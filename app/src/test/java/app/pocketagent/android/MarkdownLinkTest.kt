package app.pocketagent.android

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import app.pocketagent.android.ui.components.inlineAnnotated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinkTest {

    @Test
    fun `markdown links and bare urls become clickable`() {
        val text = inlineAnnotated(
            "来源：[公告](https://example.com/notice?x=1)，行情 https://quote.example.com/sh688836。",
            codeBg = Color.Gray,
            linkColor = Color.Blue,
        )

        assertEquals(
            "来源：公告，行情 https://quote.example.com/sh688836。",
            text.text,
        )
        val urls = text.getLinkAnnotations(0, text.length)
            .mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
        assertEquals(
            listOf("https://example.com/notice?x=1", "https://quote.example.com/sh688836"),
            urls,
        )
    }

    @Test
    fun `urls in code and non-http links are not clickable`() {
        val text = inlineAnnotated(
            "`https://example.com/code` [坏链接](javascript:alert(1))",
            codeBg = Color.Gray,
            linkColor = Color.Blue,
        )

        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        assertTrue(text.text.contains("https://example.com/code"))
        assertTrue(text.text.contains("坏链接"))
    }
}
