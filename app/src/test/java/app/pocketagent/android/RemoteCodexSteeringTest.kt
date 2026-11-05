package app.pocketagent.android

import app.pocketagent.android.agent.TurnSteering
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role
import app.pocketagent.android.remote.RemoteDuplexShell
import app.pocketagent.android.remote.RemoteInputShell
import app.pocketagent.android.remote.RemoteOperations
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.RemoteShell
import app.pocketagent.android.remote.SshHost
import app.pocketagent.android.tools.RemoteCodexTool
import app.pocketagent.android.tools.RemoteCodexProgress
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class RemoteCodexSteeringTest {
    private class DuplexShell(
        private val onTurnStarted: () -> Unit = {},
        private val onSteer: (JsonObject) -> Unit = {},
        private val rejectSteer: Boolean = false,
        private val completeBeforeAck: Boolean = false,
        private val wrongAckTurn: Boolean = false,
        private val approval: Boolean = false,
        private val hang: Boolean = false,
        private val missingAck: Boolean = false,
        private val verifiedByHistory: Boolean = false,
        private val unsupportedStartup: Boolean = false,
        private val itemsUnsupported: Boolean = false,
        private val unsupportedInitialize: Boolean = false,
        private val finishOnApproval: Boolean = true,
    ) : RemoteDuplexShell {
        val requests = mutableListOf<JsonObject>()
        var command = ""
        var ended = false
        var legacyRuns = 0
        override suspend fun exec(command: String, timeoutMs: Long) =
            if (unsupportedStartup) RemoteResult(2, stderr = "error: unrecognized subcommand 'app-server'") else RemoteResult(0)
        override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long): RemoteResult {
            legacyRuns++
            return RemoteResult(0, """{"type":"item.completed","item":{"type":"agent_message","text":"兼容执行完成"}}""")
        }
        override suspend fun execStreaming(
            command: String, input: ByteArray, timeoutMs: Long, onStdoutLine: (String) -> Unit,
        ): RemoteResult {
            val result = execWithInput(command, input, timeoutMs)
            result.stdout.lineSequence().forEach(onStdoutLine)
            return result
        }
        override suspend fun close() = Unit
        override suspend fun execDuplex(
            command: String,
            timeoutMs: Long,
            input: ReceiveChannel<ByteArray>,
            onStdoutLine: (String) -> Unit,
        ): RemoteResult {
            this.command = command
            var finished = false
            var latestSteeringId: String? = null
            fun emit(node: JsonObject) = onStdoutLine(node.toString())
            fun response(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
                put("id", request.getValue("id")); put("result", result)
            })
            fun notify(method: String, params: JsonObject) = emit(buildJsonObject {
                put("method", method); put("params", params)
            })
            fun finish() {
                finished = true
                notify("item/completed", buildJsonObject {
                    put("threadId", "thread-1"); put("turnId", "turn-1")
                    put("item", buildJsonObject {
                        put("id", "answer"); put("type", "agentMessage")
                        put("phase", "final_answer"); put("text", "已按补充要求完成")
                    })
                })
                notify("thread/tokenUsage/updated", buildJsonObject {
                    put("threadId", "thread-1"); put("turnId", "turn-1")
                    put("tokenUsage", buildJsonObject { put("last", buildJsonObject {
                        put("inputTokens", 12); put("outputTokens", 4)
                    }) })
                })
                notify("turn/completed", buildJsonObject {
                    put("threadId", "thread-1")
                    put("turn", buildJsonObject {
                        put("id", "turn-1"); put("status", "completed"); put("items", buildJsonArray {})
                    })
                })
            }
            try {
                for (bytes in input) {
                    val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8).trim()).jsonObject
                    requests += request
                    when (request.text("method")) {
                        "initialize" -> if (unsupportedInitialize) emit(buildJsonObject {
                            put("id", request.getValue("id"))
                            put("error", buildJsonObject { put("code", -32601); put("message", "method not found") })
                        }) else response(request, buildJsonObject { put("userAgent", "test") })
                        "thread/start", "thread/resume" -> response(request, buildJsonObject {
                            put("thread", buildJsonObject { put("id", "thread-1"); put("turns", buildJsonArray {}) })
                        })
                        "turn/start" -> {
                            if (hang) continue
                            notify("turn/started", buildJsonObject {
                                put("threadId", "thread-1")
                                put("turn", buildJsonObject { put("id", "turn-1"); put("status", "inProgress") })
                            })
                            response(request, buildJsonObject { put("turn", buildJsonObject {
                                put("id", "turn-1"); put("status", "inProgress"); put("items", buildJsonArray {})
                            }) })
                            if (approval) emit(buildJsonObject {
                                put("id", "approval-1"); put("method", "item/commandExecution/requestApproval")
                                put("params", buildJsonObject { put("threadId", "thread-1"); put("turnId", "turn-1") })
                            }) else onTurnStarted()
                        }
                        "turn/steer" -> {
                            if (finished) {
                                emit(buildJsonObject {
                                    put("id", request.getValue("id"))
                                    put("error", buildJsonObject { put("code", -32000); put("message", "turn ended") })
                                })
                                continue
                            }
                            onSteer(request)
                            latestSteeringId = request["params"]!!.jsonObject.text("clientUserMessageId")
                            if (completeBeforeAck) finish()
                            if (missingAck) {
                                // Intentionally withhold the RPC response; only scoped history can verify it.
                            } else if (rejectSteer) emit(buildJsonObject {
                                put("id", request.getValue("id"))
                                put("error", buildJsonObject { put("code", -32000); put("message", "turn ended") })
                            }) else response(request, buildJsonObject {
                                put("turnId", if (wrongAckTurn) "other-turn" else "turn-1")
                            })
                            if (!completeBeforeAck) finish()
                        }
                        "thread/items/list" -> if (itemsUnsupported) emit(buildJsonObject {
                            put("id", request.getValue("id"))
                            put("error", buildJsonObject { put("code", -32601); put("message", "not supported yet") })
                        }) else response(request, buildJsonObject { put("data", buildJsonArray {
                            if (verifiedByHistory) add(buildJsonObject { put("turnId", "turn-1"); put("item", buildJsonObject {
                                put("id", "native-user"); put("type", "userMessage"); put("clientId", latestSteeringId)
                            }) })
                        }) })
                        "thread/turns/list" -> response(request, buildJsonObject { put("data", buildJsonArray {
                            if (verifiedByHistory) add(buildJsonObject {
                                put("id", "turn-1"); put("status", "completed")
                                put("items", buildJsonArray { add(buildJsonObject {
                                    put("id", "native-user"); put("type", "userMessage"); put("clientId", latestSteeringId)
                                }) })
                            })
                        }) })
                        null -> if (request.text("id") == "approval-1" && finishOnApproval) finish()
                    }
                }
                return RemoteResult(0)
            } finally { ended = true }
        }
    }

    private fun operations(shell: RemoteShell) = object : RemoteOperations {
        override fun hosts(): List<SshHost> = emptyList()
        override fun host(id: String?): SshHost? = null
        override suspend fun shell(id: String?): RemoteShell = shell
        override suspend fun exec(command: String, timeoutMs: Long, hostId: String?): RemoteResult = RemoteResult(0)
    }
    private fun tool(shell: RemoteShell) = RemoteCodexTool(operations(shell), { "server" }, { "/tmp/work" })

    @Test
    fun `live steer uses current turn and acknowledges only the delivered message`() = runBlocking {
        val inbox = TurnSteering()
        val supplement = ChatMessage(id = "supplement-1", role = Role.USER, text = "请改用 Kotlin")
        val next = ChatMessage(id = "supplement-2", role = Role.USER, text = "再加说明")
        val shell = DuplexShell(onTurnStarted = { inbox.offer(supplement) }, onSteer = {
            // A new message arrives while the first RPC is in flight.
            inbox.offer(next)
        })
        val applied = mutableListOf<ChatMessage>()
        val parsed = tool(shell).run(
            host = "server", cwd = "/tmp/work", prompt = "原始要求", resume = null,
            model = "deepseek-v4-flash", effort = "max", steering = inbox,
            onSteeringApplied = { applied += it },
        )
        assertTrue(parsed.ok)
        assertEquals("thread-1", parsed.threadId)
        assertEquals("已按补充要求完成", parsed.finalText)
        assertEquals(12 to 4, parsed.usage)
        assertEquals(listOf(supplement), applied)
        assertEquals(listOf(next), inbox.peek())
        val steer = shell.requests.first { it.text("method") == "turn/steer" }["params"]!!.jsonObject
        assertEquals("turn-1", steer.text("expectedTurnId"))
        assertEquals("thread-1", steer.text("threadId"))
        assertEquals("supplement-1", steer.text("clientUserMessageId"))
        assertEquals("请改用 Kotlin", steer["input"]!!.jsonArray.single().jsonObject.text("text"))
        assertTrue(shell.ended)
        assertFalse(shell.command.contains("原始要求"))
    }

    @Test
    fun `completion before acknowledgement still records confirmed steering exactly once`() = runBlocking {
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "补充要求")
        val applied = mutableListOf<ChatMessage>()
        val shell = DuplexShell(onTurnStarted = { inbox.offer(supplement) }, completeBeforeAck = true)
        val parsed = tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox,
            onSteeringApplied = { applied += it })
        assertTrue(parsed.ok)
        assertEquals(listOf(supplement), applied)
        assertTrue(inbox.peek().isEmpty())
    }

    @Test
    fun `rejected steering and wrong turn acknowledgement preserve complete input`() = runBlocking {
        for (wrongTurn in listOf(false, true)) {
            val inbox = TurnSteering()
            val supplement = ChatMessage(role = Role.USER, text = "不能丢失的要求")
            val shell = DuplexShell(onTurnStarted = { inbox.offer(supplement) },
                rejectSteer = !wrongTurn, wrongAckTurn = wrongTurn)
            val applied = mutableListOf<ChatMessage>()
            tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox,
                onSteeringApplied = { applied += it })
            assertTrue(applied.isEmpty())
            assertEquals(listOf(supplement), inbox.peek())
            assertEquals(1, shell.requests.count { it.text("method") == "turn/steer" })
        }
    }

    @Test
    fun `resumes existing thread with no full history download or approval override`() = runBlocking {
        val inbox = TurnSteering()
        val shell = DuplexShell(onTurnStarted = { inbox.offer(ChatMessage(role = Role.USER, text = "继续")) })
        tool(shell).run("server", "/tmp/work", "原文", "thread-1", model = "deepseek-v4-flash",
            effort = "max", steering = inbox)
        val resume = shell.requests.single { it.text("method") == "thread/resume" }["params"]!!.jsonObject
        assertEquals("thread-1", resume.text("threadId"))
        assertEquals("true", resume.text("excludeTurns"))
        assertEquals("deepseek-v4-flash", resume.text("model"))
        val turn = shell.requests.single { it.text("method") == "turn/start" }["params"]!!.jsonObject
        assertEquals("max", turn.text("effort"))
        assertFalse(turn.containsKey("approvalPolicy"))
        assertFalse(turn.containsKey("sandboxPolicy"))
        val init = shell.requests.single { it.text("method") == "initialize" }["params"]!!.jsonObject
        val suppressed = init["capabilities"]!!.jsonObject["optOutNotificationMethods"]!!.jsonArray.toString()
        assertTrue(suppressed.contains("item/reasoning/textDelta"))
        assertTrue(suppressed.contains("item/commandExecution/outputDelta"))
    }

    @Test
    fun `remote approval is declined instead of silently granting execution`() = runBlocking {
        val shell = DuplexShell(approval = true)
        val parsed = tool(shell).run("server", "/tmp/work", "处理", null, steering = TurnSteering())
        val reply = shell.requests.single { it.text("id") == "approval-1" }
        assertEquals("decline", reply["result"]!!.jsonObject.text("decision"))
        assertTrue(parsed.ok)
    }

    @Test
    fun `timeout closes duplex transport without acknowledging queued work`() = runBlocking {
        val shell = DuplexShell(hang = true)
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "保留要求")
        inbox.offer(supplement)
        val outcome = tool(shell).run("server", "/tmp/work", "处理", null, timeout = 150, steering = inbox)
        assertFalse(outcome.ok)
        assertEquals(RemoteResult.TIMEOUT_EXIT_CODE, outcome.exitCode)
        assertTrue(outcome.errors.any { it.contains("超时") })
        assertTrue(shell.ended)
        assertEquals(listOf(supplement), inbox.peek())
    }

    @Test
    fun `older shell explicitly queues supplements for next turn`() = runBlocking {
        val shell = object : RemoteInputShell {
            override suspend fun exec(command: String, timeoutMs: Long) = RemoteResult(0)
            override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long) = RemoteResult(
                0, """{"type":"item.completed","item":{"type":"agent_message","text":"完成"}}""",
            )
            override suspend fun close() = Unit
        }
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "下一轮保留")
        inbox.offer(supplement)
        val progress = mutableListOf<RemoteCodexProgress>()
        tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox, onProgress = { progress += it })
        assertTrue(progress.any { it.text.contains("将在本轮结束后发送") })
        assertEquals(listOf(supplement), inbox.peek())
    }

    @Test
    fun `clearing queue after transmission still records the message the server accepted`() = runBlocking {
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "已发送的要求")
        val shell = DuplexShell(onTurnStarted = { inbox.offer(supplement) }, onSteer = { inbox.clear() })
        val applied = mutableListOf<ChatMessage>()
        tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox, onSteeringApplied = { applied += it })
        assertEquals(listOf(supplement), applied)
        assertTrue(inbox.peek().isEmpty())
    }

    @Test
    fun `lost acknowledgement is verified against only the active turn native user message`() = runBlocking {
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "待核实的要求")
        val shell = DuplexShell(onTurnStarted = { inbox.offer(supplement) }, missingAck = true, verifiedByHistory = true)
        val applied = mutableListOf<ChatMessage>()
        val result = tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox,
            onSteeringApplied = { applied += it })
        assertEquals(listOf(supplement), applied)
        assertTrue(result.unconfirmedSteeringIds.isEmpty())
        val params = shell.requests.single { it.text("method") == "thread/items/list" }["params"]!!.jsonObject
        assertEquals("turn-1", params.text("turnId"))
        assertEquals("32", params.text("limit"))
    }

    @Test
    fun `unverifiable delivery is explicitly marked to prevent automatic duplicate execution`() = runBlocking {
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "状态不明的要求")
        val shell = DuplexShell(onTurnStarted = { inbox.offer(supplement) }, missingAck = true)
        val result = tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox)
        assertEquals(setOf(supplement.id), result.unconfirmedSteeringIds)
        assertEquals(listOf(supplement), inbox.peek())
        assertTrue(result.errors.any { it.contains("暂停自动重发") })
    }

    @Test
    fun `remote command approval uses one off UI grant without changing policy`() = runBlocking {
        val shell = DuplexShell(approval = true)
        val approvals = mutableListOf<app.pocketagent.android.agent.ApprovalRequest>()
        val result = tool(shell).run("server", "/tmp/work", "处理", null, steering = TurnSteering(),
            onApproval = { approvals += it; true })
        assertTrue(result.ok)
        assertFalse(approvals.single().sessionAllowable)
        val response = shell.requests.single { it.text("id") == "approval-1" }
        assertEquals("accept", response["result"]!!.jsonObject.text("decision"))
    }

    @Test
    fun `CLI without app server falls back only before remote turn execution`() = runBlocking {
        for (probeAbsent in listOf(false, true)) {
            val shell = DuplexShell(unsupportedStartup = probeAbsent, unsupportedInitialize = !probeAbsent)
            val progress = mutableListOf<RemoteCodexProgress>()
            val result = tool(shell).run("server", "/tmp/work", "处理", null, steering = TurnSteering(),
                onProgress = { progress += it })
            assertTrue(result.ok)
            assertEquals("兼容执行完成", result.finalText)
            assertEquals(1, shell.legacyRuns)
            assertTrue(shell.requests.none { it.text("method") == "turn/start" })
            assertTrue(progress.any { it.text.contains("将在本轮结束后发送") })
        }
    }

    @Test
    fun `history verification falls back to one newest turn on current CLI`() = runBlocking {
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "需要核实")
        val shell = DuplexShell(onTurnStarted = { inbox.offer(supplement) }, missingAck = true,
            verifiedByHistory = true, itemsUnsupported = true)
        val applied = mutableListOf<ChatMessage>()
        val result = tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox,
            onSteeringApplied = { applied += it })
        assertEquals(listOf(supplement), applied)
        assertTrue(result.unconfirmedSteeringIds.isEmpty())
        val params = shell.requests.single { it.text("method") == "thread/turns/list" }["params"]!!.jsonObject
        assertEquals("1", params.text("limit"))
        assertEquals("full", params.text("itemsView"))
    }

    @Test
    fun `new requirement dismisses remote approval wait and steers before turn completion`() = runBlocking {
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "先不要运行命令，改方向")
        val shell = DuplexShell(approval = true, finishOnApproval = false)
        val applied = mutableListOf<ChatMessage>()
        var approvalCancelled = false
        val result = tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox,
            onSteeringApplied = { applied += it }, onApproval = {
                try { inbox.offer(supplement); awaitCancellation() }
                finally { approvalCancelled = true }
            })
        assertTrue(result.ok)
        assertTrue(approvalCancelled)
        assertEquals(listOf(supplement), applied)
        assertEquals("decline", shell.requests.single { it.text("id") == "approval-1" }["result"]!!.jsonObject.text("decision"))
        assertTrue(shell.requests.any { it.text("method") == "turn/steer" })
    }

    @Test
    fun `user cancellation promptly tears down remote transport`() = runBlocking {
        val inbox = TurnSteering()
        val started = CompletableDeferred<Unit>()
        val shell = DuplexShell(onTurnStarted = { started.complete(Unit) })
        val task = async { tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox) }
        started.await()
        task.cancelAndJoin()
        assertTrue(shell.ended)
    }

    @Test
    fun `image supplement is sent intact using native image input without nested SSH upload`() = runBlocking {
        val inbox = TurnSteering()
        val supplement = ChatMessage(role = Role.USER, text = "请看图片", images = listOf("data:image/png;base64,iVBORw0KGgo="))
        val shell = DuplexShell(onTurnStarted = { inbox.offer(supplement) })
        val applied = mutableListOf<ChatMessage>()
        tool(shell).run("server", "/tmp/work", "处理", null, steering = inbox, onSteeringApplied = { applied += it })
        assertEquals(listOf(supplement), applied)
        val input = shell.requests.first { it.text("method") == "turn/steer" }["params"]!!.jsonObject["input"]!!.jsonArray
        assertEquals("image", input[1].jsonObject.text("type"))
        assertEquals(supplement.images.single(), input[1].jsonObject.text("url"))
        assertEquals(0, shell.legacyRuns)
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
