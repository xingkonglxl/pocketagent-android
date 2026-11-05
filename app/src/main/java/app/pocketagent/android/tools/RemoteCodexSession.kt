package app.pocketagent.android.tools

import app.pocketagent.android.agent.TurnSteering
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.remote.RemoteDuplexShell
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.wrapWithWorkDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Safe fallback is allowed only when a protocol method was explicitly rejected before execution. */
internal class RemoteAppServerUnsupported(message: String) : Exception(message)

/** An app-server connection owns exactly one turn. Never replay it after execution has started. */
internal class RemoteCodexSession(
    private val shell: RemoteDuplexShell,
    private val cwd: String,
    private val prompt: String,
    private val resume: String?,
    private val model: String?,
    private val effort: String?,
    private val includeReasoning: Boolean,
    private val images: List<String>,
    private val timeout: Long,
    private val executable: String,
    private val steering: TurnSteering,
    private val onProgress: (RemoteCodexProgress) -> Unit,
    private val onSteeringApplied: suspend (List<ChatMessage>) -> Unit,
    private val onApproval: suspend (ApprovalRequest) -> Boolean,
) {
    private var sequence = 0
    private var threadId: String? = null
    private var activeTurnId: String? = null
    private var completed = false
    private var turnStatus = ""
    private var usage: Pair<Int, Int>? = null
    private val messages = linkedMapOf<String, String>()
    private val phases = mutableMapOf<String, String?>()
    private val reasoning = linkedMapOf<String, String>()
    private val errors = mutableListOf<String>()
    private val attempted = mutableSetOf<String>()
    private val observedUserIds = mutableSetOf<String>()
    private var inFlight: SteeringRequest? = null

    private data class SteeringRequest(val id: Int, val message: ChatMessage, val turnId: String)

    suspend fun run(): RemoteCodexEvents = coroutineScope {
        val outgoing = Channel<ByteArray>(32)
        val incoming = Channel<JsonObject>(256)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val transport = async {
            try {
                shell.execDuplex(command(), timeout, outgoing) { line ->
                    val node = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    if (node != null && incoming.trySend(node).isFailure) {
                        incoming.close(IllegalStateException("远端事件过多，已停止以免丢失补充要求"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                incoming.close(e)
                RemoteResult(255, stderr = e.message ?: e.javaClass.simpleName)
            } finally {
                incoming.close()
            }
        }
        val watcher = launch { steering.pending.collect { wake.trySend(Unit) } }
        suspend fun disconnected(stage: String): Nothing {
            val transportResult = withTimeoutOrNull(3_000) { transport.await() }
            val detail = transportResult?.stderr?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { redactRemoteDiagnostic(it).take(2_000) }
            error(buildString {
                append("远端 app-server 在 ").append(stage).append("完成前断开")
                transportResult?.let { append("（exit=").append(it.exitCode).append("）") }
                detail?.let { append("：").append(it) }
            })
        }
        suspend fun send(node: JsonObject) {
            outgoing.send((node.toString() + "\n").toByteArray(Charsets.UTF_8))
        }
        suspend fun request(method: String, params: JsonObject): Int {
            val id = ++sequence
            send(buildJsonObject { put("id", id); put("method", method); put("params", params) })
            return id
        }
        suspend fun applyConfirmed(pending: SteeringRequest) {
            inFlight = null
            val applied = steering.acknowledge(setOf(pending.message.id))
            // Clearing the unsent queue cannot undo a request already accepted by the server.
            withContext(NonCancellable) { onSteeringApplied(applied.ifEmpty { listOf(pending.message) }) }
            progress("补充要求已送入远端当前任务")
        }
        suspend fun dispatch(node: JsonObject) {
            val method = node.text("method")
            if (method != null && node.containsKey("id")) {
                // Preserve the remote approval policy and route one-off approvals to the phone UI.
                val id = node.getValue("id")
                val approvalMethod = method in listOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval")
                val approved = if (!approvalMethod) false else coroutineScope {
                    if (steering.peek().any { it.id !in attempted }) return@coroutineScope false
                    val approval = async {
                        onApproval(ApprovalRequest(
                            callId = "remote-$id",
                            toolName = if (method.contains("commandExecution")) "remote_shell_exec" else "remote_file_write",
                            argumentsJson = (node["params"] as? JsonObject)?.toString().orEmpty(),
                            sessionAllowable = false,
                        ))
                    }
                    val supplement = async { steering.pending.first { pending -> pending.any { it.id !in attempted } } }
                    try {
                        select<Boolean> {
                            supplement.onAwait { false }
                            approval.onAwait { it }
                        }
                    } finally {
                        approval.cancelAndJoin()
                        supplement.cancelAndJoin()
                    }
                }
                val response = buildJsonObject {
                    put("id", id)
                    if (approvalMethod) {
                        put("result", buildJsonObject { put("decision", if (approved) "accept" else "decline") })
                    } else {
                        put("error", buildJsonObject {
                            put("code", -32601)
                            put("message", "手机尚不支持此交互，请在远端处理后继续")
                        })
                    }
                }
                send(response)
                if (!approvalMethod) {
                    errors += "远端需要交互确认（$method），未自动批准；请在远端处理后继续"
                    progress("远端需要交互确认，未自动批准")
                } else progress(if (approved) "已批准本次远端操作" else "本次远端操作未获批准")
                return
            }
            if (method != null) {
                notification(method, node["params"] as? JsonObject ?: return)
                inFlight?.takeIf { it.message.id in observedUserIds }?.let { applyConfirmed(it) }
                return
            }
            val pending = inFlight ?: return
            if (node.number("id") != pending.id) return
            val result = node["result"] as? JsonObject
            if (node["error"] == null && result?.text("turnId") == pending.turnId) {
                applyConfirmed(pending)
            } else if (node["error"] != null) {
                inFlight = null
                progress("补充要求未接入当前轮，将在下一轮继续")
            } else {
                progress("补充要求的远端确认不一致，正在核实")
            }
        }
        suspend fun rpc(method: String, params: JsonObject): JsonObject {
            val id = request(method, params)
            return withTimeout(minOf(timeout, 30_000L)) {
                while (true) {
                    val node = incoming.receiveCatching().getOrNull() ?: disconnected(method)
                    if (node.text("method") == null && node.number("id") == id) {
                        val rpcError = node["error"] as? JsonObject
                        if (rpcError?.number("code") == -32601 && activeTurnId == null && method in listOf(
                                "initialize", "thread/start", "thread/resume", "turn/start",
                            )
                        ) throw RemoteAppServerUnsupported("远端不支持 $method")
                        if (rpcError != null) error("$method：${rpcError.text("message") ?: "协议请求失败"}")
                        return@withTimeout node["result"] as? JsonObject
                            ?: error("$method 返回了无效结果")
                    }
                    dispatch(node)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        }
        suspend fun steerPending() {
            val turn = activeTurnId ?: return
            val thread = threadId ?: return
            if (completed || inFlight != null) return
            val message = steering.peek().firstOrNull { it.id !in attempted } ?: return
            attempted += message.id
            // Invalid attachments remain queued intact rather than accepting only half a message.
            if (message.images.any { parseImageDataUrl(it) == null }) {
                progress("补充附件需下一轮处理，文字与附件均已保留")
                wake.trySend(Unit)
                return
            }
            val id = request("turn/steer", buildJsonObject {
                put("threadId", thread)
                put("expectedTurnId", turn)
                put("clientUserMessageId", message.id)
                put("input", buildJsonArray {
                    if (message.text.isNotBlank()) add(textInput(message.text))
                    message.images.forEach { url -> add(buildJsonObject { put("type", "image"); put("url", url) }) }
                })
            })
            inFlight = SteeringRequest(id, message, turn)
        }
        try {
            withTimeout(timeout) {
                rpc("initialize", buildJsonObject {
                    put("clientInfo", buildJsonObject {
                        put("name", "pocketagent_android"); put("version", "1"); put("title", "PocketAgent")
                    })
                    put("capabilities", buildJsonObject {
                        // Filtering happens on the server before bytes cross the mobile connection.
                        put("optOutNotificationMethods", buildJsonArray {
                            listOf(
                                "item/commandExecution/outputDelta", "command/exec/outputDelta", "process/outputDelta",
                                "item/fileChange/outputDelta", "item/fileChange/patchUpdated", "turn/diff/updated",
                            ).forEach { add(JsonPrimitive(it)) }
                            if (!includeReasoning) listOf(
                                "item/reasoning/textDelta", "item/reasoning/summaryTextDelta", "item/reasoning/summaryPartAdded",
                            ).forEach { add(JsonPrimitive(it)) }
                        })
                    })
                })
                send(buildJsonObject { put("method", "initialized") })
                val thread = rpc(if (resume == null) "thread/start" else "thread/resume", buildJsonObject {
                    if (resume != null) { put("threadId", resume); put("excludeTurns", true) }
                    // cwd is resolved by the shell; omit the literal '~' from protocol paths.
                    if (cwd.startsWith("/")) put("cwd", cwd)
                    model?.let { put("model", it) }
                })
                threadId = (thread["thread"] as? JsonObject)?.text("id")
                    ?: error("远端 app-server 未返回 session id")
                progress("已连接远端 Codex，可在运行中补充要求")
                val turn = rpc("turn/start", buildJsonObject {
                    put("threadId", threadId!!)
                    if (cwd.startsWith("/")) put("cwd", cwd)
                    model?.let { put("model", it) }
                    effort?.let { put("effort", it) }
                    put("input", buildJsonArray {
                        if (prompt.isNotBlank()) add(textInput(prompt))
                        images.forEach { path -> add(buildJsonObject { put("type", "localImage"); put("path", path) }) }
                    })
                })
                val turnNode = turn["turn"] as? JsonObject ?: error("远端未返回 turn")
                activeTurnId = turnNode.text("id") ?: activeTurnId ?: error("远端未返回 turn id")
                if (turnNode.text("status") in listOf("completed", "failed", "interrupted")) finish(turnNode)
                while (!completed) {
                    steerPending()
                    select<Unit> {
                        incoming.onReceiveCatching { value ->
                            val node = value.getOrNull() ?: disconnected("任务")
                            dispatch(node)
                        }
                        wake.onReceive { }
                    }
                }
                // Completion and steer acknowledgements can arrive in either order.
                if (inFlight != null) withTimeoutOrNull(2_000) {
                    while (inFlight != null) {
                        val node = incoming.receiveCatching().getOrNull() ?: break
                        dispatch(node)
                    }
                }
                // A lost acknowledgement is checked against this turn only, never full history.
                if (inFlight != null) {
                    try {
                        withTimeout(3_000) {
                            try {
                                val page = rpc("thread/items/list", buildJsonObject {
                                    put("threadId", threadId!!); put("turnId", activeTurnId!!)
                                    put("limit", 32); put("sortDirection", "desc")
                                })
                                (page["data"] as? JsonArray).orEmpty().forEach { entry ->
                                    val value = entry as? JsonObject ?: return@forEach
                                    (value["item"] as? JsonObject)?.let { item(it, true) }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                // CLI 0.166 exposes item paging in schema but has not enabled it.
                                // Fetch just the newest turn, never the complete thread transcript.
                                val page = rpc("thread/turns/list", buildJsonObject {
                                    put("threadId", threadId!!); put("limit", 1)
                                    put("sortDirection", "desc"); put("itemsView", "full")
                                })
                                (page["data"] as? JsonArray).orEmpty().forEach { value ->
                                    val turn = value as? JsonObject ?: return@forEach
                                    if (turn.text("id") == activeTurnId) (turn["items"] as? JsonArray).orEmpty()
                                        .forEach { (it as? JsonObject)?.let { value -> item(value, true) } }
                                }
                            }
                            inFlight?.takeIf { it.message.id in observedUserIds }?.let { applyConfirmed(it) }
                        }
                    } catch (e: CancellationException) { currentCoroutineContext().ensureActive() }
                    catch (_: Exception) { /* Remains explicitly unconfirmed. */ }
                }
            }
            outgoing.close()
            val result = withTimeoutOrNull(3_000) { transport.await() }
            if (result == null) transport.cancel()
            else if (!result.ok && result.stderr.isNotBlank()) errors += redactRemoteDiagnostic(result.stderr).take(2_000)
            result(result)
        } catch (e: RemoteAppServerUnsupported) {
            throw e
        } catch (e: TimeoutCancellationException) {
            // A protocol deadline is a visible failure; cancelling the parent remains a user stop.
            currentCoroutineContext().ensureActive()
            errors += "远端 app-server 响应超时，未确认的补充要求已保留：${e.message}"
            result(RemoteResult.timeout(timeout))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += e.message ?: e.javaClass.simpleName
            result(null)
        } finally {
            outgoing.close()
            watcher.cancel()
            transport.cancel()
        }
    }

    private fun notification(method: String, params: JsonObject) {
        val eventThread = params.text("threadId")
        if (threadId != null && eventThread != null && eventThread != threadId) return
        val eventTurn = params.text("turnId")
        if (activeTurnId != null && eventTurn != null && eventTurn != activeTurnId) return
        when (method) {
            "turn/started" -> {
                activeTurnId = (params["turn"] as? JsonObject)?.text("id") ?: activeTurnId
                progress("远端 Codex 开始处理，可继续补充要求")
            }
            "turn/completed" -> (params["turn"] as? JsonObject)?.let { finish(it) }
            "item/agentMessage/delta" -> {
                val id = params.text("itemId") ?: return
                messages[id] = messages[id].orEmpty() + params.text("delta").orEmpty()
            }
            "item/reasoning/textDelta", "item/reasoning/summaryTextDelta" -> if (includeReasoning) {
                val id = params.text("itemId").orEmpty() + method
                reasoning[id] = reasoning[id].orEmpty() + params.text("delta").orEmpty()
            }
            "item/started", "item/completed" -> {
                val item = params["item"] as? JsonObject ?: return
                item(item, method == "item/completed")
            }
            "thread/tokenUsage/updated" -> {
                val last = ((params["tokenUsage"] as? JsonObject)?.get("last") as? JsonObject) ?: return
                usage = (last.number("inputTokens") ?: 0) to (last.number("outputTokens") ?: 0)
            }
            "error" -> (params["error"] as? JsonObject)?.text("message")?.let { errors += it }
        }
    }

    private fun item(item: JsonObject, done: Boolean) {
        val id = item.text("id").orEmpty()
        when (item.text("type")) {
            "userMessage" -> item.text("clientId")?.let { observedUserIds += it }
            "agentMessage" -> {
                phases[id] = item.text("phase")
                if (done) item.text("text")?.let { messages[id] = it }
            }
            "reasoning" -> {
                if (!done) progress("远端思考中…")
                if (done && includeReasoning) {
                    val parts = listOf("summary", "content").flatMap { key ->
                        (item[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    }
                    if (parts.isNotEmpty()) {
                        reasoning.keys.filter { it.startsWith(id) }.toList().forEach { reasoning.remove(it) }
                        reasoning[id] = parts.joinToString("\n\n")
                    }
                }
            }
            "commandExecution" -> if (!done) progress("正在执行远端命令")
            "fileChange" -> progress(if (done) "远端文件已修改" else "正在修改远端文件")
            "mcpToolCall" -> if (!done) progress("正在调用远端 MCP：${item.text("tool").orEmpty().take(80)}")
            "webSearch" -> if (!done) progress("远端正在搜索网页")
            "contextCompaction" -> progress("远端正在压缩上下文")
        }
    }

    private fun finish(turn: JsonObject) {
        val id = turn.text("id")
        if (activeTurnId != null && id != null && id != activeTurnId) return
        completed = true
        turnStatus = turn.text("status").orEmpty()
        (turn["items"] as? JsonArray).orEmpty().forEach { (it as? JsonObject)?.let { node -> item(node, true) } }
        (turn["error"] as? JsonObject)?.text("message")?.let { errors += it }
        progress("正在汇总远端结果")
    }

    private fun result(transport: RemoteResult?): RemoteCodexEvents {
        val final = messages.entries.lastOrNull { phases[it.key] == "final_answer" }?.value
            ?: messages.values.lastOrNull().orEmpty()
        val ok = completed && turnStatus == "completed"
        val unconfirmed = inFlight?.let { setOf(it.message.id) }.orEmpty()
        if (unconfirmed.isNotEmpty()) {
            errors += "有补充要求的远端接收状态无法确认，已暂停自动重发；请同步核对原会话后继续"
        }
        return RemoteCodexEvents(ok, if (ok) 0 else transport?.exitCode?.takeIf { it != 0 } ?: 255,
            threadId, reasoning.values.filter { it.isNotBlank() }.distinct(), final, errors.distinct(), usage, unconfirmed)
    }

    private fun progress(text: String) { onProgress(RemoteCodexProgress(text, threadId)) }

    private fun command(): String = remoteCodexSessionCommand(cwd, includeReasoning, executable)
}

/** Test the actual production wrapper with a POSIX shell, without starting any model request. */
internal fun remoteCodexSessionCommand(cwd: String, includeReasoning: Boolean, executable: String): String {
        // An explicit fd preserves SSH stdin for the background process. The trap owns both children,
        // so cancellation/EOF does not leave an app-server or output filter running on the remote host.
        val filters = mutableListOf(
            "/\"method\"[[:space:]]*:[[:space:]]*\"item\\/completed\"/ && /\"type\"[[:space:]]*:[[:space:]]*\"commandExecution\"/ { next }",
        )
        if (!includeReasoning) filters +=
            "/\"method\"[[:space:]]*:[[:space:]]*\"item\\/completed\"/ && /\"type\"[[:space:]]*:[[:space:]]*\"reasoning\"/ { next }"
        val filter = filters.joinToString("\n") + "\n{ print; fflush() }"
        val script = """
            umask 077
            exec 3<&0
            PA_DIR=${'$'}(mktemp -d "${'$'}{TMPDIR:-/tmp}/pocketagent-rpc.XXXXXX") || exit 1
            PA_FIFO="${'$'}PA_DIR/events"
            PA_SERVER= PA_FILTER=
            trap 'trap - EXIT HUP INT TERM; [ -z "${'$'}PA_SERVER" ] || kill "${'$'}PA_SERVER" 2>/dev/null; [ -z "${'$'}PA_FILTER" ] || kill "${'$'}PA_FILTER" 2>/dev/null; rm -rf -- "${'$'}PA_DIR"' EXIT HUP INT TERM
            mkfifo "${'$'}PA_FIFO" || exit 1
            awk '$filter' < "${'$'}PA_FIFO" &
            PA_FILTER=${'$'}!
            "${'$'}CODEX_BIN" app-server <&3 > "${'$'}PA_FIFO" &
            PA_SERVER=${'$'}!
            wait "${'$'}PA_SERVER"
            PA_STATUS=${'$'}?
            PA_SERVER=
            wait "${'$'}PA_FILTER"
            PA_FILTER=
            exit "${'$'}PA_STATUS"
        """.trimIndent()
        return codexDiscovery(executable = executable) + "\n" + wrapWithWorkDir(script, cwd)
}

private fun redactRemoteDiagnostic(text: String): String = text
    .replace(Regex("(?i)\\b(?:sk|rk|pk)-[A-Za-z0-9_-]{12,}"), "[REDACTED]")
    .replace(Regex("(?i)((?:authorization|api[_-]?key|token|secret)\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;]+"), "$1[REDACTED]")

private fun textInput(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
