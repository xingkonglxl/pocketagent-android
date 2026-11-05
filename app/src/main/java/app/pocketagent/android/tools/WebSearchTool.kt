package app.pocketagent.android.tools

import app.pocketagent.android.net.Http
import app.pocketagent.android.net.ToolSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 公网搜索。模型只知道“要查最新信息”时，不应凭记忆猜股价/新闻。
 * 固定访问公开搜索页，不接受任意 URL，避免变成 SSRF 跳板。
 */
class WebSearchTool(
    private val http: OkHttpClient = Http.client.newBuilder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : Tool {
    override val spec = ToolSpec(
        name = "web_search",
        description = "搜索公开网页，返回标题、摘要和链接；用于新闻、股价、版本、当天状态等实时信息。",
        parametersJson = ToolSchemas.WEB_SEARCH,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runCatching {
        val query = arg(argumentsJson, "query")?.trim().orEmpty()
        if (query.isBlank()) return ToolOutcome.Error("缺少 query")

        val encoded = URLEncoder.encode(query, "UTF-8")
        // 中转站不支持服务端 web_search，只能手机自己抓。中文查询先走百度：
        // 实测同样的问题走 Bing 会返回日文字典、笔顺、壁纸这类无关结果。
        val bing = "Bing" to { searchBing(encoded) }
        val baidu = "百度" to { searchBaidu(encoded) }
        val duck = "DuckDuckGo" to { searchDuckDuckGo(encoded) }
        val mojeek = "Mojeek" to { searchMojeek(encoded) }
        val chinese = query.any { it.code > 0x2E80 }
        val engines: List<Pair<String, () -> List<WebSearchResult>>> =
            if (chinese) listOf(baidu, bing, duck, mojeek) else listOf(bing, baidu, duck, mojeek)
        var bestEngine = ""
        var bestResults: List<WebSearchResult> = emptyList()
        var bestRelevant = -1
        for ((engine, search) in engines) {
            val results = runCatching { search() }.getOrDefault(emptyList())
            // 只看条数会被"8 条无关结果"骗过去；优先选与查询真正相关的引擎。
            val relevant = relevantCount(query, results)
            if (relevant > bestRelevant || (relevant == bestRelevant && results.size > bestResults.size)) {
                bestEngine = engine
                bestResults = results
                bestRelevant = relevant
            }
            if (relevant >= MIN_RESULTS) break
        }

        // 引擎返回“字词条/壁纸/字典”这类无关页时，宁可明确报错，也不要把垃圾喂给模型：
        // 模型看到不相关摘要会自行放弃取证，或把无关标题当证据，两者都会拉低回答质量。
        val relevantResults = bestResults.filter { relevanceScore(query, it) >= 2 }
        if (bestResults.isNotEmpty() && relevantResults.isEmpty()) {
            return ToolOutcome.Error(
                "web_search 拿到 ${bestResults.size} 条结果，但没有一条与「$query」相关（引擎=$bestEngine）。" +
                    "请改用 http_get 打开已知权威网址（行情、公告、文档等），或换更精确的关键词再搜一次。",
            )
        }
        if (bestResults.isEmpty()) {
            return ToolOutcome.Error(
                "四个搜索引擎都没解析到结果（可能被拦截或没网）。" +
                    "可以改用 http_get 直接打开已知网址，或把问题拆成不需要实时数据的形式。",
            )
        }
        ToolOutcome.Ok(
            formatWebSearchResults(
                query = query,
                engine = bestEngine,
                results = rankResults(query, relevantResults),
                partial = bestRelevant < MIN_RESULTS,
            ),
        )
    }.getOrElse { ToolOutcome.Error(it.message ?: "搜索失败") }

    private fun searchBing(query: String): List<WebSearchResult> {
        // cn.bing.com 实测 301，且响应体为空；www.bing.com + 桌面 UA 才会返回 b_algo。
        val html = fetch(searchRequest("https://www.bing.com/search?q=$query").build())
            ?: return emptyList()
        return parseBingResults(html)
    }

    private fun searchBaidu(query: String): List<WebSearchResult> {
        val url = "https://www.baidu.com/s?wd=$query&ie=utf-8&rn=10"
        val directHtml = fetch(
            searchRequest(url)
                .header("Referer", "https://www.baidu.com/")
                .build(),
        )
        val direct = directHtml?.let(::parseBaiduResults).orEmpty()
        if (direct.size >= MIN_RESULTS) return direct

        // 百度在部分网络会先把无 Cookie 的搜索请求 302 到验证码页。先访问首页取
        // Set-Cookie，再用同一 UA/Referer 重试；不自动执行验证码，失败就继续回退。
        val cookie = fetchBaiduCookieHeader() ?: return direct
        val retried = fetch(
            searchRequest(url)
                .header("Referer", "https://www.baidu.com/")
                .header("Cookie", cookie)
                .build(),
        )?.let(::parseBaiduResults).orEmpty()
        return if (retried.size > direct.size) retried else direct
    }

    private fun searchDuckDuckGo(query: String): List<WebSearchResult> {
        val html = fetch(
            searchRequest("https://lite.duckduckgo.com/lite/?q=$query").build(),
        ) ?: return emptyList()
        return parseDuckDuckGoResults(html)
    }

    private fun searchMojeek(query: String): List<WebSearchResult> {
        val html = fetch(
            searchRequest("https://www.mojeek.com/search?q=$query").build(),
        ) ?: return emptyList()
        return parseMojeekResults(html)
    }

    private fun searchRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", DESKTOP_USER_AGENT)
        .header(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        )
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")

    private fun fetchBaiduCookieHeader(): String? = runCatching {
        http.newCall(searchRequest("https://www.baidu.com/").build()).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                response.headers.values("Set-Cookie")
                    .mapNotNull { header ->
                        header.substringBefore(';').trim()
                            .takeIf { it.contains('=') && !it.startsWith("=") }
                    }
                    .distinct()
                    .joinToString("; ")
                    .takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    private fun fetch(request: Request): String? = runCatching {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            if (body.contentLength() > MAX_PAGE_BYTES) return@use null
            val bytes = body.bytes()
            if (bytes.size > MAX_PAGE_BYTES) return@use null
            decodeSearchHtml(bytes, response.header("Content-Type"))
        }
    }.getOrNull()
}

internal data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String,
)

/** 查询分词：ASCII 单词 + 中文二元组，用来判断结果是否真的跟查询相关。 */
internal fun queryTokens(query: String): List<String> {
    val ascii = Regex("[A-Za-z0-9_]{2,}").findAll(query).map { it.value.lowercase() }.toList()
    val cjk = query.filter { it.code > 0x2E80 }
    val bigrams = if (cjk.length >= 2) cjk.windowed(2).toList() else emptyList()
    return (ascii + bigrams).distinct()
}

/** 交易所、公告、主流财经媒体：同名结果优先。 */
private val AUTHORITY_DOMAINS = listOf(
    "sse.com.cn", "szse.cn", "bse.cn", "cninfo.com.cn", "csrc.gov.cn", "gov.cn",
    "eastmoney.com", "sina.com.cn", "cnstock.com", "stcn.com", "yicai.com", "10jqka.com.cn",
)

/** 词典/笔顺/壁纸这类结果即使命中关键词也不该排在前面。 */
private val SPAM_WORDS = listOf("字典", "笔顺", "壁纸", "小说", "彩票", "游戏下载", "破解")

internal fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").lowercase()

internal fun relevanceScore(query: String, result: WebSearchResult): Int {
    val tokens = queryTokens(query)
    if (tokens.isEmpty()) return 0
    val hay = (result.title + " " + result.snippet).lowercase()
    var score = tokens.count { hay.contains(it) }
    val host = hostOf(result.url)
    if (AUTHORITY_DOMAINS.any { host == it || host.endsWith(".$it") }) score += 3
    if (SPAM_WORDS.any { result.title.contains(it) }) score -= 4
    return score
}

internal fun relevantCount(query: String, results: List<WebSearchResult>): Int =
    results.count { relevanceScore(query, it) >= 2 }

/** 按相关性排序，并限制同域名最多 2 条，避免一个站刷满整个结果。 */
internal fun rankResults(
    query: String,
    results: List<WebSearchResult>,
    limit: Int = 8,
): List<WebSearchResult> {
    val scored = results.map { it to relevanceScore(query, it) }
        // 词典/壁纸这类负分结果在有其它候选时直接丢掉；全是垃圾时保留原顺序
        .filter { it.second >= 0 }
        .ifEmpty { results.map { it to 0 } }
        .sortedByDescending { it.second }
    val perHost = mutableMapOf<String, Int>()
    val out = mutableListOf<WebSearchResult>()
    for ((result, _) in scored) {
        val host = hostOf(result.url)
        val used = perHost[host] ?: 0
        if (used >= 2) continue
        perHost[host] = used + 1
        out += result
        if (out.size >= limit) break
    }
    return out
}

internal fun parseBingResults(html: String): List<WebSearchResult> {
    val starts = BING_BLOCK_START.findAll(html).map { it.range.first }.toList()
    if (starts.isEmpty()) return emptyList()

    return starts.mapIndexedNotNull { index, start ->
        val end = starts.getOrElse(index + 1) { html.length }
        val block = html.substring(start, end)
        val titleMatch = BING_TITLE.find(block) ?: return@mapIndexedNotNull null
        val title = cleanHtml(titleMatch.groupValues[2])
        val href = attribute(titleMatch.groupValues[1], "href")
            ?.let { normalizeUrl(it, "https://www.bing.com/") }
            ?.let(::unwrapBingUrl)
            ?: return@mapIndexedNotNull null
        if (title.isBlank() || !isHttpUrl(href)) return@mapIndexedNotNull null

        val snippet = BING_SNIPPET_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtml)?.takeIf { it.isNotBlank() }
        }.orEmpty()
        WebSearchResult(title, snippet, href)
    }.distinctBy { it.url }.take(10)
}

internal fun parseBaiduResults(html: String): List<WebSearchResult> {
    // 新版页面把 class 拆成多行，并把标题藏在 <!--s-text--> 注释标记之间；
    // 旧版则使用 h3.t。先提取所有标题节点，再用下一标题作为摘要搜索的边界。
    val hits = BAIDU_H3.findAll(html).mapNotNull { match ->
        val classAttr = attribute(match.groupValues[1], "class") ?: return@mapNotNull null
        if (!isBaiduTitleClass(classAttr)) return@mapNotNull null

        val anchor = HTML_ANCHOR.find(match.groupValues[2]) ?: return@mapNotNull null
        val href = attribute(anchor.groupValues[1], "href")
            ?.let { normalizeUrl(it, "https://www.baidu.com/") }
            ?: return@mapNotNull null
        val title = cleanHtml(anchor.groupValues[2])
        if (title.isBlank() || !isHttpUrl(href)) return@mapNotNull null
        BaiduHit(
            title = title,
            url = href,
            start = match.range.first,
            end = match.range.last + 1,
        )
    }.toList()

    return hits.mapIndexedNotNull { index, hit ->
        val nextStart = hits.getOrNull(index + 1)?.start ?: html.length
        val tail = html.substring(hit.end, nextStart)
        val snippet = BAIDU_SNIPPET_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(tail)?.groupValues?.getOrNull(1)?.let(::cleanHtml)?.takeIf { it.isNotBlank() }
        }.orEmpty()
        WebSearchResult(hit.title, snippet, hit.url)
    }.distinctBy { it.url }.take(10)
}

internal fun parseDuckDuckGoResults(html: String): List<WebSearchResult> {
    // Lite 页面使用 class='result-link'（单引号），且 href 在 class 前面。
    val links = DDG_LINK.findAll(html).toList()
    val snippets = DDG_SNIPPET.findAll(html)
        .map { cleanHtml(it.groupValues[1]) }
        .toList()
    return links.mapIndexedNotNull { index, link ->
        val title = cleanHtml(link.groupValues[2])
        val href = attribute(link.groupValues[1], "href")
            ?.let { normalizeUrl(it, "https://lite.duckduckgo.com/") }
            ?.let(::unwrapDuckDuckGoUrl)
            ?: return@mapIndexedNotNull null
        if (title.isBlank() || !isHttpUrl(href)) return@mapIndexedNotNull null
        WebSearchResult(title, snippets.getOrElse(index) { "" }, href)
    }.distinctBy { it.url }.take(10)
}

internal fun parseMojeekResults(html: String): List<WebSearchResult> {
    val starts = MOJEEK_BLOCK_START.findAll(html).map { it.range.first }.toList()
    if (starts.isEmpty()) return emptyList()

    return starts.mapIndexedNotNull { index, start ->
        val end = starts.getOrElse(index + 1) { html.length }
        val block = html.substring(start, end)
        val titleMatch = MOJEEK_TITLE.find(block) ?: return@mapIndexedNotNull null
        val title = cleanHtml(titleMatch.groupValues[2])
        val href = attribute(titleMatch.groupValues[1], "href")
            ?.let { normalizeUrl(it, "https://www.mojeek.com/") }
            ?: return@mapIndexedNotNull null
        if (title.isBlank() || !isHttpUrl(href)) return@mapIndexedNotNull null
        val snippet = MOJEEK_SNIPPET_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(block)?.groupValues?.getOrNull(1)?.let(::cleanHtml)?.takeIf { it.isNotBlank() }
        }.orEmpty()
        WebSearchResult(title, snippet, href)
    }.distinctBy { it.url }.take(10)
}

internal fun formatWebSearchResults(
    query: String,
    engine: String,
    results: List<WebSearchResult>,
    partial: Boolean = false,
): String = buildString {
    append("搜索：").append(query).append("（来源：").append(engine).append("）")
    if (partial) append("\n注意：本轮只解析到少量结果，可能被搜索引擎限流。")
    for (result in results.take(8)) {
        append("\n\n• ").append(result.title)
        append("\n").append(result.snippet.ifBlank { "（该结果未提供摘要）" })
        append("\n").append(result.url)
        if (isBaiduRedirectUrl(result.url)) {
            append("\n（百度跳转链接：打开后由百度跳到目标网页）")
        }
    }
}

internal fun decodeSearchHtml(bytes: ByteArray, contentType: String? = null): String {
    var offset = 0
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()
    ) {
        offset = 3
    }
    val payload = if (offset == 0) bytes else bytes.copyOfRange(offset, bytes.size)
    val head = String(payload, 0, minOf(payload.size, 4_096), Charsets.ISO_8859_1)
    val declared = contentType?.let { CHARSET_PARAM.find(it)?.groupValues?.getOrNull(1) }
        ?: CHARSET_PARAM.find(head)?.groupValues?.getOrNull(1)

    charsetFor(declared)?.let { return String(payload, it) }
    return runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
    }.getOrElse {
        // 百度旧页面可能只声明 GBK；GB18030 是其安全超集。
        String(payload, Charset.forName("GB18030"))
    }
}

private data class BaiduHit(
    val title: String,
    val url: String,
    val start: Int,
    val end: Int,
)

private const val MIN_RESULTS = 3
private const val MAX_PAGE_BYTES = 6L * 1024 * 1024
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private val BING_BLOCK_START = Regex(
    """(?is)<li\b[^>]*\bclass\s*=\s*["'][^"']*\bb_algo\b[^"']*["'][^>]*>""",
)
private val BING_TITLE = Regex(
    """(?is)<h2\b[^>]*>\s*<a\b([^>]*)>(.*?)</a>""",
)
private val BING_SNIPPET_PATTERNS = listOf(
    Regex(
        """(?is)<p\b[^>]*\bclass\s*=\s*["'][^"']*\bb_lineclamp\w*\b[^"']*["'][^>]*>(.*?)</p>""",
    ),
    Regex("""(?is)<p\b[^>]*>(.*?)</p>"""),
)

private val BAIDU_H3 = Regex("""(?is)<h3\b([^>]*)>(.*?)</h3>""")
private val HTML_ANCHOR = Regex("""(?is)<a\b([^>]*)>(.*?)</a>""")
private val BAIDU_SNIPPET_PATTERNS = listOf(
    Regex(
        """(?is)<(?:span|div)\b[^>]*\bclass\s*=\s*["'][^"']*\bsummary-text[_-][^"']*["'][^>]*>(.*?)</(?:span|div)>""",
    ),
    Regex(
        """(?is)<div\b[^>]*\bclass\s*=\s*["'][^"']*\bc-abstract\b[^"']*["'][^>]*>(.*?)</div>""",
    ),
    Regex(
        """(?is)<span\b[^>]*\bclass\s*=\s*["'][^"']*\bcontent-right\b[^"']*["'][^>]*>(.*?)</span>""",
    ),
)

private val DDG_LINK = Regex(
    """(?is)<a\b([^>]*\bclass\s*=\s*["'][^"']*\bresult-link\b[^"']*["'][^>]*)>(.*?)</a>""",
)
private val DDG_SNIPPET = Regex(
    """(?is)<td\b[^>]*\bclass\s*=\s*["'][^"']*\bresult-snippet\b[^"']*["'][^>]*>(.*?)</td>""",
)

private val MOJEEK_BLOCK_START = Regex("""(?is)<li\b[^>]*>\s*<h2\b""")
private val MOJEEK_TITLE = Regex(
    """(?is)<h2\b[^>]*>\s*<a\b([^>]*)>(.*?)</a>""",
)
private val MOJEEK_SNIPPET_PATTERNS = listOf(
    Regex(
        """(?is)<p\b[^>]*\bclass\s*=\s*["'][^"']*\bs\b[^"']*["'][^>]*>(.*?)</p>""",
    ),
    Regex(
        """(?is)<p\b[^>]*\bclass\s*=\s*["'][^"']*\bsnippet\b[^"']*["'][^>]*>(.*?)</p>""",
    ),
)

private val CHARSET_PARAM = Regex("""(?i)charset\s*=\s*["']?\s*([^"'\s;/>]+)""")
private val DECIMAL_ENTITY = Regex("""&#(\d{1,7});""")
private val HEX_ENTITY = Regex("""&#[xX]([0-9a-fA-F]{1,6});""")
private val WHITESPACE = Regex("""\s+""")

private fun isBaiduTitleClass(classAttr: String): Boolean {
    val classes = classAttr.split(WHITESPACE)
    return "cosc-title" in classes || "c-title" in classes || "t" in classes
}

private fun attribute(attributes: String, name: String): String? {
    val pattern = Regex(
        """(?is)(?:^|\s)${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
    )
    val match = pattern.find(attributes) ?: return null
    return (1..3).firstNotNullOfOrNull { index ->
        match.groupValues.getOrNull(index)?.takeIf { it.isNotEmpty() }
    }
}

private fun normalizeUrl(raw: String, baseUrl: String): String? {
    var url = decodeHtmlEntities(raw).trim()
    if (url.startsWith("//")) url = "https:$url"
    if (url.startsWith("/")) url = baseUrl.trimEnd('/') + url
    return url.takeIf { isHttpUrl(it) }
}

private fun isHttpUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

private fun unwrapBingUrl(url: String): String {
    if (!url.contains("bing.com/ck/a", ignoreCase = true)) return url
    val encoded = Regex("""[?&]u=a1([^&]+)""").find(url)?.groupValues?.getOrNull(1) ?: return url
    val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
    return runCatching {
        String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
    }.getOrNull()?.takeIf(::isHttpUrl) ?: url
}

private fun unwrapDuckDuckGoUrl(url: String): String {
    if (!url.contains("duckduckgo.com/l/", ignoreCase = true)) return url
    val encoded = Regex("""[?&]uddg=([^&]+)""").find(url)?.groupValues?.getOrNull(1) ?: return url
    return runCatching {
        URLDecoder.decode(encoded, "UTF-8")
    }.getOrNull()?.takeIf(::isHttpUrl) ?: url
}

private fun isBaiduRedirectUrl(url: String): Boolean =
    url.contains("baidu.com/link?", ignoreCase = true)

private fun charsetFor(name: String?): Charset? {
    val normalized = name?.trim()?.trim('"', '\'')?.lowercase() ?: return null
    val actual = when (normalized) {
        "gb2312", "gbk", "x-gbk" -> "GB18030"
        "utf8" -> "UTF-8"
        else -> normalized
    }
    return runCatching { Charset.forName(actual) }.getOrNull()
}

private fun cleanHtml(value: String): String = decodeHtmlEntities(
    value
        .replace(Regex("""(?is)<!--.*?-->"""), " ")
        .replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
        .replace(Regex("""(?is)<[^>]+>"""), " "),
).replace(WHITESPACE, " ").trim()

private fun decodeHtmlEntities(value: String): String {
    var text = value
        .replace("&nbsp;", " ")
        .replace("&ensp;", " ")
        .replace("&emsp;", " ")
        .replace("&thinsp;", " ")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&middot;", "·")
        .replace("&hellip;", "…")
        .replace("&amp;", "&")
    text = DECIMAL_ENTITY.replace(text) { match ->
        val codePoint = match.groupValues[1].toIntOrNull()
        entityCharacter(codePoint) ?: match.value
    }
    return HEX_ENTITY.replace(text) { match ->
        val codePoint = match.groupValues[1].toIntOrNull(16)
        entityCharacter(codePoint) ?: match.value
    }
}

private fun entityCharacter(codePoint: Int?): String? {
    if (codePoint == null || !Character.isValidCodePoint(codePoint)) return null
    return String(Character.toChars(codePoint))
}
