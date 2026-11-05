package app.pocketagent.android.tools

import app.pocketagent.android.net.Http
import app.pocketagent.android.net.PublicOnlyDns
import app.pocketagent.android.net.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private const val MAX_BODY_BYTES = 32 * 1024
/** 正文抽取后仍然太长时的上限：超出的只是噪声，模型读多了反而答偏。 */
private const val MAX_TEXT_CHARS = 12_000
private const val HTTP_TIMEOUT_SECONDS = 20L

class HttpGetTool(
    /** 测试里要访问 MockWebServer(127.0.0.1)，生产默认拦内网 */
    private val allowLocal: Boolean = false,
    http: OkHttpClient = Http.client.newBuilder()
        .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) : Tool {
    // A separate pool prevents reusing a connection opened by an API/MCP client to a LAN host.
    private val http = http.newBuilder()
        .connectionPool(ConnectionPool())
        .proxy(Proxy.NO_PROXY)
        .dns(if (allowLocal) http.dns else PublicOnlyDns(http.dns))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override val spec = ToolSpec(
        name = "http_get",
        description = "抓取公网 http/https 网页或 API 的文本内容，返回状态码和前 32KB。" +
            "20 秒超时；不执行 JavaScript、不处理登录；内容被截断时会明确提示，需要长文时优先找支持分页的 API。",
        parametersJson = ToolSchemas.HTTP_GET,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = withContext(Dispatchers.IO) {
        runToolCatching {
            val url = arg(argumentsJson, "url")?.trim().orEmpty()
            if (url.isBlank()) {
                return@runToolCatching ToolOutcome.Error("缺少必填参数 url。示例：{\"url\":\"https://example.com\"}")
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@runToolCatching ToolOutcome.Error("只支持 http/https URL，收到：$url")
            }

            // Android 默认禁止明文 HTTP，而不少行情/文档站同时支持 HTTPS。
            // 给定的地址是 http:// 时先自动升级成 https:// 再试，失败才回退原地址；
            // 这样既不用放宽平台安全策略，也能让更多数据源正常访问。
            val candidates = if (url.startsWith("http://")) {
                listOf(url.replaceFirst("http://", "https://"), url)
            } else {
                listOf(url)
            }
            var lastError: Exception? = null
            for (candidate in candidates) {
                val result = try {
                    fetchPublic(candidate).use { res ->
                        val (raw, truncated) = readLimited(res.body?.source())
                        val contentType = res.header("Content-Type").orEmpty()
                        // 网页整页 HTML（脚本/样式/属性）直接喂给模型既费 token 又容易答偏，
                        // 桌面 Codex 读网页也是先抽正文。JSON/纯文本保持原样。
                        val body = if (looksLikeHtml(contentType, raw)) htmlToText(raw) else raw
                        val bodyTruncated = truncated || body.length > MAX_TEXT_CHARS
                        val shown = if (body.length > MAX_TEXT_CHARS) body.take(MAX_TEXT_CHARS) else body
                        val suffix = if (bodyTruncated) {
                            "\n…（已截断：只显示前 $MAX_BODY_BYTES 字节；" +
                                "请改用支持分页/范围查询的 API，或先缩小抓取范围）"
                        } else {
                            ""
                        }
                        Triple(res.code, shown + suffix, res.request.url.toString())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
                val text = "HTTP ${result.first}\nURL: ${result.third}\n${result.second}"
                return@runToolCatching if (result.first in 200..299) ToolOutcome.Ok(text) else ToolOutcome.Error(text)
            }

            val hint = if (url.startsWith("http://")) {
                "（该地址只有明文 http://，Android 默认禁止；请换 https:// 或改用支持 HTTPS 的数据源）"
            } else {
                ""
            }
            ToolOutcome.Error("抓取失败：${httpErrorMessage(lastError ?: RuntimeException("无响应"))}$hint")
        }.getOrElse {
            ToolOutcome.Error("抓取失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun fetchPublic(raw: String): Response {
        var url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("URL 不合法")
        repeat(9) { hop ->
            validateTarget(url)
            val request = Request.Builder().url(url).header("User-Agent", "PocketAgent/0.1").build()
            val response = http.newCall(request).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location") ?: return response
            val next = url.resolve(location)
            response.close()
            require(next != null) { "重定向地址不合法" }
            require(hop < 8) { "网页重定向次数过多" }
            require(!url.isHttps || next.isHttps) { "拒绝 HTTPS 降级重定向" }
            url = next
        }
        error("网页重定向次数过多")
    }

    private fun validateTarget(url: HttpUrl) {
        require(url.username.isEmpty() && url.password.isEmpty()) { "网页 URL 不能包含用户名或密码" }
        // OkHttp bypasses DNS for literal IPs, so also validate before every request.
        // The DNS wrapper independently checks the addresses used at connect time (anti-rebinding).
        if (!allowLocal) http.dns.lookup(url.host)
    }

    private fun readLimited(source: okio.BufferedSource?): Pair<String, Boolean> {
        if (source == null) return "" to false
        val buffer = Buffer()
        var remaining = MAX_BODY_BYTES + 1L
        while (remaining > 0) {
            val read = source.read(buffer, minOf(remaining, 8 * 1024L))
            if (read == -1L) break
            remaining -= read
        }
        val bytes = buffer.readByteArray()
        val truncated = bytes.size > MAX_BODY_BYTES
        val shown = if (truncated) bytes.copyOf(MAX_BODY_BYTES) else bytes
        var text = String(shown, Charsets.UTF_8)
        if (truncated) text = text.trimEnd('\uFFFD')
        return text to truncated
    }

    private fun httpErrorMessage(e: Throwable): String = when (e) {
        is InterruptedIOException -> "请求超时或已取消（工具超时 ${HTTP_TIMEOUT_SECONDS}s）；检查网络或换一个地址"
        is UnknownHostException -> if (e.message.orEmpty().startsWith("拒绝访问")) e.message!!
            else "DNS 解析失败；检查域名拼写和网络"
        is SSLException -> "TLS/证书校验失败；检查系统时间、代理或改用 https 官方域名"
        else -> e.message ?: e.javaClass.simpleName
    }
}

private fun looksLikeHtml(contentType: String, body: String): Boolean {
    if (contentType.contains("html", ignoreCase = true)) return true
    val head = body.take(200).trimStart().lowercase()
    return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<body")
}

/**
 * 极简 HTML → 正文：去掉脚本/样式/注释和标签，保留可读文本。
 * 不做完整 DOM 解析（工具只跑在手机上，依赖越少越好），够模型读到正文即可。
 */
internal fun htmlToText(html: String): String {
    var s = html
    s = s.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
    s = s.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
    s = s.replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
    s = s.replace(Regex("(?is)<head[^>]*>.*?</head>"), " ")
    s = s.replace(Regex("(?s)<!--.*?-->"), " ")
    // 块级标签换行，避免两段文字粘成一句
    s = s.replace(
        Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|table|ul|ol|td|th|pre|blockquote)[^>]*>"),
        "\n",
    )
    s = s.replace(Regex("(?s)<[^>]+>"), " ")
    s = s.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
    s = s.replace(Regex("[ \\t\\u00A0]+"), " ")
    s = s.replace(Regex(" *\\n *"), "\n")
    s = s.replace(Regex("\\n{3,}"), "\n\n")
    return s.trim()
}
