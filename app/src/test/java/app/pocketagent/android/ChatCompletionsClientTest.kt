package app.pocketagent.android

import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.ChatCompletionsClient
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.encodeDeepSeekReasoning
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChatCompletionsClientTest {

    private val server = MockWebServer()
    private val http = OkHttpClient()

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(baseUrl: String) = Provider(
        name = "test",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = baseUrl,
        model = "deepseek-chat",
    )

    @Test
    fun `parses text tool calls and usage`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"role":"assistant","content":"我看看"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"fs_read","arguments":"{\"path\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a.txt\"}"}}]}}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: {"usage":{"prompt_tokens":10,"completion_tokens":5},"choices":[]}

            data: [DONE]

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )

        val client = ChatCompletionsClient(http)
        val base = server.url("/v1").toString()
        val events = client
            .stream(LlmConfig(provider(base), "sk-test"), "系统提示", listOf(ApiMessage("user", "读文件")), emptyList())
            .toList()

        val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("我看看", text)

        val started = events.filterIsInstance<LlmEvent.ToolCallStarted>()
        assertEquals(1, started.size)
        assertEquals("call_1", started.first().id)
        assertEquals("fs_read", started.first().name)

        val args = events.filterIsInstance<LlmEvent.ToolCallArguments>().joinToString("") { it.delta }
        assertEquals("{\"path\":\"a.txt\"}", args)

        val usage = events.filterIsInstance<LlmEvent.Usage>().first()
        assertEquals(10, usage.input)
        assertEquals(5, usage.output)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("系统提示"))
    }

    @Test
    fun `maps http errors to friendly message`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Authentication Fails"}}"""),
        )
        val client = ChatCompletionsClient(http)
        val result = runCatching {
            client.stream(LlmConfig(provider(server.url("/").toString()), "bad"), "", listOf(ApiMessage("user", "hi")), emptyList())
                .toList()
        }
        val error = result.exceptionOrNull()
        assertTrue(error is app.pocketagent.android.net.LlmException)
        assertEquals(401, (error as app.pocketagent.android.net.LlmException).statusCode)
        assertTrue(error.message!!.contains("Key 无效"))
        assertTrue(error.message!!.contains("Authentication Fails"))
    }

    @Test
    fun `survives sse split across network reads`() = runTest {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse)
                .throttleBody(7, 20, TimeUnit.MILLISECONDS),
        )
        val client = ChatCompletionsClient(http)
        val events = client
            .stream(
                LlmConfig(provider(server.url("/").toString()), "k"),
                "",
                listOf(ApiMessage("user", "hi")),
                emptyList(),
            )
            .toList()
        assertEquals("你好", events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun `retries a 500 with a fresh call`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"temporary"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        val client = ChatCompletionsClient(http)
        client.stream(
            LlmConfig(provider(server.url("/v1").toString()), "k"),
            "sys",
            listOf(ApiMessage("user", "hi")),
            emptyList(),
        ).toList()

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `does not leak responses reasoning items into chat completions`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        val reasoning = """{"type":"reasoning","id":"rs_1","content":[],"encrypted_content":"opaque"}"""
        ChatCompletionsClient(http).stream(
            LlmConfig(provider(server.url("/v1").toString()), "k"),
            "sys",
            listOf(
                ApiMessage(
                    role = "assistant",
                    content = "答",
                    reasoningItem = reasoning,
                ),
            ),
            emptyList(),
        ).toList()

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("encrypted_content"))
        assertFalse(body.contains("\"reasoningItem\""))
        assertTrue(body.contains("\"content\":\"答\""))
    }

    @Test
    fun `deepseek reasoning content is replayed on assistant history`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        ChatCompletionsClient(http).stream(
            LlmConfig(provider(server.url("/v1").toString()), "k"),
            "sys",
            listOf(
                ApiMessage(role = "user", content = "问题"),
                ApiMessage(
                    role = "assistant",
                    content = "答",
                    reasoningItem = encodeDeepSeekReasoning("思考内容"),
                ),
            ),
            emptyList(),
        ).toList()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_content\":\"思考内容\""))
    }
}
