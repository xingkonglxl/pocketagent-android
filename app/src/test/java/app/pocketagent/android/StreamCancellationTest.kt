package app.pocketagent.android

import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class StreamCancellationTest {
    @Test fun `all three protocols promptly cancel a blocked SSE socket`() = runBlocking {
        for (kind in ProviderKind.entries) {
            val server = MockWebServer()
            server.start()
            val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {}\n\n").setHeader("Content-Length", "100000"))
                val provider = Provider(name = "test", kind = kind,
                    baseUrl = server.url("/").toString(), model = "test")
                val client: LlmClient = when (kind) {
                    ProviderKind.CHAT_COMPLETIONS -> ChatCompletionsClient(http)
                    ProviderKind.RESPONSES -> ResponsesClient(http)
                    ProviderKind.ANTHROPIC -> AnthropicClient(http)
                }
                val request = launch(Dispatchers.IO) {
                    client.stream(LlmConfig(provider, "test-key"), "", listOf(ApiMessage("user", "test")), emptyList()).collect()
                }
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val start = System.nanoTime()
                withTimeout(3_000) { request.cancelAndJoin() }
                assertTrue("$kind must cancel within 3 seconds", (System.nanoTime() - start) / 1_000_000 < 3_000)
            } finally {
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdownNow()
                server.shutdown()
            }
        }
    }
}
