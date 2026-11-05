package app.pocketagent.android.mcp

import app.pocketagent.android.net.Http
import app.pocketagent.android.net.isSecureBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.util.concurrent.TimeUnit

/** MCP 服务器返回的一个工具。`inputSchema` 原样作为函数参数 schema 使用。 */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpCallResult(
    val text: String,
    val isError: Boolean,
)

/** MCP 服务器暴露的一个资源（文件、文档、数据库行等）。 */
data class McpResource(
    val uri: String,
    val name: String,
    val mimeType: String,
    val description: String,
)

class McpException(message: String) : Exception(message)

/** 工具层只依赖这三个能力，单测可以离线替身，不必起 HTTP 服务器。 */
interface McpSession {
    suspend fun initialize(): String
    suspend fun listTools(): List<McpToolInfo>
    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult
    suspend fun listResources(): List<McpResource> = emptyList()
    suspend fun readResource(uri: String): String =
        throw McpException("该服务器没有实现 resources/read")
}

/**
 * 最小可用的 MCP 客户端：Streamable HTTP + JSON-RPC。
 *
 * 只支持 HTTP（含 `text/event-stream` 响应），不支持 stdio——手机上跑不了
 * node / python 的 MCP 服务器，stdio 类型请放到远端机器用 `remote_codex`。
 *
 * 令牌只放在 Authorization 头里，不写日志、不拼进 URL，错误信息里也不会回显。
 */
class McpClient(
    private val endpoint: String,
    private val token: String? = null,
    http: OkHttpClient = defaultHttp(),
) : McpSession {
    // Never forward Mcp-Session-Id or credentials through an automatic redirect.
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var initialized = false

    private var nextId = 0

    /** 返回服务器名（`serverInfo.name`），用于界面展示；服务器没给就返回空串。 */
    override suspend fun initialize(): String = withContext(Dispatchers.IO) {
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "PocketAgent")
                        put("version", "android")
                    },
                )
            },
        )
        initialized = true
        // 初始化通知失败不影响后续请求：有些服务器不要求，也有的是无状态实现。
        runCatching { notify("notifications/initialized") }
        result["serverInfo"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    override suspend fun listTools(): List<McpToolInfo> {
        ensureInitialized()
        val result = request("tools/list", JsonObject(emptyMap()))
        val array = result["tools"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val tool = element as? JsonObject ?: return@mapNotNull null
            val name = tool["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            McpToolInfo(
                name = name,
                description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = tool["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        ensureInitialized()
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        val content = result["content"] as? JsonArray
        val text = if (content == null) {
            result.toString()
        } else {
            content.joinToString("\n") { renderPart(it) }.trim()
        }
        return McpCallResult(text.ifBlank { "（MCP 工具没有返回文本内容）" }, isError)
    }

    override suspend fun listResources(): List<McpResource> {
        ensureInitialized()
        val result = try {
            request("resources/list", JsonObject(emptyMap()))
        } catch (error: McpException) {
            if (isMethodMissing(error)) {
                throw McpException("该服务器不支持 resources/list（只提供工具）")
            }
            throw error
        }
        val array = result["resources"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val uri = obj["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            McpResource(
                uri = uri,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    override suspend fun readResource(uri: String): String {
        ensureInitialized()
        val result = request(
            "resources/read",
            buildJsonObject { put("uri", uri) },
        )
        val contents = result["contents"] as? JsonArray ?: return ""
        return contents.joinToString("\n\n") { element ->
            val obj = element as? JsonObject ?: return@joinToString element.toString()
            val text = obj["text"]?.jsonPrimitive?.contentOrNull
            when {
                !text.isNullOrEmpty() -> text
                obj["blob"] != null -> "（二进制资源：${obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: "未知类型"}，" +
                    "base64 ${obj["blob"]?.jsonPrimitive?.contentOrNull?.length ?: 0} 字符；手机上不展开）"
                else -> "（资源为空：$uri）"
            }
        }.trim()
    }

    private fun isMethodMissing(error: McpException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("-32601") || message.contains("Method not found", ignoreCase = true) ||
            message.contains("method not found", ignoreCase = true)
    }

    private suspend fun ensureInitialized() {
        if (!initialized) initialize()
    }

    private fun renderPart(element: kotlinx.serialization.json.JsonElement): String {
        val part = element as? JsonObject ?: return element.toString()
        return when (val type = part["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "image" -> {
                val mime = part["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image"
                val size = part["data"]?.jsonPrimitive?.contentOrNull?.length ?: 0
                "（MCP 返回图片：$mime，base64 $size 字符；手机上只保留占位，避免占满上下文）"
            }
            "resource" -> {
                val resource = part["resource"] as? JsonObject
                val uri = resource?.get("uri")?.jsonPrimitive?.contentOrNull.orEmpty()
                val text = resource?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (text.isNotBlank()) text else "（MCP 资源：$uri）"
            }
            null -> part.toString()
            else -> "（MCP 返回 $type 内容）"
        }
    }

    /**
     * 服务器可能随时让 session 过期（返回 404）。这时丢掉旧 session、重新 initialize
     * 再重试一次，避免用户看到一次“工具突然全失败”。
     */
    private suspend fun request(method: String, params: JsonObject): JsonObject {
        return try {
            doRequest(method, params)
        } catch (e: McpException) {
            val expired = sessionId != null && e.message.orEmpty().contains("HTTP 404")
            if (!expired) throw e
            sessionId = null
            initialized = false
            initialize()
            doRequest(method, params)
        }
    }

    private suspend fun doRequest(method: String, params: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val id = synchronized(this@McpClient) {
                nextId += 1
                nextId
            }
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            post(payload).use { response -> readResult(response, id) }
        }

    private fun notify(method: String) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        post(payload).use { response ->
            response.header(SESSION_HEADER)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            response.body?.close()
        }
    }

    private fun post(payload: JsonObject): Response {
        if (!isSecureBaseUrl(endpoint)) throw McpException("MCP 地址不安全：请使用 HTTPS；HTTP 仅允许本机或内网数字地址")
        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val builder = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Accept", "application/json, text/event-stream")
        sessionId?.takeIf { it.isNotBlank() }?.let { builder.header(SESSION_HEADER, it) }
        token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return http.newCall(builder.build()).execute()
    }

    private fun readResult(response: Response, id: Int): JsonObject {
        response.header(SESSION_HEADER)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
        val code = response.code
        when {
            code == 401 || code == 403 ->
                throw McpException("鉴权失败（HTTP $code）：检查该服务器是否需要在设置里填访问令牌")
            code == 404 -> throw McpException("HTTP 404：地址不对，或该服务器不支持 Streamable HTTP")
            !response.isSuccessful -> {
                val snippet = response.body?.string()?.trim()?.take(300).orEmpty()
                throw McpException("HTTP $code" + if (snippet.isNotEmpty()) "：$snippet" else "")
            }
        }
        val body = response.body ?: return JsonObject(emptyMap())
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (code == 202 || code == 204) {
            body.close()
            return JsonObject(emptyMap())
        }
        if (contentType.contains("text/event-stream")) {
            return readSseResult(body.source(), id)
        }
        val text = body.string()
        if (text.isBlank()) return JsonObject(emptyMap())
        val element = runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw McpException("响应不是合法 JSON：${text.take(200)}") }
        val envelope = element as? JsonObject ?: throw McpException("响应不是 JSON-RPC 对象")
        return unwrap(envelope)
    }

    private fun readSseResult(source: BufferedSource, id: Int): JsonObject {
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) {
                parseSseEvent(data)?.let { envelope ->
                    if (envelope["id"]?.jsonPrimitive?.intOrNull == id) return unwrap(envelope)
                }
                continue
            }
            if (line.startsWith("data:")) {
                data.append(line.removePrefix("data:").trim())
                if (data.length > MAX_SSE_EVENT_CHARS) {
                    throw McpException("SSE 事件超过 ${MAX_SSE_EVENT_CHARS} 字符，已停止读取")
                }
            }
        }
        parseSseEvent(data)?.let { envelope ->
            if (envelope["id"]?.jsonPrimitive?.intOrNull == id) return unwrap(envelope)
        }
        throw McpException("SSE 流里没有 id=$id 的结果（服务器可能中断了）")
    }

    private fun parseSseEvent(data: StringBuilder): JsonObject? {
        if (data.isEmpty()) return null
        val payload = data.toString()
        data.setLength(0)
        if (payload.isBlank() || payload == "[DONE]") return null
        return (runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject)
    }

    private fun unwrap(envelope: JsonObject): JsonObject {
        val error = envelope["error"] as? JsonObject
        if (error != null) {
            val message = error["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            val code = error["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
            throw McpException(if (code.isBlank()) "MCP 错误：$message" else "MCP 错误 $code：$message")
        }
        return envelope["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val MAX_SSE_EVENT_CHARS = 1_000_000
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttp(): OkHttpClient = Http.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
