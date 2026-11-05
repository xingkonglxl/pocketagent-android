package app.pocketagent.android

import app.pocketagent.android.ui.home.plainMarkdownPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomePreviewTest {

    @Test
    fun `strips bold markers from the conversation preview`() {
        val preview = plainMarkdownPreview(
            "不行，这两个条件互相矛盾。**625 只有在跌破 631.5 之后才会出现，" +
                "而那时“不破 631.5”这个前提已经失效了。** 先理清逻辑。",
        )

        assertEquals(
            "不行，这两个条件互相矛盾。625 只有在跌破 631.5 之后才会出现，" +
                "而那时“不破 631.5”这个前提已经失效了。 先理清逻辑。",
            preview,
        )
        assertFalse(preview.contains("**"))
    }

    @Test
    fun `keeps link labels and removes noisy source urls`() {
        val preview = plainMarkdownPreview(
            "## 来源\n- [公告](https://example.com/notice?x=1)\n- `688836` https://quote.example.com/sh688836",
        )

        assertEquals("来源 • 公告 • 688836", preview)
    }

    @Test
    fun `collapses markdown whitespace and empty bodies`() {
        assertEquals("第一段 第二段", plainMarkdownPreview("  ## 第一段\n\n> 第二段  "))
        assertEquals("（空消息）", plainMarkdownPreview("```\nonly code\n```"))
    }
}
