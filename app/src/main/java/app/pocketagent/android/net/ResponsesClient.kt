package app.pocketagent.android.net

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

class ResponsesClient(private val http: OkHttpClient = Http.client) : LlmClient {

    override fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        // 修复前保存的工具轮没有 reasoning item，思考模型会直接拒绝这一类历史。
        // 把这类旧工具轮降级成纯文本（保留正文，丢掉调用与结果），保证老对话还能继续聊。
        val droppedCallIds = messages.asSequence()
            .filter { it.role == "assistant" && it.toolCalls.isNotEmpty() && it.reasoningItem.isNullOrBlank() }
            .flatMap { it.toolCalls.asSequence() }
            .map { it.id }
            .toSet()
        // 思考模式下，中转站有时不返回 reasoning item（实测：指令较长时就不返回），
        // 但回放时又强制要求它。把这类 assistant 轮降级成 user 侧记录，
        // 既保留信息，也不会触发 500。
        val thinking = config.provider.reasoningEffort != null
        val payload = buildJsonObject {
            put("model", config.provider.model)
            put("stream", true)
            if (systemPrompt.isNotBlank()) put("instructions", systemPrompt)
            putJsonArray("input") {
                messages.forEach { m ->
                    val calls = m.toolCalls.filterNot { it.id in droppedCallIds }
                    when {
                        m.role == "tool" -> {
                            if (m.toolCallId != null && m.toolCallId in droppedCallIds) {
                                add(buildJsonObject {
                                    put("role", "user")
                                    put("content", "〔工具结果 ${m.toolCallId}〕\n" + compactLegacyNote(m.content))
                                })
                            } else {
                                add(buildJsonObject {
                                    put("type", "function_call_output")
                                    put("call_id", m.toolCallId ?: "")
                                    put("output", m.content)
                                })
                            }
                        }
                        m.role == "assistant" -> {
                            if (thinking && m.reasoningItem.isNullOrBlank()) {
                                if (m.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("role", "user")
                                        put("content", "〔上一轮助手输出〕" + compactLegacyNote(m.content))
                                    })
                                }
                            } else if (calls.isEmpty()) {
                                // 普通回答：reasoning 紧贴它所属的 assistant 正文。
                                m.reasoningItem?.let { raw ->
                                    parseReasoningItem(raw)?.let { add(it) }
                                }
                                if (m.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("role", "assistant")
                                        put("content", m.content)
                                    })
                                }
                            } else {
                                // 工具回合按服务端原始输出顺序回放：reasoning → 正文 → function_call。
                                // 顺序反了（正文在 reasoning 前）中转站会报
                                // 「reasoning_text must be passed back」，这是间歇性 500 的来源。
                                m.reasoningItem?.let { raw ->
                                    parseReasoningItem(raw)?.let { add(it) }
                                }
                                if (m.content.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("role", "assistant")
                                        put("content", m.content)
                                    })
                                }
                                calls.forEach { c ->
                                    add(buildJsonObject {
                                        put("type", "function_call")
                                        put("call_id", c.id)
                                        put("name", c.name)
                                        put("arguments", c.arguments)
                                    })
                                }
                            }
                        }
                        else -> add(buildJsonObject {
                            put("role", m.role)
                            if (m.images.isNotEmpty() && m.role == "user") {
                                putJsonArray("content") {
                                    if (m.content.isNotBlank()) {
                                        add(buildJsonObject {
                                            put("type", "input_text")
                                            put("text", m.content)
                                        })
                                    }
                                    m.images.forEach { url ->
                                        add(buildJsonObject {
                                            put("type", "input_image")
                                            put("image_url", url)
                                        })
                                    }
                                }
                            } else {
                                put("content", m.content)
                            }
                        })
                    }
                }
            }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", Json.parseToJsonElement(t.parametersJson).jsonObject)
                        })
                    }
                }
                put("tool_choice", "auto")
                // 和桌面一致：允许同一轮返回多个工具调用（App 仍按顺序逐个执行/审批）。
                put("parallel_tool_calls", true)
            }
            // 中转站不保存会话状态；显式关闭，避免不同请求之间发生隐式状态残留。
            put("store", false)
            // 桌面 Codex 在无服务端状态时也靠这个字段拿到可回传的思考内容。
            putJsonArray("include") { add("reasoning.encrypted_content") }
            config.provider.temperature?.let { put("temperature", it) }
            config.provider.serviceTier?.let { put("service_tier", it) }
            // 桌面固定发 reasoning.summary = "auto"：不带它，服务端不吐思考摘要，
            // App 的「思考过程」就一直是空的。effort 有值时才加。
            put("reasoning", buildJsonObject {
                put("summary", "auto")
                config.provider.reasoningEffort?.let { effort ->
                    put("effort", effort)
                    // 让模型在整轮工具循环里保留推理上下文，而不是每回灌一次工具结果就重想
                    put("context", "all_turns")
                }
            })
            // 同一组会话复用前缀缓存（桌面用 session id 做 key）
            config.cacheKey?.takeIf { it.isNotBlank() }?.let { put("prompt_cache_key", it) }
            // 桌面 Codex 不发 text.verbosity，这里跟着不发；写死 low 会让回答明显变短。
            // 需要更省 token 时在「模型来源」里自己选 low。
            config.provider.verbosity?.takeIf { it.isNotBlank() }?.let { level ->
                put("text", buildJsonObject { put("verbosity", level) })
            }
        }

        val request = Request.Builder()
            .url(endpoint(config.provider.baseUrl, "responses"))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        // 仅调试用：PA_DEBUG_PAYLOAD=/tmp/x.jsonl 时把每次请求体落盘，便于对齐中转站要求。
        // 默认关闭；线上/手机上不会写任何东西。
        System.getenv("PA_DEBUG_PAYLOAD")?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { java.io.File(path).appendText(payload.toString() + "\n") }
        }

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
                        if (dataText.isEmpty() || dataText == "[DONE]") continue
                        System.getenv("PA_DEBUG_SSE")?.takeIf { it.isNotBlank() }?.let { path ->
                            runCatching { java.io.File(path).appendText(dataText + "\n") }
                        }
                        val node = runCatching { Json.parseToJsonElement(dataText).jsonObject }.getOrNull()
                            ?: continue
                        parseEvent(node)?.forEach {
                            if (it is LlmEvent.Finished) sawTerminalEvent = true
                            emit(it)
                        }
                    }
                }
                if (!sawTerminalEvent) {
                    throw LlmException(0, "SSE 在 response.completed 之前断开，回答可能不完整；请重试")
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

    private fun parseEvent(node: kotlinx.serialization.json.JsonObject): List<LlmEvent>? {
        val out = mutableListOf<LlmEvent>()
        when ((node["type"] as? JsonPrimitive)?.contentOrNull) {
            "response.output_text.delta" ->
                (node["delta"] as? JsonPrimitive)?.contentOrNull?.let { out += LlmEvent.TextDelta(it) }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                (node["delta"] as? JsonPrimitive)?.contentOrNull?.let { out += LlmEvent.ReasoningDelta(it) }

            "response.output_item.added", "response.output_item.done" -> {
                val eventType = (node["type"] as? JsonPrimitive)?.contentOrNull
                val item = node["item"] as? JsonObject
                when ((item?.get("type") as? JsonPrimitive)?.contentOrNull) {
                    "reasoning" -> {
                        // done 事件里的 item 是完整版；AgentRunner 以最后收到的一份为准。
                        item?.let { out += LlmEvent.ReasoningItem(it.toString()) }
                    }
                    "function_call" -> if (eventType == "response.output_item.added") {
                        val index = (node["output_index"] as? JsonPrimitive)?.intOrNull ?: 0
                        val callId = (item?.get("call_id") as? JsonPrimitive)?.contentOrNull
                            ?: (item?.get("id") as? JsonPrimitive)?.contentOrNull ?: ""
                        val name = (item?.get("name") as? JsonPrimitive)?.contentOrNull ?: ""
                        out += LlmEvent.ToolCallStarted(index, callId, name)
                    }
                }
            }

            "response.function_call_arguments.delta" -> {
                val index = (node["output_index"] as? JsonPrimitive)?.intOrNull ?: 0
                (node["delta"] as? JsonPrimitive)?.contentOrNull?.let { out += LlmEvent.ToolCallArguments(index, it) }
            }

            "response.completed" -> {
                val response = node["response"] as? JsonObject
                val usage = response?.get("usage") as? JsonObject
                out += LlmEvent.Usage(
                    (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull,
                    (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull,
                )
                // 有些网关只发 completed，不发 output_item.done；从完整 output 里补一份。
                (response?.get("output") as? kotlinx.serialization.json.JsonArray)?.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    if ((item["type"] as? JsonPrimitive)?.contentOrNull == "reasoning") {
                        out += LlmEvent.ReasoningItem(item.toString())
                    }
                }
                // 网关也可能发 completed 但 status=incomplete（例如 max_output_tokens 截断）。
                // 以前一律当 stop，用户看到的是一条没有结尾、也没有任何提示的回答。
                val status = (response?.get("status") as? JsonPrimitive)?.contentOrNull
                val incompleteReason = ((response?.get("incomplete_details") as? JsonObject)
                    ?.get("reason") as? JsonPrimitive)?.contentOrNull
                if (status == "incomplete" || incompleteReason != null) {
                    // 交给 runner：finishReason=length 时会明确告诉用户「回答被截断，可以让我继续」
                    out += LlmEvent.Finished("length")
                } else {
                    out += LlmEvent.Finished("stop")
                }
            }

            "response.incomplete" -> {
                val response = node["response"] as? JsonObject
                val reason = ((response?.get("incomplete_details") as? JsonObject)
                    ?.get("reason") as? JsonPrimitive)?.contentOrNull
                throw LlmException(
                    0,
                    "回答被截断（response.incomplete" + (reason?.let { "：$it" } ?: "") + "）",
                )
            }

            "response.failed" -> {
                val err = ((node["response"] as? JsonObject)?.get("error")) as? JsonObject
                val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "响应失败"
                throw LlmException(0, msg)
            }

            "error" -> {
                val msg = (node["message"] as? JsonPrimitive)?.contentOrNull ?: node.toString()
                throw LlmException(0, msg)
            }
        }
        return out.ifEmpty { null }
    }

    override suspend fun probe(config: LlmConfig): String? = runCatching {
        val payload = buildJsonObject {
            put("model", config.provider.model)
            put("input", "ping")
            put("max_output_tokens", 16)
        }
        val request = Request.Builder()
            .url(endpoint(config.provider.baseUrl, "responses"))
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).execute().use { res ->
            if (res.isSuccessful) null else LlmException(res.code, res.body?.string().orEmpty()).message
        }
    }.getOrElse { e -> e.message ?: "连接失败" }

    private fun parseReasoningItem(raw: String): JsonObject? {
        val item = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull
        return item.takeIf { type == "reasoning" }
    }

    /**
     * 旧会话降级记录只需要让模型知道做过什么，不需要逐字保留全部工具输出。
     * 1641 条那条对话里有大量旧工具轮，不压缩会把请求撑到几 MB。
     */
    private fun compactLegacyNote(text: String, limit: Int = 800): String =
        if (text.length <= limit) {
            text
        } else {
            text.take(limit) + "\n…（旧记录已截断 ${text.length - limit} 字符）"
        }
}
