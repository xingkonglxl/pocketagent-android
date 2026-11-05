package app.pocketagent.android.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 拼接口地址。用户可能填 https://api.deepseek.com、.../v1 或带尾部斜杠，
 * 也可能图省事直接填到 /chat/completions，这里都兼容。
 */
fun endpoint(baseUrl: String, path: String, alreadyVersioned: Boolean = false): String {
    val base = baseUrl.trim().trimEnd('/')
    require(base.isNotEmpty()) { "接口地址不能为空" }
    require(isSecureBaseUrl(base)) { "接口地址不安全：请使用 HTTPS，且不要在 URL 中填写用户名或密码" }
    if (base.endsWith("/$path")) return base
    // Claude 的路径是 /v1/messages：用户可能填到域名，也可能填到 /v1
    if (alreadyVersioned) {
        val tail = path.removePrefix("v1/")
        return if (base.endsWith("/v1")) "$base/$tail" else "$base/v1/$tail"
    }
    return "$base/$path"
}

/**
 * 接口地址必须是 https：http 会让 API Key 和整段对话在链路上明文传输。
 * 只放行本机 / 内网地址（自己搭的模型服务常见）。
 */
fun isSecureBaseUrl(raw: String): Boolean {
    val url = raw.trim().toHttpUrlOrNull() ?: return false
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
    if (url.isHttps) return true
    val host = url.host
    if (host == "localhost" || host == "::1") return true
    // Only numeric addresses qualify; `10.attacker.example` is a public DNS name.
    val octets = host.split('.').map { it.toIntOrNull() }
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
    val a = octets[0]!!
    val b = octets[1]!!
    return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
}
