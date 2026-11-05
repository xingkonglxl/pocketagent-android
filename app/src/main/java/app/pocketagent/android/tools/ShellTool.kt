package app.pocketagent.android.tools

import app.pocketagent.android.net.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 手机本地 shell（Android 自带 toybox：ls/cat/grep/sed/find/awk/date 等）。
 * 桌面 Codex 最常用的 exec 能力在手机上是缺的，模型只能逐层 fs_list 猜目录。
 * cwd 固定为工作区根目录；没有 root，也不能装软件；重的编译任务仍走 ssh_exec。
 *
 * 输出过长时不会只丢给模型一段“已省略”：完整捕获的内容会落到工作区日志文件，
 * 模型可以按 fs_read 的 offset 继续读，行为和桌面 Codex 的截断提示一致。
 */
class ShellExecTool(
    private val root: File,
    private val maxOutputChars: Int = 30_000,
) : Tool {
    override val spec = ToolSpec(
        name = "shell_exec",
        description = "在手机工作区执行 shell 命令（Android 自带 toybox：ls、cat、grep、sed、find、awk、date 等），" +
            "cwd 固定为工作区根目录，没有 root，不能安装软件。编译/长时间任务请用 ssh_exec 到远端机器。",
        parametersJson = ToolSchemas.SHELL_EXEC,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = withContext(Dispatchers.IO) {
        try {
            val command = arg(argumentsJson, "command")?.trim().orEmpty()
            if (command.isBlank()) return@withContext ToolOutcome.Error("缺少 command")
            val timeoutSec = (argInt(argumentsJson, "timeout") ?: 30).coerceIn(1, 300)
            val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"

            val process = ProcessBuilder(shell, "-c", command)
                .directory(root)
                .redirectErrorStream(true)
                .start()
            val buffer = StringBuilder()
            var droppedOutput = false
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(buffer) {
                            if (buffer.length < 400_000) buffer.append(line).append('\n')
                            else droppedOutput = true
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val finished = process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(500)
                val partial = synchronized(buffer) { buffer.toString().take(2_000) }
                return@withContext ToolOutcome.Error(
                    "命令超过 ${timeoutSec}s 未结束，已强制终止。输出片段：\n$partial",
                )
            }
            reader.join(1_000)
            val text = synchronized(buffer) { buffer.toString() }
            val clipped = clip(text, droppedOutput)
            val code = process.exitValue()
            if (code == 0) {
                ToolOutcome.Ok(clipped.ifBlank { "（命令成功，没有输出）" })
            } else {
                ToolOutcome.Error("退出码 $code\n$clipped")
            }
        } catch (e: Exception) {
            ToolOutcome.Error("执行失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun clip(text: String, droppedOutput: Boolean): String =
        if (text.length <= maxOutputChars) {
            if (droppedOutput) {
                text + "\n…（命令输出超过手机缓冲上限 400000 字符，后面的内容未保留；" +
                    "请把命令输出重定向到文件后用 fs_read 分页读）"
            } else {
                text
            }
        } else {
            val omitted = text.length - maxOutputChars
            val spilled = spill(text)
            val hint = if (spilled != null) {
                "…（输出过长，中间约 $omitted 字符已省略；完整输出共 ${text.length} 字符已写入 " +
                    "`$spilled`，用 fs_read 读取或按它返回的 next_offset 续读）…"
            } else {
                "…（输出过长，已省略中间约 $omitted 字符，写日志也失败；" +
                    "请把命令输出重定向到工作区文件后用 fs_read 分页读）…"
            }
            val tailNote = if (droppedOutput) {
                "…（命令输出超过手机缓冲上限 400000 字符，更靠后的内容未保留）…\n"
            } else {
                ""
            }
            text.take(maxOutputChars / 2) + "\n" + hint + "\n" + tailNote +
                text.takeLast(maxOutputChars / 2)
        }

    /** 把完整捕获写出到 `.pocketagent/logs/`，返回相对工作区根的路径；失败返回 null。 */
    private fun spill(text: String): String? = runCatching {
        val dir = File(root, SPILL_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = "shell-" + System.currentTimeMillis() + "-" +
            java.util.UUID.randomUUID().toString().take(6) + ".log"
        val file = File(dir, name)
        file.writeText(text.take(MAX_SPILL_CHARS), Charsets.UTF_8)
        pruneSpillFiles(dir)
        "$SPILL_DIR/$name"
    }.getOrNull()

    private fun pruneSpillFiles(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("shell-") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(MAX_SPILL_FILES).forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val SPILL_DIR = ".pocketagent/logs"
        const val MAX_SPILL_CHARS = 2_000_000
        const val MAX_SPILL_FILES = 20
    }
}
