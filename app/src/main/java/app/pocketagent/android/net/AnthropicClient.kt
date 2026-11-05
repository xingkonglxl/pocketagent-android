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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * Claude（Anthropic /v1/messages）。和另外两家的差别：
 * - 鉴权用 x-api-key + anthropic-version，不是 Bearer
 * - system 是顶层字段，不进 messages
 * - 工具调用是 content block（tool_use / tool_result）
 */
class AnthropicClient(private val http: OkHttpClient = Http.client) : LlmClient {

    override fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        val payload = buildJsonObject {
            put("model", config.provider.model)
            // 思考预算是从 max_tokens 里扣的，Anthropic 要求 budget_tokens 严格小于 max_tokens。
            // 以前固定发 max_tokens=4096，选「高/很高/最高」档直接 400，表现为「一开思考就失败」。
            val thinking = config.provider.reasoningEffort
                ?.takeIf { it != "none" }
                ?.let { thinkingBudget(it) }
            put(
                "max_tokens",
                if (thinking != null) maxOf(config.provider.maxOutputTokens, thinking + 1024)
                else config.provider.maxOutputTokens,
            )
            put("stream", true)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            put("messages", buildMessages(messages))
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", Json.parseToJsonElement(t.parametersJson).jsonObject)
                        })
                    }
                }
            }
            // 开了 thinking 就不能再带 temperature（Anthropic 会报错）
            if (thinking == null) config.provider.temperature?.let { put("temperature", it) }
            if (thinking != null) {
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", "enabled")
                        put("budget_tokens", thinking)
                    },
                )
            }
        }

        val request = Request.Builder()
            .url(endpoint(config.provider.baseUrl, "v1/messages", alreadyVersioned = true))
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        var attempt = 0
        // 思考块必须原样回传给下一轮（带工具调用时尤其严格），否则 Anthropic 直接 400。
        val thinkingState = ThinkingState()
        while (true) {
            val call = http.newCall(request)
            val cancellation = call.cancelWithCoroutine()
            try {
            val response = call.execute()
            if (!response.isSuccessful && (response.code == 429 || response.code >= 500) && attempt < 1) {
                response.close()
                attempt++
                delay(1200)
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
                    if (dataText.isEmpty() || dataText == "[DONE]") continue
                    val node = runCatching { Json.parseToJsonElement(dataText).jsonObject }.getOrNull() ?: continue
                    parseEvent(node, thinkingState)?.forEach { emit(it) }
                }
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

    /** 把内部统一消息转成 Claude 的 content block 格式，并把相邻同角色消息合并。 */
    private fun buildMessages(messages: List<ApiMessage>): JsonArray {
        val out = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun push(role: String, block: JsonObject) {
            val last = out.lastOrNull()
            if (last != null && last.first == role) last.second.add(block)
            else out.add(role to mutableListOf(block))
        }

        messages.forEach { m ->
            when {
                m.role == "tool" -> push(
                    "user",
                    buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", m.toolCallId ?: "")
                        put("content", m.content)
                    },
                )

                m.role == "assistant" -> {
                    // 开了思考就得把上一轮的 thinking 块（含签名）原样带回，
                    // 而且必须排在同一条 assistant 消息的最前面。
                    m.reasoningItem?.let { raw ->
                        runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull()
                            ?.filterIsInstance<JsonObject>()
                            ?.filter {
                                (it["type"] as? JsonPrimitive)?.contentOrNull in
                                    setOf("thinking", "redacted_thinking")
                            }
                            ?.forEach { push("assistant", it) }
                    }
                    if (m.content.isNotBlank()) {
                        push("assistant", buildJsonObject {
                            put("type", "text")
                            put("text", m.content)
                        })
                    }
                    m.toolCalls.forEach { c ->
                        val input = runCatching { Json.parseToJsonElement(c.arguments).jsonObject }
                            .getOrElse { JsonObject(emptyMap()) }
                        push("assistant", buildJsonObject {
                            put("type", "tool_use")
                            put("id", c.id)
                            put("name", c.name)
                            put("input", input)
                        })
                    }
                }

                else -> {
                    // 文字和所有图片都要发；旧实现只发第一张，扫描版 PDF 会只剩封面。
                    val role = if (m.role == "system") "user" else m.role
                    if (m.content.isNotBlank() || m.images.isEmpty()) {
                        push(role, buildJsonObject {
                            put("type", "text")
                            put("text", m.content)
                        })
                    }
                    // 图片走 base64 block，data URL 形如 data:image/png;base64,xxxx
                    m.images.forEach { url ->
                        val media = url.substringAfter("data:").substringBefore(";")
                        push(role, buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", media.ifBlank { "image/png" })
                                put("data", url.substringAfter("base64,", ""))
                            })
                        })
                    }
                }
            }
        }

        return JsonArray(
            out.map { (role, blocks) ->
                buildJsonObject {
                    put("role", role)
                    put("content", JsonArray(blocks))
                }
            },
        )
    }

    /** 一轮回复里可能有多段 thinking，每段都带签名；回传时要整段带回去。 */
    private class ThinkingState {
        val blocks = mutableListOf<JsonObject>()
        var text: StringBuilder? = null
        var signature: StringBuilder? = null
        /**
         * 真正的结束原因只出现在 message_delta 里；紧跟的 message_stop 不带任何信息。
         * 以前 message_stop 一律报 end_turn，把 max_tokens 覆盖掉，
         * 于是被输出上限截断的回答被当成正常结束，续写和提示都不会触发。
         */
        var stopReason: String? = null
    }

    private fun parseEvent(node: JsonObject, thinking: ThinkingState): List<LlmEvent>? {
        val out = mutableListOf<LlmEvent>()
        val type = (node["type"] as? JsonPrimitive)?.contentOrNull
        when (type) {
            "message_start" -> {
                // 同一条流理论上只有一个 message，但重试/拼接时别把上一条的原因带过来
                thinking.stopReason = null
                val usage = ((node["message"] as? JsonObject)?.get("usage")) as? JsonObject
                (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull?.let {
                    out += LlmEvent.Usage(it, null)
                }
            }

            "content_block_start" -> {
                val block = node["content_block"] as? JsonObject
                when ((block?.get("type") as? JsonPrimitive)?.contentOrNull) {
                    "tool_use" -> {
                        val index = (node["index"] as? JsonPrimitive)?.intOrNull ?: 0
                        val id = (block["id"] as? JsonPrimitive)?.contentOrNull ?: "tool_$index"
                        val name = (block["name"] as? JsonPrimitive)?.contentOrNull ?: ""
                        out += LlmEvent.ToolCallStarted(index, id, name)
                    }
                    "thinking" -> {
                        thinking.text = StringBuilder()
                        thinking.signature = StringBuilder()
                    }
                    // 被安全策略加密的思考块：原样收下、原样回传，不需要解析内容
                    "redacted_thinking" -> thinking.blocks += block
                }
            }

            "content_block_delta" -> {
                val index = (node["index"] as? JsonPrimitive)?.intOrNull ?: 0
                val delta = node["delta"] as? JsonObject
                when ((delta?.get("type") as? JsonPrimitive)?.contentOrNull) {
                    "text_delta" -> (delta["text"] as? JsonPrimitive)?.contentOrNull?.let {
                        out += LlmEvent.TextDelta(it)
                    }

                    "thinking_delta" -> (delta["thinking"] as? JsonPrimitive)?.contentOrNull?.let {
                        out += LlmEvent.ReasoningDelta(it)
                        thinking.text?.append(it)
                    }

                    "signature_delta" -> (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let {
                        thinking.signature?.append(it)
                    }

                    "input_json_delta" -> (delta["partial_json"] as? JsonPrimitive)?.contentOrNull?.let {
                        out += LlmEvent.ToolCallArguments(index, it)
                    }
                }
            }

            "content_block_stop" -> {
                val text = thinking.text
                if (text != null) {
                    thinking.blocks += buildJsonObject {
                        put("type", "thinking")
                        put("thinking", text.toString())
                        put("signature", thinking.signature?.toString().orEmpty())
                    }
                    thinking.text = null
                    thinking.signature = null
                    // 每凑齐一段就把整组回传一次；后面还有的话会覆盖成更完整的一组
                    out += LlmEvent.ReasoningItem(JsonArray(thinking.blocks.toList()).toString())
                }
            }

            "message_delta" -> {
                val usage = node["usage"] as? JsonObject
                (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull?.let {
                    out += LlmEvent.Usage(null, it)
                }
                val stop = ((node["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull
                if (stop != null) {
                    thinking.stopReason = stop
                    out += LlmEvent.Finished(stop)
                }
            }

            // message_stop 只表示「流结束」，不带 stop_reason。真正的结束原因已经在
            // message_delta 里发过一次，这里不再重复发（否则会覆盖成 end_turn）；
            // 只有整条流没给过 stop_reason（极简响应）才补一个 end_turn。
            "message_stop" -> if (thinking.stopReason == null) out += LlmEvent.Finished("end_turn")

            "error" -> {
                val err = node["error"] as? JsonObject
                val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: node.toString()
                throw LlmException(0, msg)
            }
        }
        return out.ifEmpty { null }
    }

    override suspend fun probe(config: LlmConfig): String? = runCatching {
        val payload = buildJsonObject {
            put("model", config.provider.model)
            put("max_tokens", 16)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "ping")
                        })
                    }
                })
            }
        }
        val request = Request.Builder()
            .url(endpoint(config.provider.baseUrl, "v1/messages", alreadyVersioned = true))
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { res ->
            if (res.isSuccessful) null else LlmException(res.code, res.body?.string().orEmpty()).message
        }
    }.getOrElse { e -> e.message ?: "连接失败" }

    private fun thinkingBudget(level: String): Int = when (level) {
        "minimal" -> 1024
        "low" -> 2048
        "medium" -> 4096
        "high" -> 8192
        "xhigh" -> 12288
        else -> 16384
    }
}
