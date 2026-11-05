package app.pocketagent.android.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

class ChatCompletionsClient(private val http: OkHttpClient = Http.client) : LlmClient {

    override fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        val payload = buildJsonObject {
            put("model", config.provider.model)
            put("stream", true)
            val deepSeek = config.provider.model.startsWith("deepseek", ignoreCase = true)
            putJsonArray("messages") {
                if (systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        when {
                            m.images.isNotEmpty() && m.role == "user" -> {
                                putJsonArray("content") {
                                    if (m.content.isNotBlank()) {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", m.content)
                                        })
                                    }
                                    m.images.forEach { url ->
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject { put("url", url) })
                                        })
                                    }
                                }
                            }
                            m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                                if (deepSeek) {
                                    put("reasoning_content", decodeDeepSeekReasoning(m.reasoningItem).orEmpty())
                                }
                                if (m.content.isNotBlank()) put("content", m.content) else put("content", "")
                                putJsonArray("tool_calls") {
                                    m.toolCalls.forEach { c ->
                                        add(buildJsonObject {
                                            put("id", c.id)
                                            put("type", "function")
                                            put("function", buildJsonObject {
                                                put("name", c.name)
                                                put("arguments", c.arguments)
                                            })
                                        })
                                    }
                                }
                            }
                            m.role == "tool" -> {
                                put("content", m.content)
                                put("tool_call_id", m.toolCallId ?: "")
                            }
                            else -> {
                                if (deepSeek && m.role == "assistant") {
                                    put("reasoning_content", decodeDeepSeekReasoning(m.reasoningItem).orEmpty())
                                }
                                put("content", m.content)
                            }
                        }
                    })
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", Json.parseToJsonElement(t.parametersJson).jsonObject)
                            })
                        })
                    }
                }
                put("tool_choice", "auto")
                put("parallel_tool_calls", true)
            }
            config.provider.temperature?.let { put("temperature", it) }
            config.provider.reasoningEffort?.let { put("reasoning_effort", it) }
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }

        val request = Request.Builder()
            .url(endpoint(config.provider.baseUrl, "chat/completions"))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        var attempt = 0
        var sawTerminalEvent = false
        while (true) {
            val call = http.newCall(request)
            val cancellation = call.cancelWithCoroutine()
            try {
                val response = call.execute()
                if (!response.isSuccessful && (response.code == 429 || response.code >= 500) && attempt < 2) {
                    response.close()
                    attempt++
                    delay(1_000L * attempt)
                    continue
                }
                response.use {
                    if (!response.isSuccessful) {
                        throw LlmException(response.code, response.body?.string().orEmpty())
                    }
                    val source = response.body?.source() ?: throw LlmException(0, "空响应")
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val dataText = line.removePrefix("data:").trim()
                        if (dataText.isEmpty()) continue
                        if (dataText == "[DONE]") {
                            sawTerminalEvent = true
                            break
                        }
                        val node = runCatching { Json.parseToJsonElement(dataText).jsonObject }.getOrNull()
                            ?: continue
                        parseChunk(node)?.forEach {
                            if (it is LlmEvent.Finished) sawTerminalEvent = true
                            emit(it)
                        }
                    }
                }
                if (!sawTerminalEvent) {
                    throw LlmException(0, "SSE 在结束标记前断开，回答可能不完整；请重试")
                }
                break
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                cancellation.cancel()
            }
        }
    }

    private fun parseChunk(node: JsonObject): List<LlmEvent>? {
        val out = mutableListOf<LlmEvent>()
        // 中转站会在每个分片里带 "usage": null、"content": null，
        // 必须用 as? 安全取值，否则 JsonNull 会抛异常把整条流打断。
        (node["usage"] as? JsonObject)?.let { u ->
            out += LlmEvent.Usage(
                (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull,
                (u["completion_tokens"] as? JsonPrimitive)?.intOrNull,
            )
        }
        val choice = (node["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return out.ifEmpty { null }
        val delta = choice["delta"] as? JsonObject
        (delta?.get("content") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            out += LlmEvent.TextDelta(it)
        }
        (delta?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            out += LlmEvent.ReasoningDelta(it)
        }
        (delta?.get("tool_calls") as? JsonArray)?.forEach { element ->
            val call = element as? JsonObject ?: return@forEach
            val index = (call["index"] as? JsonPrimitive)?.intOrNull ?: 0
            val fn = call["function"] as? JsonObject
            val id = (call["id"] as? JsonPrimitive)?.contentOrNull
            val name = (fn?.get("name") as? JsonPrimitive)?.contentOrNull
            if (!id.isNullOrEmpty() && !name.isNullOrEmpty()) {
                out += LlmEvent.ToolCallStarted(index, id, name)
            }
            (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                out += LlmEvent.ToolCallArguments(index, it)
            }
        }
        (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let {
            out += LlmEvent.Finished(it)
        }
        return out.ifEmpty { null }
    }

    override suspend fun probe(config: LlmConfig): String? = runCatching {
        val payload = buildJsonObject {
            put("model", config.provider.model)
            put("stream", false)
            put("max_tokens", 8)
            putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", "ping") }) }
        }
        val request = Request.Builder()
            .url(endpoint(config.provider.baseUrl, "chat/completions"))
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { res ->
            if (res.isSuccessful) null else LlmException(res.code, res.body?.string().orEmpty()).message
        }
    }.getOrElse { e -> e.message ?: "连接失败" }
}
