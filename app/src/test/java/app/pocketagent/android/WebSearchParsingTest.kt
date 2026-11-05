package app.pocketagent.android

import app.pocketagent.android.tools.WebSearchResult
import app.pocketagent.android.tools.decodeSearchHtml
import app.pocketagent.android.tools.formatWebSearchResults
import app.pocketagent.android.tools.parseBaiduResults
import app.pocketagent.android.tools.parseBingResults
import app.pocketagent.android.tools.parseDuckDuckGoResults
import app.pocketagent.android.tools.parseMojeekResults
import app.pocketagent.android.tools.rankResults
import app.pocketagent.android.tools.relevantCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class WebSearchParsingTest {

    @Test
    fun `ranking drops spam and prefers authority when query is chinese`() {
        val results = listOf(
            WebSearchResult("宇 的笔顺 字典", "汉字笔顺查询", "https://dict.example.com/yu"),
            WebSearchResult("宇树科技 688836 行情", "宇树科技最新股价与市值", "https://quote.eastmoney.com/688836"),
            WebSearchResult("宇树科技 科创板 IPO 进展", "上交所受理与上市委审议", "https://www.sse.com.cn/ipo"),
            WebSearchResult("宇树科技 行情二", "重复域名第二条", "https://quote.eastmoney.com/dup"),
            WebSearchResult("宇树科技 行情三", "同域名第三条应被截断", "https://quote.eastmoney.com/dup2"),
        )

        val ranked = rankResults("宇树科技 股票", results)

        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.none { it.title.contains("字典") })
        assertEquals(2, ranked.count { it.url.contains("eastmoney.com") })
        assertTrue(relevantCount("宇树科技 股票", results) >= 3)
    }

    @Test
    fun `bing parses real result blocks and unwraps redirect`() {
        val html = """
            <ol id="b_results">
              <li class="b_algo" data-id iid=SERP.5126>
                <h2><a target="_blank" target="_blank" href="https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1aHR0cHM6Ly9rb3RsaW5sYW5nLm9yZy9kb2NzL2Nvcm91dGluZXMtb3ZlcnZpZXcuaHRtbA&amp;ntb=1" h="ID=SERP,5126.2">Coroutines | <strong>Kotlin</strong> Documentation</a></h2>
                <div class="b_caption"><p class="b_lineclamp2" data-rslinkclamp-iid="">Coroutines in Kotlin are built on suspending functions.</p></div>
              </li>
              <li class="b_algo" data-id iid=SERP.5142>
                <h2><a target="_blank" href="https://kotlinlang.org/">Kotlin Programming Language</a></h2>
                <div class="b_caption"><p class="b_lineclamp4">Kotlin is a concise multiplatform language.</p></div>
              </li>
              <li class="b_algo" data-id iid=SERP.5157>
                <h2><a href="https://developer.android.com/kotlin/coroutines">Kotlin coroutines on Android</a></h2>
                <div class="b_caption"><p class="b_lineclamp2">Use coroutines for asynchronous work on Android.</p></div>
              </li>
            </ol>
        """.trimIndent()

        val results = parseBingResults(html)

        assertEquals(3, results.size)
        assertEquals("Coroutines | Kotlin Documentation", results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", results[0].url)
        assertTrue(results[0].snippet.contains("suspending functions"))
        assertEquals("https://developer.android.com/kotlin/coroutines", results[2].url)
    }

    @Test
    fun `baidu parses multiline title classes and marks redirect links`() {
        val html = """
            <div class="result c-container xpath-log new-pmd">
              <h3 class="cosc-title cos-link
                       title_4QsBx
                       click-area-exp-title
                     " data-module="title"><a class="cosc-title-a cos-link cosc-title-md " href="https://www.baidu.com/link?url=token-one" target="_blank"><span class="cosc-title-slot"><span class="tts-b-hl"><!--s-text-->Coroutines | <em>Kotlin</em> Documentation<!--/s-text--></span></span></a></h3>
              <div data-module="abstract"><span class="cos-space-mr-3xs prefix-time_650Xx">2天前</span><span class="    summary-text_15QGa"><em>Kotlin</em> provides several ways for <em>coroutines</em> to communicate.</span></div>
            </div>
            <div class="result c-container xpath-log new-pmd">
              <h3 class="cosc-title cos-link title_4QsBx"><a href="https://www.baidu.com/link?url=token-two"><span class="cosc-title-slot"><span><!--s-text-->Android 开发进阶:<em>Kotlin</em> <em>Coroutines</em> 使用详解 - 简书<!--/s-text--></span></span></a></h3>
              <span class="summary-text_15QGa">从协程的基本概念、启动方式和线程切换讲起。</span>
            </div>
            <div class="result c-container xpath-log new-pmd">
              <h3 class="cosc-title cos-link title_4QsBx"><a href="https://www.baidu.com/link?url=token-three"><span class="cosc-title-slot"><span><!--s-text-->Kotlin Coroutines Tutorial - Baeldung<!--/s-text--></span></span></a></h3>
              <div class="c-abstract">Learn how to use coroutines in Kotlin with practical examples.</div>
            </div>
        """.trimIndent()

        val results = parseBaiduResults(html)
        val output = formatWebSearchResults("Kotlin coroutines", "百度", results)

        assertEquals(3, results.size)
        assertEquals("Coroutines | Kotlin Documentation", results[0].title)
        assertTrue(results[0].snippet.contains("communicate"))
        assertTrue(results[1].snippet.contains("协程"))
        assertEquals("https://www.baidu.com/link?url=token-one", results[0].url)
        assertTrue(output.contains("来源：百度"))
        assertTrue(output.contains("百度跳转链接"))
    }

    @Test
    fun `duckduckgo lite parses single quoted links and decodes target`() {
        val html = """
            <tr>
              <td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines%2Doverview.html&amp;rut=abc" class='result-link'>Coroutines | Kotlin Documentation</a></td>
            </tr>
            <tr><td class='result-snippet'><b>Coroutines</b> in <b>Kotlin</b> allow code to pause and resume.</td></tr>
            <tr>
              <td><a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2Fkotlin%2Fcoroutines&amp;rut=def" rel="nofollow" class='result-link'>Kotlin coroutines on Android</a></td>
            </tr>
            <tr><td class='result-snippet'>Use coroutines for asynchronous work on Android.</td></tr>
        """.trimIndent()

        val results = parseDuckDuckGoResults(html)

        assertEquals(2, results.size)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", results[0].url)
        assertTrue(results[0].snippet.contains("pause and resume"))
        assertEquals("https://developer.android.com/kotlin/coroutines", results[1].url)
    }

    @Test
    fun `mojeek parser reads h2 link and snippet`() {
        val html = """
            <ul class="results-standard">
              <li>
                <h2><a class="ob" href="https://kotlinlang.org/docs/coroutines-overview.html">Coroutines | Kotlin Documentation</a></h2>
                <p class="s">Coroutines in Kotlin are built on suspending functions.</p>
              </li>
              <li>
                <h2><a href="https://developer.android.com/kotlin/coroutines">Kotlin coroutines on Android</a></h2>
                <p class="s">Use coroutines for asynchronous work.</p>
              </li>
            </ul>
        """.trimIndent()

        val results = parseMojeekResults(html)

        assertEquals(2, results.size)
        assertEquals("Coroutines | Kotlin Documentation", results[0].title)
        assertTrue(results[1].snippet.contains("asynchronous"))
    }

    @Test
    fun `gbk page is decoded from meta charset`() {
        val source = "<html><head><meta charset=\"gbk\"></head><body>百度搜索</body></html>"
        val decoded = decodeSearchHtml(source.toByteArray(Charset.forName("GBK")))

        assertTrue(decoded.contains("百度搜索"))
    }

    @Test
    fun `formatting includes source and fallback for empty snippet`() {
        val output = formatWebSearchResults(
            query = "test",
            engine = "Bing",
            results = listOf(WebSearchResult("Title", "", "https://example.com/")),
            partial = true,
        )

        assertTrue(output.contains("来源：Bing"))
        assertTrue(output.contains("未提供摘要"))
        assertTrue(output.contains("可能被搜索引擎限流"))
        assertFalse(output.contains("百度跳转链接"))
    }
}
