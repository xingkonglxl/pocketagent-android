package app.pocketagent.android

import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.ChatCompletionsClient
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.ResponsesClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseJsonNullRegressionTest {

    private val server = MockWebServer()
    private val http = OkHttpClient()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `chat completions ignores relay nulls and parses later content`() = runTest {
        val sse = """
            data: {"id":"chunk_0","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"role":"assistant","content":null,"reasoning_content":null,"tool_calls":null},"logprobs":null,"finish_reason":null}],"usage":null}

            data: {"id":"chunk_1","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":null,"usage":null}

            data: {"id":"chunk_2","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":null,"logprobs":null,"finish_reason":null}],"usage":null}

            data: {"id":"chunk_3","object":"chat.completion.chunk","model":"deepseek-v4-flash","choices":[{"index":0,"delta":{"content":"后续回复","reasoning_content":null,"tool_calls":null},"logprobs":null,"finish_reason":null}],"usage":null}

            data: [DONE]

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )

        val provider = Provider(
            name = "relay",
            kind = ProviderKind.CHAT_COMPLETIONS,
            baseUrl = server.url("/v1").toString(),
            model = "deepseek-v4-flash",
        )
        val events = ChatCompletionsClient(http)
            .stream(LlmConfig(provider, "sk-test"), "", listOf(ApiMessage("user", "你好")), emptyList())
            .toList()

        assertEquals(
            "后续回复",
            events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text },
        )
        assertTrue(events.none { it is LlmEvent.Usage })
    }

    @Test
    fun `responses ignores relay nulls and parses later content`() = runTest {
        val sse = """
            data: {"type":"response.output_text.delta","delta":null,"content":null,"usage":null}

            data: {"type":"response.output_item.added","output_index":null,"item":null,"delta":null}

            data: {"type":"response.function_call_arguments.delta","output_index":null,"delta":null}

            data: {"type":"response.output_text.delta","delta":"后续回复","content":null,"usage":null}

            data: {"type":"response.completed","response":null,"usage":null}

            data: [DONE]

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )

        val provider = Provider(
            name = "relay",
            kind = ProviderKind.RESPONSES,
            baseUrl = server.url("/v1").toString(),
            model = "gpt-5-codex",
        )
        val events = ResponsesClient(http)
            .stream(LlmConfig(provider, "sk-test"), "", listOf(ApiMessage("user", "你好")), emptyList())
            .toList()

        assertEquals(
            "后续回复",
            events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text },
        )
        val usage = events.filterIsInstance<LlmEvent.Usage>().single()
        assertEquals(null, usage.input)
        assertEquals(null, usage.output)
        assertEquals("stop", events.filterIsInstance<LlmEvent.Finished>().single().reason)
    }
}
