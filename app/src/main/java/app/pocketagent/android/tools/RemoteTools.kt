package app.pocketagent.android.tools

import app.pocketagent.android.net.ToolSpec
import app.pocketagent.android.remote.DEFAULT_MAX_READ_BYTES
import app.pocketagent.android.remote.MAX_READ_BYTES
import app.pocketagent.android.remote.RemoteConnectionException
import app.pocketagent.android.remote.RemoteOperations
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.ShellRemoteFs
import app.pocketagent.android.remote.clipKeepingEnds

private const val MAX_STDOUT_CHARS = 30_000
private const val MAX_STDERR_CHARS = 12_000
private const val MAX_LIST_ENTRIES = 300
private const val DEFAULT_EXEC_TIMEOUT_MS = 120_000L
private const val MIN_EXEC_TIMEOUT_MS = 1_000L
private const val MAX_EXEC_TIMEOUT_MS = 600_000L

/** 在配置好的远程主机（remote-host / Mac / Orin）上执行非交互式命令。 */
class RemoteExecTool(private val manager: RemoteOperations) : Tool {
    override val spec = ToolSpec(
        name = "ssh_exec",
        description = "在配置好的远程主机上执行非交互式 shell 命令，返回 exit code、stdout、stderr。" +
            "每次调用都是独立 shell，cd/export 不会保留；需要时在同一 command 里用 &&。stdin 会立即 EOF。" +
            "默认超时 120 秒；长任务请用 nohup 重定向到日志并在后续轮询，输出过大时结果会保留头尾并提示截断。",
        parametersJson = ToolSchemas.SSH_EXEC,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val command = arg(argumentsJson, "command")
            ?: return ToolOutcome.Error("缺少必填参数 command。示例：{\"command\":\"pwd && git status --short\"}")
        if (command.isBlank()) return ToolOutcome.Error("command 不能为空")

        val timeout = argLong(argumentsJson, "timeout_ms") ?: DEFAULT_EXEC_TIMEOUT_MS
        if (timeout !in MIN_EXEC_TIMEOUT_MS..MAX_EXEC_TIMEOUT_MS) {
            return ToolOutcome.Error(
                "timeout_ms 必须在 $MIN_EXEC_TIMEOUT_MS..$MAX_EXEC_TIMEOUT_MS 毫秒之间，收到 $timeout",
            )
        }
        val host = arg(argumentsJson, "host")?.trim()?.takeIf { it.isNotEmpty() }
        val result = manager.exec(command, timeout, host)
        // 桌面 Codex 截断输出时会把全文落到临时文件；这里同样保存到远端 /tmp，
        // 模型可以直接 ssh_read 续读，不用重复执行同一条（可能很贵的）命令。
        val spillPath = spillRemoteOutput(manager, host, result)
        val text = formatCommandResult(result, timeout, spillPath)
        if (result.ok) ToolOutcome.Ok(text) else ToolOutcome.Error(text)
    }.getOrElse { ToolOutcome.Error("远程命令失败：${remoteErrorMessage(it)}") }
}

private const val SPILL_DIR = "/tmp"
private const val MAX_SPILL_CHARS = 2_000_000

/** 只在输出被截断时才写远端临时文件；写失败就退回纯文字提示。 */
private suspend fun spillRemoteOutput(
    manager: RemoteOperations,
    host: String?,
    result: RemoteResult,
): String? {
    val clipped = result.stdout.length > MAX_STDOUT_CHARS || result.stderr.length > MAX_STDERR_CHARS
    if (!clipped) return null
    return runCatching {
        val name = "$SPILL_DIR/pocketagent-exec-" + System.currentTimeMillis() + "-" +
            java.util.UUID.randomUUID().toString().take(6) + ".log"
        val body = buildString {
            append(result.stdout.take(MAX_SPILL_CHARS))
            if (result.stderr.isNotBlank()) {
                append("\n--- stderr ---\n")
                append(result.stderr.take(MAX_SPILL_CHARS))
            }
        }
        ShellRemoteFs(manager.shell(host)).write(name, body)
        name
    }.getOrNull()
}

class RemoteReadTool(private val manager: RemoteOperations) : Tool {
    override val spec = ToolSpec(
        name = "ssh_read",
        description = "分页读取远程主机上的文本文件（相对 workDir 或绝对路径），默认 256KB、最大 1MB。" +
            "offset 是字节偏移；结果截断时会给出 next_offset，下一次保持 path 不变并传该 offset。",
        parametersJson = ToolSchemas.SSH_READ,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty()
        if (path.isBlank()) {
            return ToolOutcome.Error("缺少必填参数 path。示例：{\"path\":\"/tmp/build.log\"}")
        }
        val offset = argLong(argumentsJson, "offset") ?: 0L
        if (offset < 0) return ToolOutcome.Error("offset 不能为负数：$offset")
        val limit = argInt(argumentsJson, "limit") ?: DEFAULT_MAX_READ_BYTES
        if (limit !in 1..MAX_READ_BYTES) {
            return ToolOutcome.Error("limit 必须在 1..$MAX_READ_BYTES 之间，收到 $limit")
        }
        val host = arg(argumentsJson, "host")?.trim()?.takeIf { it.isNotEmpty() }
        val fs = ShellRemoteFs(manager.shell(host))
        ToolOutcome.Ok(fs.read(path, limit, offset))
    }.getOrElse { ToolOutcome.Error("远程读取失败：${remoteErrorMessage(it)}") }
}

class RemoteWriteTool(private val manager: RemoteOperations) : Tool {
    override val spec = ToolSpec(
        name = "ssh_write",
        description = "把完整内容写到远程主机的文件（覆盖，不是局部 patch），目录不存在会先建。" +
            "修改已有文件前先用 ssh_read；提交的是整个文件的新内容。",
        parametersJson = ToolSchemas.SSH_WRITE,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty()
        if (path.isBlank()) {
            return ToolOutcome.Error("缺少必填参数 path。示例：{\"path\":\"src/a.kt\",\"content\":\"...\"}")
        }
        val content = arg(argumentsJson, "content")
            ?: return ToolOutcome.Error("缺少必填参数 content（空文件也要显式传空字符串）")
        val host = arg(argumentsJson, "host")?.trim()?.takeIf { it.isNotEmpty() }
        val fs = ShellRemoteFs(manager.shell(host))
        fs.write(path, content)
        ToolOutcome.Ok("已写入 $path（${content.toByteArray().size} 字节）")
    }.getOrElse { ToolOutcome.Error("远程写入失败：${remoteErrorMessage(it)}") }
}

class RemoteListTool(private val manager: RemoteOperations) : Tool {
    override val spec = ToolSpec(
        name = "ssh_ls",
        description = "列出远程主机某个目录的直接子项（非递归，不显示大小）。目录很多时会提示截断；" +
            "需要过滤、递归或看权限/大小时改用 ssh_exec 的 find / rg / ls -lh。",
        parametersJson = ToolSchemas.SSH_LS,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty().ifBlank { "." }
        val host = arg(argumentsJson, "host")?.trim()?.takeIf { it.isNotEmpty() }
        val fs = ShellRemoteFs(manager.shell(host))
        val entries = fs.list(path)
        if (entries.isEmpty()) return ToolOutcome.Ok("（空目录）")
        ToolOutcome.Ok(
            entries.take(MAX_LIST_ENTRIES).joinToString("\n") +
                if (entries.size > MAX_LIST_ENTRIES) {
                    "\n…（只显示前 $MAX_LIST_ENTRIES 项，另有 ${entries.size - MAX_LIST_ENTRIES} 项；" +
                        "缩小 path 或改用 ssh_exec find）"
                } else {
                    ""
                },
        )
    }.getOrElse { ToolOutcome.Error("远程列目录失败：${remoteErrorMessage(it)}") }
}

internal fun formatCommandResult(
    result: RemoteResult,
    timeoutMs: Long,
    spillPath: String? = null,
): String = buildString {
    append("exit=").append(result.exitCode)
    if (result.timedOut) append("（超时）")
    appendStream("stdout", result.stdout, MAX_STDOUT_CHARS, spillPath)
    appendStream("stderr", result.stderr, MAX_STDERR_CHARS, spillPath)
    if (result.stdout.isBlank() && result.stderr.isBlank()) {
        append("\n（无 stdout/stderr）")
    }
    if (result.timedOut) {
        append("\n…（已达到 timeout_ms=$timeoutMs；远端进程可能仍在运行。" +
            "长任务请用 nohup ... > /tmp/task.log 2>&1 &，再 ssh_read /tmp/task.log）")
    }
}.trim()

private fun StringBuilder.appendStream(label: String, text: String, maxChars: Int, spillPath: String?) {
    if (text.isBlank()) return
    append('\n').append(clipKeepingEnds(text, maxChars, label))
    if (text.length > maxChars) {
        if (spillPath != null) {
            append("\n…（$label 过大；完整输出已保存到远端 `$spillPath`，" +
                "用 ssh_read 读取或按它返回的 next_offset 续读，不要重复执行这条命令）")
        } else {
            append("\n…（$label 过大；缩小命令输出可用 grep/head/tail，" +
                "或将完整输出重定向到远端文件后用 ssh_read 按 offset 续读）")
        }
    }
}

private fun remoteErrorMessage(e: Throwable): String = when (e) {
    is RemoteConnectionException -> e.message ?: "连接失败"
    is IllegalArgumentException -> e.message ?: "参数错误"
    else -> e.message ?: e.javaClass.simpleName
}
