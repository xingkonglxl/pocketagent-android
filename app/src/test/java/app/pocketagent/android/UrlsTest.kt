package app.pocketagent.android

import app.pocketagent.android.net.endpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlsTest {
    @Test
    fun `adds path to plain base`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            endpoint("https://api.deepseek.com", "chat/completions"),
        )
    }

    @Test
    fun `keeps version prefix and drops trailing slash`() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            endpoint("https://api.openai.com/v1/", "responses"),
        )
    }

    @Test
    fun `does not duplicate full endpoint`() {
        assertEquals(
            "https://x.dev/v1/chat/completions",
            endpoint("https://x.dev/v1/chat/completions", "chat/completions"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects blank base`() {
        endpoint("   ", "responses")
    }
}
