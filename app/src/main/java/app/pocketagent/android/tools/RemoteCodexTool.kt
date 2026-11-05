package app.pocketagent.android.tools

import app.pocketagent.android.agent.TurnSteering
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.net.ToolSpec
import app.pocketagent.android.remote.RemoteDuplexShell
import app.pocketagent.android.remote.RemoteInputShell
import app.pocketagent.android.remote.RemoteOperations
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.RemoteStreamingShell
import app.pocketagent.android.remote.shellPath
import app.pocketagent.android.remote.shellQuote
import app.pocketagent.android.remote.wrapWithWorkDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

/** 手机附件转成远端 Codex 的 `-i` 临时文件。 */
internal data class RemoteImageUpload(
    val bytes: ByteArray,
    val extension: String,
)

/** 远端 Codex 的一个可展示进度，按 JSONL 事件增量产生。 */
data class RemoteCodexProgress(
    val text: String,
    val threadId: String? = null,
)

internal fun parseImageDataUrl(value: String): RemoteImageUpload? {
    val comma = value.indexOf(',')
    if (comma <= 0) return null
    val header = value.substring(0, comma)
    if (!header.startsWith("data:image/", ignoreCase = true) ||
        !header.endsWith(";base64", ignoreCase = true)
    ) {
        return null
    }
    val mime = header.removePrefix("data:").substringBefore(';').lowercase()
    val extension = when (mime) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> return null
    }
    val encoded = value.substring(comma + 1).filterNot { it.isWhitespace() }
    // 先按 base64 长度拒绝超大附件，避免在主线程里构造几十 MiB 的临时字符串。
    if (encoded.length > 12 * 1024 * 1024) return null
    val bytes = runCatching {
        Base64.getDecoder().decode(encoded)
    }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
    if (!matchesImageSignature(bytes, extension)) return null
    return RemoteImageUpload(bytes, extension)
}

private fun matchesImageSignature(bytes: ByteArray, extension: String): Boolean = when (extension) {
    "png" -> bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0d.toByte() && bytes[5] == 0x0a.toByte() &&
        bytes[6] == 0x1a.toByte() && bytes[7] == 0x0a.toByte()
    "jpg" -> bytes.size >= 3 &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
    "gif" -> bytes.size >= 6 &&
        bytes.copyOfRange(0, 3).contentEquals("GIF".toByteArray()) &&
        (bytes.copyOfRange(3, 6).contentEquals("87a".toByteArray()) ||
            bytes.copyOfRange(3, 6).contentEquals("89a".toByteArray()))
    "webp" -> bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
    else -> false
}

/**
 * 把一段任务交给目标机上真正安装的 Codex harness 执行。
 *
 * 手机 harness 负责对话和审批；远端 `codex exec` 负责它自己的模型、工具、sandbox、
 * skills/MCP 和 DeepSeek adapter。这里是可选的“远端大脑”，不会替换当前会话文件。
 */
class RemoteCodexTool(
    private val manager: RemoteOperations,
    private val workspaceHostId: () -> String?,
    private val workspaceDir: () -> String,
) : Tool {
    override val spec = ToolSpec(
        name = "remote_codex",
        description = "把一项复杂代码/运维任务交给当前远端主机上的官方 Codex harness 执行，" +
            "使用该机器自己的 model、config、sandbox 和 DeepSeek adapter。适合远端工作区里需要多轮工具协作为难手机 harness 的任务；" +
            "结果会返回最终回答、远端 session id 和 token 用量。该操作始终需要用户审批。",
        parametersJson = ToolSchemas.REMOTE_CODEX,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val prompt = arg(argumentsJson, "prompt")?.trim().orEmpty()
        if (prompt.isBlank()) return ToolOutcome.Error("缺少必填参数 prompt")
        val host = arg(argumentsJson, "host")?.trim()?.takeIf { it.isNotBlank() } ?: workspaceHostId()
            ?: return ToolOutcome.Error("当前 /workspace 在手机本地；先把工作区切到远端主机")
        val cwd = arg(argumentsJson, "cwd")?.trim()?.takeIf { it.isNotBlank() }
            ?: workspaceDir().ifBlank { "~" }
        val resume = arg(argumentsJson, "resume")?.trim()?.takeIf { it.isNotBlank() }
        val model = arg(argumentsJson, "model")?.trim()?.takeIf { it.isNotBlank() }
        val effort = arg(argumentsJson, "reasoning_effort")?.trim()?.takeIf { it.isNotBlank() }
        val timeout = (argLong(argumentsJson, "timeout_ms") ?: 600_000L).coerceIn(10_000L, 1_800_000L)
        val parsed = run(
            host = host,
            cwd = cwd,
            prompt = prompt,
            resume = resume,
            model = model,
            effort = effort,
            timeout = timeout,
        )
        if (!parsed.ok && parsed.finalText.isBlank()) {
            return ToolOutcome.Error(
                "远端 Codex 失败（exit=${parsed.exitCode}）：" +
                    (parsed.errors.lastOrNull() ?: "没有错误详情"),
            )
        }
        if (parsed.finalText.isBlank()) {
            return ToolOutcome.Error(
                "远端 Codex 没有返回 agent_message；" +
                    (parsed.errors.lastOrNull() ?: "请检查该主机的 Codex 配置"),
            )
        }
        ToolOutcome.Ok(buildString {
            append("远端 Codex 已完成（$host")
            parsed.threadId?.let { append("，session=").append(it) }
            append("）")
            parsed.usage?.let { (input, output) ->
                append("\ninput_tokens=").append(input)
                append(" output_tokens=").append(output)
            }
            if (parsed.errors.isNotEmpty()) {
                append("\nwarnings: ").append(parsed.errors.take(3).joinToString(" | "))
            }
            append("\n\n").append(parsed.finalText.take(30_000))
        })
    }.getOrElse { ToolOutcome.Error("远端 Codex 调用失败：${it.message ?: it.javaClass.simpleName}") }

    internal suspend fun run(
        host: String,
        cwd: String,
        prompt: String,
        resume: String?,
        model: String? = null,
        effort: String? = null,
        includeReasoning: Boolean = false,
        images: List<RemoteImageUpload> = emptyList(),
        timeout: Long = 600_000L,
        onProgress: (RemoteCodexProgress) -> Unit = {},
        steering: TurnSteering? = null,
        onSteeringApplied: suspend (List<ChatMessage>) -> Unit = {},
        onApproval: suspend (ApprovalRequest) -> Boolean = { false },
    ): RemoteCodexEvents {
        val shell = manager.shell(host)
        check(shell is RemoteInputShell) { "当前 SSH 实现不支持向远端 Codex 安全传入 stdin" }
        val imageDir = if (images.isEmpty()) null else "/tmp/pocketagent-images-${UUID.randomUUID()}"
        val imagePaths = mutableListOf<String>()
        if (imageDir != null) {
            try {
                shell.exec("umask 077; mkdir -p -- ${shellQuote(imageDir)}", 20_000)
                    .requireOk("创建远端图片临时目录")
                images.forEachIndexed { index, image ->
                    val path = "$imageDir/input-$index.${image.extension}"
                    shell.execWithInput(
                        "umask 077; cat > ${shellQuote(path)}",
                        image.bytes,
                        120_000,
                    ).requireOk("上传第 ${index + 1} 张图片")
                    imagePaths += path
                }
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    runCatching { shell.exec("rm -rf -- ${shellQuote(imageDir)}", 20_000) }
                }
                throw e
            }
        }
        try {
            if (steering != null && shell is RemoteDuplexShell && supportsRemoteAppServer(
                    shell, manager.host(host)?.codexExecutable.orEmpty(),
                )
            ) {
                try {
                    return RemoteCodexSession(
                        shell, cwd, prompt, resume, model, effort, includeReasoning, imagePaths, timeout,
                        manager.host(host)?.codexExecutable.orEmpty(), steering, onProgress, onSteeringApplied, onApproval,
                    ).run()
                } catch (_: RemoteAppServerUnsupported) {
                    // Only explicit method-not-found before any accepted turn permits safe replay.
                }
            }
            if (steering != null) onProgress(RemoteCodexProgress(
                "当前远端版本暂不支持运行中接入；补充要求将在本轮结束后发送",
            ))
            return runWithShell(
                shell = shell,
                cwd = cwd,
                prompt = prompt,
                resume = resume,
                model = model,
                effort = effort,
                includeReasoning = includeReasoning,
                images = imagePaths,
                timeout = timeout,
                onProgress = onProgress,
                executable = manager.host(host)?.codexExecutable.orEmpty(),
            )
        } finally {
            if (imageDir != null) {
                withContext(NonCancellable) {
                    runCatching { shell.exec("rm -rf -- ${shellQuote(imageDir)}", 20_000) }
                }
            }
        }
    }

    private suspend fun runWithShell(
        shell: RemoteInputShell,
        cwd: String,
        prompt: String,
        resume: String?,
        model: String?,
        effort: String?,
        includeReasoning: Boolean,
        images: List<String>,
        timeout: Long,
        onProgress: (RemoteCodexProgress) -> Unit,
        executable: String,
    ): RemoteCodexEvents {
        val modelArg = model?.let { " -m ${shellQuote(it)}" }.orEmpty()
        val effortArg = effort?.let {
            " -c ${shellQuote("model_reasoning_effort=\"$it\"")}"
        }.orEmpty()
        val imageArg = images.joinToString("") { " -i ${shellQuote(it)}" }
        val execute = if (resume == null) {
            "\"\$CODEX_BIN\" exec --json --skip-git-repo-check" +
                "$modelArg$effortArg$imageArg -C ${shellPath(cwd)} -"
        } else {
            wrapWithWorkDir(
                "\"\$CODEX_BIN\" exec resume --json --all --skip-git-repo-check" +
                    "$modelArg$effortArg$imageArg ${shellQuote(resume)} -",
                cwd,
            )
        }
        // 默认不把 reasoning JSON 传回手机：它可能很大，而且同一段内容不会展示。
        // 用 FIFO + awk 在远端过滤，仍然逐行流式输出，并保留 codex 的真实退出码。
        val invoke = if (includeReasoning) {
            execute
        } else {
            "FILTER_FIFO=\$(mktemp \"\${TMPDIR:-/tmp}/pocketagent-filter.XXXXXX\")\n" +
                "rm -f -- \"\$FILTER_FIFO\"\n" +
                "mkfifo \"\$FILTER_FIFO\" || exit 1\n" +
                // 只匹配 item 对象里的 reasoning，避免把正文中恰好出现该字符串的消息过滤掉。
                "awk '!/\"item\":\\{\"type\":\"reasoning\"/ { print; fflush() }' < \"\$FILTER_FIFO\" &\n" +
                "FILTER_PID=\$!\n" +
                "$execute > \"\$FILTER_FIFO\"\n" +
                "STATUS=\$?\n" +
                "wait \"\$FILTER_PID\"\n" +
                "rm -f -- \"\$FILTER_FIFO\"\n" +
                "exit \$STATUS"
        }
        val command = codexDiscovery(executable = executable) + "\n" + invoke
        val result = if (shell is RemoteStreamingShell) {
            shell.execStreaming(
                command,
                prompt.toByteArray(Charsets.UTF_8),
                timeout,
            ) { line ->
                parseRemoteCodexProgress(line)?.let { progress ->
                    runCatching { onProgress(progress) }
                }
            }
        } else {
            shell.execWithInput(command, prompt.toByteArray(Charsets.UTF_8), timeout)
        }
        return parseRemoteCodexEvents(result, includeReasoning)
    }

    suspend fun isAvailable(host: String): Boolean {
        return detectRemoteCodex(manager.shell(host), executable = manager.host(host)?.codexExecutable.orEmpty())
    }
}

/** A timeout or broken runtime must never be mistaken for an uninstalled harness. */
internal suspend fun detectRemoteCodex(shell: app.pocketagent.android.remote.RemoteShell, timeoutMs: Long = 20_000, executable: String = ""): Boolean {
    val result = shell.exec(
        codexDiscovery(reportMissing = false, executable = executable) +
            "\n\"\$CODEX_BIN\" --version || exit $?\n" +
            "CODEX_HELP=$(\"\$CODEX_BIN\" exec --help) || exit $?\n" +
            "printf '%s\\n' \"\$CODEX_HELP\" | grep -q -- '--json' || " +
            "{ echo 'Codex 版本不兼容：需要支持 exec --json，请升级远端 CLI' >&2; exit 126; }",
        timeoutMs,
    )
    if (!result.timedOut && result.exitCode == 127 &&
        result.stdout.trim() == "POCKETAGENT_CODEX_NOT_FOUND") return false
    result.requireOk("远端 Codex 检测（请检查安装、Node 和 SSH；未切换到手机 harness）")
    return true
}

internal fun codexDiscovery(reportMissing: Boolean = true, executable: String = ""): String {
        val configured = executable.trim()
        require(configured.isBlank() || configured.startsWith("/") || configured.startsWith("~/")) {
            "Codex 路径必须是绝对路径或 ~/ 路径；留空自动检测"
        }
        val selection = if (configured.isBlank()) {
            "CODEX_BIN=\$(command -v codex 2>/dev/null || true)\n" +
                "if [ -z \"\$CODEX_BIN\" ]; then\n" +
                "  for candidate in \"\$HOME\"/.nvm/versions/node/*/bin/codex \"\$HOME\"/.local/share/fnm/node-versions/*/installation/bin/codex \"\$HOME\"/.local/share/mise/installs/node/*/bin/codex; do\n" +
                "    if [ -x \"\$candidate\" ]; then CODEX_BIN=\$candidate; fi\n" +
                "  done\nfi\n"
        } else {
            "CODEX_BIN=${shellPath(configured)}\n" +
                "[ -x \"\$CODEX_BIN\" ] || { echo '指定的 Codex 文件不存在或不可执行，请修改设置中的路径' >&2; exit 126; }\n"
        }
        val missing = if (reportMissing) {
            "{ echo '远端没有找到 codex（已检查 PATH、Homebrew、NVM、Volta、asdf 和 bun）' >&2; exit 127; }"
        } else {
            "{ echo POCKETAGENT_CODEX_NOT_FOUND; exit 127; }"
        }
        return "PATH=\"\$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:" +
            "\$HOME/.codex/bin:\$HOME/.npm-global/bin:\$HOME/.volta/bin:\$HOME/.asdf/shims:\$HOME/.bun/bin:\$PATH\"\n" +
            "export PATH\n" +
            selection +
            "[ -n \"\$CODEX_BIN\" ] || $missing\n" +
            "CODEX_DIR=\$(dirname \"\$CODEX_BIN\")\n" +
            "PATH=\"\$CODEX_DIR:\$PATH\"\n" +
            "export PATH"
}

internal data class RemoteCodexEvents(
    val ok: Boolean,
    val exitCode: Int,
    val threadId: String?,
    val reasoning: List<String>,
    val finalText: String,
    val errors: List<String>,
    val usage: Pair<Int, Int>?,
    /** Sent over SSH but neither an RPC reply nor native userMessage verified receipt. Never auto-replay. */
    val unconfirmedSteeringIds: Set<String> = emptySet(),
)

private suspend fun supportsRemoteAppServer(shell: RemoteInputShell, executable: String): Boolean {
    val result = shell.exec(codexDiscovery(executable = executable) + "\n\"\$CODEX_BIN\" app-server --help", 20_000)
    if (result.ok) return true
    val detail = result.stderr + "\n" + result.stdout
    if (!result.timedOut && result.exitCode in listOf(1, 2) &&
        Regex("(?i)(unrecognized subcommand|unknown (sub)?command|invalid (sub)?command).*app-server")
            .containsMatchIn(detail)
    ) return false
    result.requireOk("远端 app-server 检测")
    return false
}

internal fun parseRemoteCodexEvents(
    result: RemoteResult,
    includeReasoning: Boolean = true,
): RemoteCodexEvents {
    var threadId: String? = null
    var finalText = ""
    val reasoning = mutableListOf<String>()
    val errors = mutableListOf<String>()
    var usage: Pair<Int, Int>? = null
    result.stdout.lineSequence().filter { it.isNotBlank() }.forEach { raw ->
        val node = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@forEach
        when (node.string("type")) {
            "thread.started" -> threadId = node.string("thread_id") ?: threadId
            "item.completed" -> {
                val item = node["item"] as? JsonObject ?: return@forEach
                when (item.string("type")) {
                    "agent_message" -> item.string("text")?.trim()?.takeIf { it.isNotBlank() }?.let {
                        finalText = it
                    }
                    "reasoning" -> if (includeReasoning) {
                        item.string("text")?.trim()?.takeIf { it.isNotBlank() }?.let {
                            reasoning += it
                        }
                    }
                    "error" -> item.string("message")?.takeIf { it.isNotBlank() }?.let { errors += it }
                }
            }
            "turn.completed" -> {
                val value = node["usage"] as? JsonObject
                if (value != null) {
                    usage = (value.int("input_tokens") ?: 0) to (value.int("output_tokens") ?: 0)
                }
            }
            "turn.failed", "error" -> {
                node.string("message")?.takeIf { it.isNotBlank() }?.let { errors += it }
            }
        }
    }
    if (finalText.isBlank() && result.stdout.isNotBlank() && result.stdout.lineSequence().none {
            it.trimStart().startsWith("{")
        }
    ) {
        finalText = result.stdout.take(30_000)
    }
    if (result.stderr.isNotBlank()) errors += result.stderr.take(2_000)
    return RemoteCodexEvents(
        result.ok,
        result.exitCode,
        threadId,
        reasoning.distinct(),
        finalText,
        errors.distinct(),
        usage,
    )
}

/**
 * 把 `codex exec --json` 的 JSONL 事件转成适合手机展示的一行进度。
 * 只读取类型和命令/工具名，不展示命令输出正文，避免进度里泄漏大段内容。
 */
internal fun parseRemoteCodexProgress(raw: String): RemoteCodexProgress? {
    val node = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    return when (val type = node.string("type")) {
        "thread.started" -> RemoteCodexProgress(
            "已连接远端 Codex session",
            node.string("thread_id"),
        )
        "turn.started" -> RemoteCodexProgress("远端 Codex 开始处理")
        "turn.completed" -> RemoteCodexProgress("正在汇总最终结果")
        "item.started", "item.completed" -> {
            val item = node["item"] as? JsonObject ?: return null
            val started = type == "item.started"
            when (item.string("type")) {
                "command_execution" -> {
                    val command = item.string("command")
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.take(140)
                    val prefix = if (started) "正在执行命令" else "命令已完成"
                    RemoteCodexProgress(if (command.isNullOrBlank()) prefix else "$prefix：$command")
                }
                "mcp_tool_call" -> {
                    val server = item.string("server")?.takeIf { it.isNotBlank() }
                    val tool = item.string("tool")?.takeIf { it.isNotBlank() }
                    val name = listOfNotNull(server, tool).joinToString("/")
                    val prefix = if (started) "正在调用 MCP" else "MCP 调用完成"
                    RemoteCodexProgress(if (name.isBlank()) prefix else "$prefix：$name")
                }
                "file_change" -> RemoteCodexProgress(
                    if (started) "正在修改文件" else "文件已修改",
                )
                "web_search" -> RemoteCodexProgress(
                    if (started) "正在搜索网页" else "网页搜索完成",
                )
                "reasoning" -> if (started) RemoteCodexProgress("思考中…") else null
                "agent_message" -> if (started) null else RemoteCodexProgress("已生成回答，正在整理")
                else -> null
            }
        }
        else -> null
    }
}

private fun JsonObject.string(key: String): String? =
    runCatching { (this[key] ?: return null).jsonPrimitive.contentOrNull }.getOrNull()

private fun JsonObject.int(key: String): Int? =
    runCatching { (this[key] ?: return null).jsonPrimitive.intOrNull }.getOrNull()
