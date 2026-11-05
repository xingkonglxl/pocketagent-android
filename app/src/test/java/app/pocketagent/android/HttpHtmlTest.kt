package app.pocketagent.android

import app.pocketagent.android.tools.htmlToText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * http_get 抓网页时先抽正文：脚本/样式/标签不能进模型上下文，
 * 否则一次抓取就塞进几万 token 的噪声，回答会被带偏。
 */
class HttpHtmlTest {

    @Test
    fun `drops scripts styles and tags but keeps visible text`() {
        val html = """
            <!doctype html>
            <html><head><title>行情</title><style>.a{color:red}</style></head>
            <body>
              <script>function track(){var x=1;}</script>
              <h1>宇树科技</h1>
              <p>收盘价 647.05 元</p>
              <div>跌幅 <b>-2.32%</b></div>
              <!-- 注释不该出现 -->
            </body></html>
        """.trimIndent()
        val text = htmlToText(html)
        assertTrue(text, text.contains("宇树科技"))
        assertTrue(text, text.contains("647.05"))
        assertTrue(text, text.contains("-2.32%"))
        assertFalse("脚本不能保留：$text", text.contains("function track"))
        assertFalse("样式不能保留：$text", text.contains("color:red"))
        assertFalse("标签不能保留：$text", text.contains("<div>"))
        assertFalse("注释不能保留：$text", text.contains("注释不该出现"))
    }

    @Test
    fun `decodes common entities and collapses blank lines`() {
        val text = htmlToText("<p>A&amp;B</p><p>&lt;tag&gt;</p><p>x&nbsp;y</p>\n\n\n\n<p>end</p>")
        assertTrue(text, text.contains("A&B"))
        assertTrue(text, text.contains("<tag>"))
        assertTrue(text, text.contains("x y"))
        assertFalse("不应出现连续三个换行：$text", text.contains("\n\n\n"))
    }
}
