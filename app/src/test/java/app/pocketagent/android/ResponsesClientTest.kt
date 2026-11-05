package app.pocketagent.android

import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.ApiToolCall
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.ResponsesClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesClientTest {

    private val server = MockWebServer()
    private val http = OkHttpClient()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `parses codex style events`() = runTest {
        val sse = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_9","name":"fs_write"}}

            data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"path\":\"b.txt\","}

            data: {"type":"response.function_call_arguments.delta","output_index":0,"delta":"\"content\":\"hi\"}"}

            data: {"type":"response.output_text.delta","delta":"好的"}

            data: {"type":"response.completed","response":{"usage":{"input_tokens":7,"output_tokens":3}}}

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )

        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "gpt-5-codex",
        )
        val events = ResponsesClient(http)
            .stream(LlmConfig(provider, "sk-test"), "sys", listOf(ApiMessage("user", "写文件")), emptyList())
            .toList()

        val started = events.filterIsInstance<LlmEvent.ToolCallStarted>().first()
        assertEquals("call_9", started.id)
        assertEquals("fs_write", started.name)

        val args = events.filterIsInstance<LlmEvent.ToolCallArguments>().joinToString("") { it.delta }
        assertEquals("{\"path\":\"b.txt\",\"content\":\"hi\"}", args)

        assertEquals("好的", events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text })
        val usage = events.filterIsInstance<LlmEvent.Usage>().first()
        assertEquals(7, usage.input)
        assertEquals(3, usage.output)

        val request = server.takeRequest()
        assertEquals("/v1/responses", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("sys", body["instructions"]?.jsonPrimitive?.content)
        assertEquals(false, body["store"]?.jsonPrimitive?.content?.toBoolean())
        // 桌面 Codex 不发 text.verbosity；默认不能自己塞 low，否则回答会明显变短
        assertEquals(null, body["text"])
    }

    @Test
    fun `raises on response failed event`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""data: {"type":"response.failed","response":{"error":{"message":"model not found"}}}"""),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/").toString(),
            model = "nope",
        )
        val result = runCatching {
            ResponsesClient(http)
                .stream(LlmConfig(provider, "k"), "", listOf(ApiMessage("user", "hi")), emptyList())
                .toList()
        }
        assertTrue(result.exceptionOrNull()?.message?.contains("model not found") == true)
    }

    @Test
    fun `captures complete reasoning item from done event`() = runTest {
        val sse = """
            data: {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1","status":"in_progress","content":[],"summary":[],"encrypted_content":null}}

            data: {"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","status":"completed","content":[{"type":"reasoning_text","text":"先确认公司是否上市"}],"summary":[],"encrypted_content":"opaque"}}

            data: {"type":"response.output_text.delta","delta":"宇树科技尚未上市。"}

            data: {"type":"response.completed","response":{"usage":{"input_tokens":9,"output_tokens":4}}}

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
        )
        val events = ResponsesClient(http)
            .stream(LlmConfig(provider, "k"), "sys", listOf(ApiMessage("user", "宇树科技股票")), emptyList())
            .toList()

        val items = events.filterIsInstance<LlmEvent.ReasoningItem>()
        assertEquals(2, items.size)
        val full = Json.parseToJsonElement(items.last().json).jsonObject
        assertEquals("rs_1", full["id"]?.jsonPrimitive?.content)
        assertEquals("opaque", full["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning item is sent before function call and tool output`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type":"response.completed","response":{"usage":{}}}"""),
        )
        val reasoning = """
            {"type":"reasoning","id":"rs_1","status":"completed","content":[],
             "summary":[],"encrypted_content":"opaque"}
        """.trimIndent()
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
        )
        ResponsesClient(http).stream(
            LlmConfig(provider, "k"),
            "sys",
            listOf(
                ApiMessage("user", "查一下"),
                ApiMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(ApiToolCall("call_1", "web_search", """{"query":"宇树科技"}""")),
                    reasoningItem = reasoning,
                ),
                ApiMessage("tool", "搜索结果", toolCallId = "call_1"),
            ),
            emptyList(),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val input = body["input"]!!.jsonArray
        assertEquals("user", input[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("reasoning", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("function_call", input[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool turn with text keeps reasoning first`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type":"response.completed","response":{"usage":{}}}"""),
        )
        val reasoning = """{"type":"reasoning","id":"rs_2","status":"completed","content":[],"summary":[],"encrypted_content":"op"}"""
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
        )
        ResponsesClient(http).stream(
            LlmConfig(provider, "k"),
            "sys",
            listOf(
                ApiMessage("user", "查一下"),
                ApiMessage(
                    role = "assistant",
                    content = "我先查一下",
                    toolCalls = listOf(ApiToolCall("call_9", "web_search", """{"query":"x"}""")),
                    reasoningItem = reasoning,
                ),
                ApiMessage("tool", "结果", toolCallId = "call_9"),
            ),
            emptyList(),
        ).toList()

        val input = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["input"]!!.jsonArray
        assertEquals("reasoning", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("assistant", input[2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("function_call", input[3].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `matches desktop request controls for tools and reasoning context`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type":"response.completed","response":{"usage":{}}}"""),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
            serviceTier = "priority",
        )
        ResponsesClient(http).stream(
            LlmConfig(provider, "k", cacheKey = "conv-1"),
            "sys",
            listOf(ApiMessage("user", "查一下")),
            listOf(
                app.pocketagent.android.net.ToolSpec(
                    "web_search",
                    "搜索",
                    """{"type":"object"}""",
                ),
            ),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        // 桌面 Codex 实测就是 True，App 以前写死 false，多工具任务会多花好几轮
        assertEquals(true, body["parallel_tool_calls"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, body["store"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("priority", body["service_tier"]?.jsonPrimitive?.content)
        assertEquals("conv-1", body["prompt_cache_key"]?.jsonPrimitive?.content)
        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("max", reasoning["effort"]?.jsonPrimitive?.content)
        assertEquals("all_turns", reasoning["context"]?.jsonPrimitive?.content)
        // 不带 summary，服务端不吐思考摘要，App 的思考区就是空的
        assertEquals("auto", reasoning["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `completed with status incomplete is reported as a length stop`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"补充两点：\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"," +
                        "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}," +
                        "\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}}\n\n",
                ),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
        )
        val events = ResponsesClient(http)
            .stream(LlmConfig(provider, "k"), "sys", listOf(ApiMessage("user", "解释一下")), emptyList())
            .toList()

        assertEquals("补充两点：", events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text })
        // 以前这里被判成 stop，用户只看到半句话、没有任何提示
        assertEquals("length", events.filterIsInstance<LlmEvent.Finished>().last().reason)
    }

    @Test
    fun `verbosity is only sent when the user picks one`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type":"response.completed","response":{"usage":{}}}"""),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/").toString(),
            model = "gpt-6-astra",
            verbosity = "medium",
        )
        ResponsesClient(http).stream(
            LlmConfig(provider, "k"),
            "sys",
            listOf(ApiMessage("user", "分析一下")),
            emptyList(),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("medium", body["text"]?.jsonObject?.get("verbosity")?.jsonPrimitive?.content)
    }

    @Test
    fun `legacy tool turn without reasoning is downgraded to text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type":"response.completed","response":{"usage":{}}}"""),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
        )
        ResponsesClient(http).stream(
            LlmConfig(provider, "k"),
            "sys",
            listOf(
                ApiMessage("user", "查一下"),
                ApiMessage(
                    role = "assistant",
                    content = "我查到结论是 A",
                    toolCalls = listOf(ApiToolCall("call_old", "web_search", """{"query":"x"}""")),
                    reasoningItem = null,
                ),
                ApiMessage("tool", "旧结果", toolCallId = "call_old"),
                ApiMessage("user", "继续"),
            ),
            emptyList(),
        ).toList()

        val input = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["input"]!!.jsonArray
        assertTrue(input.none { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" })
        assertTrue(input.none { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
        assertTrue(input.any { it.jsonObject["content"]?.jsonPrimitive?.content == "我查到结论是 A" })
    }

    @Test
    fun `demoted legacy tool output is compacted`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type":"response.completed","response":{"usage":{}}}"""),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
        )
        ResponsesClient(http).stream(
            LlmConfig(provider, "k"),
            "sys",
            listOf(
                ApiMessage("user", "看文件"),
                ApiMessage(
                    role = "assistant",
                    content = "读一下",
                    toolCalls = listOf(ApiToolCall("c1", "fs_read", """{"path":"big.log"}""")),
                    reasoningItem = null,
                ),
                ApiMessage("tool", "Z".repeat(10_000), toolCallId = "c1"),
                ApiMessage("user", "继续"),
            ),
            emptyList(),
        ).toList()

        val input = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["input"]!!.jsonArray
        val longest = input.maxOf { (it.jsonObject["content"]?.jsonPrimitive?.content ?: "").length }
        // 10000 字的旧工具输出必须被压到千字以内，否则 1641 条对话会把请求撑爆
        assertTrue("旧记录没有压缩：最长 $longest 字符", longest < 1_200)
    }

    @Test
    fun `retries a 500 with a fresh call`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"temporary"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type":"response.completed","response":{"usage":{}}}"""),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
        )
        val events = ResponsesClient(http)
            .stream(LlmConfig(provider, "k"), "sys", listOf(ApiMessage("user", "hi")), emptyList())
            .toList()

        assertTrue(events.any { it is LlmEvent.Finished })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `reports a stream that closes before response completed`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""data: {"type":"response.output_text.delta","delta":"只答了一半"}"""),
        )
        val provider = Provider(
            name = "codex",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
        )

        val result = runCatching {
            ResponsesClient(http)
                .stream(LlmConfig(provider, "k"), "sys", listOf(ApiMessage("user", "hi")), emptyList())
                .toList()
        }

        assertTrue(result.exceptionOrNull()?.message?.contains("response.completed") == true)
    }
}
