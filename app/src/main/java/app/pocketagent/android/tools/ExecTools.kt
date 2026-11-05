package app.pocketagent.android.tools

import app.pocketagent.android.net.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 在手机工作区启动后台命令；后续用 write_stdin 轮询、写输入或终止。 */
class ExecCommandTool(private val sessions: ProcessSessionManager) : Tool {
    override val spec = ToolSpec(
        name = "exec_command",
        description = "在手机工作区启动后台 shell 进程，返回 session_id 和初始输出；之后用 write_stdin " +
            "轮询增量输出、写 stdin 或终止。适合长任务和需要输入的命令。它不是远端 shell；" +
            "远端命令用 ssh_exec，复杂远端任务用 remote_codex。",
        parametersJson = ToolSchemas.EXEC_COMMAND,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = withContext(Dispatchers.IO) {
        runToolCatching {
            val command = arg(argumentsJson, "command")?.trim().orEmpty()
            if (command.isBlank()) return@runToolCatching ToolOutcome.Error("缺少 command")
            val yieldMs = (argInt(argumentsJson, "yield_time_ms") ?: 1_000)
                .coerceIn(0, 30_000).toLong()
            val maxChars = (argInt(argumentsJson, "max_output_chars") ?: 12_000)
                .coerceIn(100, 30_000)
            val snapshot = sessions.start(command, yieldMs, maxChars)
            ToolOutcome.Ok(renderProcess(snapshot))
        }.getOrElse {
            ToolOutcome.Error("启动命令失败：${it.message ?: it.javaClass.simpleName}")
        }
    }
}

/** 轮询/写入/终止 exec_command 创建的会话。 */
class WriteStdinTool(private val sessions: ProcessSessionManager) : Tool {
    override val spec = ToolSpec(
        name = "write_stdin",
        description = "向 exec_command 启动的会话写 stdin 或轮询输出；chars 为空表示只轮询，" +
            "kill=true 强制终止。每次返回自上次调用以来的增量输出。",
        parametersJson = ToolSchemas.WRITE_STDIN,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = withContext(Dispatchers.IO) {
        runToolCatching {
            val sessionId = arg(argumentsJson, "session_id")?.trim().orEmpty()
            if (sessionId.isBlank()) return@runToolCatching ToolOutcome.Error("缺少 session_id")
            val chars = arg(argumentsJson, "chars") ?: ""
            val yieldMs = (argInt(argumentsJson, "yield_time_ms") ?: 1_000)
                .coerceIn(0, 30_000).toLong()
            val maxChars = (argInt(argumentsJson, "max_output_chars") ?: 12_000)
                .coerceIn(100, 30_000)
            val snapshot = if (argBoolean(argumentsJson, "kill") == true) {
                sessions.kill(sessionId)
            } else {
                sessions.write(sessionId, chars, yieldMs, maxChars)
            }
            ToolOutcome.Ok(renderProcess(snapshot))
        }.getOrElse {
            ToolOutcome.Error("write_stdin 失败：${it.message ?: it.javaClass.simpleName}")
        }
    }
}

internal fun renderProcess(snapshot: ProcessSnapshot): String = buildString {
    append("session_id=").append(snapshot.sessionId).append('\n')
    append("status=").append(if (snapshot.running) "running" else "exited")
    snapshot.exitCode?.let { append(" exit=").append(it) }
    append('\n')
    if (snapshot.output.isBlank()) {
        append("(no new output)\n")
    } else {
        append(snapshot.output)
        if (!snapshot.output.endsWith('\n')) append('\n')
    }
    if (snapshot.truncated) {
        append("…（输出已截断；继续 write_stdin 轮询，或让命令写日志文件后用 fs_read）\n")
    }
    if (snapshot.running) {
        append("（进程仍在运行；用 write_stdin 继续轮询）")
    }
}
