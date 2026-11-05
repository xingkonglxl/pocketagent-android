package app.pocketagent.android

import app.pocketagent.android.agent.AgentEvent
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.AutoApproveGate
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.AnthropicClient
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.ApiToolCall
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.endpoint
import app.pocketagent.android.tools.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AnthropicClientTest {

    private val server = MockWebServer()
    private val http = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(baseUrl: String, base: String = baseUrl) = Provider(
        name = "claude",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = base,
        model = "claude-sonnet-4-5",
    )

    /** 把一段 SSE 喂给客户端，返回解析出来的全部事件。 */
    private suspend fun eventsOf(sse: String): List<LlmEvent> {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )
        server.start()
        return AnthropicClient(http)
            .stream(
                LlmConfig(provider("${server.url("/")}".trimEnd('/')), "sk-test"),
                "",
                listOf(ApiMessage("user", "你好")),
                emptyList(),
            )
            .toList()
    }

    /** AgentRunner 采信最后一条 Finished，所以断言要看最后一条。 */
    private fun List<LlmEvent>.finalReason(): String? =
        filterIsInstance<LlmEvent.Finished>().lastOrNull()?.reason

    @Test
    fun `retry after server error creates a fresh call and streams successfully`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("temporary failure"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"retried successfully\"}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n",
        ))
        server.start()
        val events = AnthropicClient(http).stream(
            LlmConfig(provider(server.url("/").toString()), "test-key"),
            "", listOf(ApiMessage("user", "test")), emptyList(),
        ).toList()
        assertEquals("retried successfully", events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `endpoint adds v1 once`() {
        assertEquals("https://x.com/v1/messages", endpoint("https://x.com", "v1/messages", alreadyVersioned = true))
        assertEquals("https://x.com/v1/messages", endpoint("https://x.com/v1", "v1/messages", alreadyVersioned = true))
        assertEquals("https://x.com/v1/messages", endpoint("https://x.com/v1/", "v1/messages", alreadyVersioned = true))
    }

    @Test
    fun `streams text tool use and usage`() = runTest {
        val sse = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":42,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"fs_read","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\"}"}}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"我看看"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"thinking_delta","thinking":"先读文件"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )
        server.start()

        val events = AnthropicClient(http)
            .stream(
                LlmConfig(provider("${server.url("/")}".trimEnd('/')), "sk-test"),
                "你是助手",
                listOf(ApiMessage("user", "读一下 a.txt")),
                emptyList(),
            )
            .toList()

        val texts = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        val reasoning = events.filterIsInstance<LlmEvent.ReasoningDelta>().joinToString("") { it.text }
        val started = events.filterIsInstance<LlmEvent.ToolCallStarted>().single()
        val args = events.filterIsInstance<LlmEvent.ToolCallArguments>().joinToString("") { it.delta }
        val usage = events.filterIsInstance<LlmEvent.Usage>()

        assertEquals("我看看", texts)
        assertEquals("先读文件", reasoning)
        assertEquals("toolu_1", started.id)
        assertEquals("fs_read", started.name)
        assertEquals("{\"path\":\"a.txt\"}", args)
        assertTrue(usage.any { it.input == 42 })
        assertTrue(usage.any { it.output == 12 })

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"system\":\"你是助手\""))
        assertTrue(body.contains("\"max_tokens\":8192"))
    }

    @Test
    fun `assistant tool call and tool result use content blocks`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
        )
        server.start()

        AnthropicClient(http)
            .stream(
                LlmConfig(provider("${server.url("/")}".trimEnd('/')), "sk-test"),
                "",
                listOf(
                    ApiMessage("user", "看看文件"),
                    ApiMessage(
                        role = "assistant",
                        content = "我来读",
                        toolCalls = listOf(ApiToolCall("toolu_9", "fs_read", "{\"path\":\"a.txt\"}")),
                    ),
                    ApiMessage("tool", content = "hello", toolCallId = "toolu_9"),
                ),
                emptyList(),
            )
            .toList()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"type\":\"tool_use\""))
        assertTrue(body.contains("\"id\":\"toolu_9\""))
        assertTrue(body.contains("\"input\":{\"path\":\"a.txt\"}"))
        assertTrue(body.contains("\"type\":\"tool_result\""))
        assertTrue(body.contains("\"tool_use_id\":\"toolu_9\""))
    }

    @Test
    fun `error event becomes LlmException`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}\n\n"),
        )
        server.start()

        val error = runCatching {
            AnthropicClient(http)
                .stream(LlmConfig(provider("${server.url("/")}".trimEnd('/')), "sk-test"), "", listOf(ApiMessage("user", "hi")), emptyList())
                .toList()
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("overloaded") == true)
    }

    @Test
    fun `probe reports http error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"invalid x-api-key\"}}"),
        )
        server.start()

        val msg = AnthropicClient(http)
            .probe(LlmConfig(provider("${server.url("/")}".trimEnd('/')), "bad"))

        assertTrue(msg?.contains("401") == true)
    }

    @Test
    fun `thinking blocks keep their signature and are replayed before tool calls`() = runTest {
        val first = """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先看日志"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-abc"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"fs_read","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.log\"}"}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent()
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(first),
        )
        server.start()

        val base = provider("${server.url("/")}".trimEnd('/')).copy(reasoningEffort = "high")
        val events = AnthropicClient(http)
            .stream(LlmConfig(base, "sk-test"), "", listOf(ApiMessage("user", "查日志")), emptyList())
            .toList()

        val reasoningItem = events.filterIsInstance<LlmEvent.ReasoningItem>().last().json
        assertTrue(reasoningItem.contains("\"thinking\":\"先看日志\""))
        assertTrue(reasoningItem.contains("\"signature\":\"sig-abc\""))
        server.takeRequest() // 消费第一轮请求，下面的 takeRequest 才是第二轮

        // 第二轮：把上一轮的 thinking 块连同工具结果一起回传
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
        )
        AnthropicClient(http).stream(
            LlmConfig(base, "sk-test"),
            "",
            listOf(
                ApiMessage("user", "查日志"),
                ApiMessage(
                    role = "assistant",
                    content = "我读一下",
                    toolCalls = listOf(ApiToolCall("toolu_2", "fs_read", "{\"path\":\"a.log\"}")),
                    reasoningItem = reasoningItem,
                ),
                ApiMessage("tool", content = "日志内容", toolCallId = "toolu_2"),
            ),
            emptyList(),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
        // 第二请求是最新的一条，取 messages[1].content
        val messages = body.jsonObject["messages"]!!.jsonArray
        val assistantBlocks = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", assistantBlocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig-abc", assistantBlocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("text", assistantBlocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", assistantBlocks[2].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `thinking budget always stays below max tokens and drops temperature`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
        )
        server.start()

        val p = provider("${server.url("/")}".trimEnd('/')).copy(
            reasoningEffort = "max",
            maxOutputTokens = 8192,
            temperature = 0.3,
        )
        AnthropicClient(http)
            .stream(LlmConfig(p, "sk-test"), "", listOf(ApiMessage("user", "分析一下")), emptyList())
            .toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val maxTokens = body["max_tokens"]!!.jsonPrimitive.int
        val budget = body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int
        assertTrue("budget $budget 必须小于 max_tokens $maxTokens", budget < maxTokens)
        assertEquals(16_384, budget)
        assertEquals(null, body["temperature"])
    }

    @Test
    fun `max_tokens from message_delta is not overwritten by message_stop`() = runTest {
        val events = eventsOf(
            """
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"第一点：先看日志"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":4096}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent(),
        )

        assertEquals("max_tokens", events.finalReason())
        // 截断信号不能被后面的 message_stop 抹平成正常结束
        assertFalse(events.filterIsInstance<LlmEvent.Finished>().any { it.reason == "end_turn" })
        assertEquals(
            "第一点：先看日志",
            events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text },
        )
        assertTrue(events.filterIsInstance<LlmEvent.Usage>().any { it.output == 4096 })
    }

    @Test
    fun `end_turn is reported verbatim`() = runTest {
        val events = eventsOf(
            """
            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent(),
        )

        assertEquals("end_turn", events.finalReason())
        assertEquals(1, events.filterIsInstance<LlmEvent.Finished>().size)
    }

    @Test
    fun `a bare message_stop still means end_turn`() = runTest {
        // 没有 message_delta 的极简响应保持原来的默认值
        val events = eventsOf("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")

        assertEquals("end_turn", events.finalReason())
    }

    @Test
    fun `tool_use from message_delta survives the trailing message_stop`() = runTest {
        val events = eventsOf(
            """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"fs_read","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\"}"}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent(),
        )

        assertEquals("tool_use", events.finalReason())
        assertEquals("toolu_1", events.filterIsInstance<LlmEvent.ToolCallStarted>().single().id)
    }

    @Test
    fun `a max_tokens cut off is continued by the runner instead of ending the answer`() = runTest {
        server.start()
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(
                """
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"补充两点："}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":8192}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(
                """
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"\n1. 第一点\n2. 第二点"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":16}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent(),
            ),
        )

        val client = AnthropicClient(http)
        val runner = AgentRunner(ToolRegistry(emptyList()), AutoApproveGate) { client }
        val events = mutableListOf<AgentEvent>()

        val result = runner.run(
            provider("${server.url("/")}".trimEnd('/')),
            "sk-test",
            listOf(ApiMessage("user", "解释一下")),
        ) { events += it }

        // 两段拼在一起，说明 AgentRunner 认出了 max_tokens 并自动续写
        assertEquals("补充两点：\n1. 第一点\n2. 第二点", result.text)
        assertEquals(2, result.steps)
        assertTrue(events.filterIsInstance<AgentEvent.Notice>().any { it.text.contains("自动接着写") })

        // 续写请求里要带上已经写出的部分和截断说明
        server.takeRequest()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains("补充两点："))
        assertTrue(second.contains("截断"))
    }
}
