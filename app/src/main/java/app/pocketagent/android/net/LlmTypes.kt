package app.pocketagent.android.net

import java.util.Base64

import app.pocketagent.android.data.Provider
import kotlinx.coroutines.flow.Flow

data class LlmConfig(
    val provider: Provider,
    val apiKey: String,
    /** 会话级缓存键（Responses 的 prompt_cache_key）；同一组对话复用前缀缓存，明显更快 */
    val cacheKey: String? = null,
)

data class ApiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ApiMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<ApiToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** data URL 列表，多模态输入 */
    val images: List<String> = emptyList(),
    /**
     * Responses API 的 reasoning item 原文。思考模型的工具循环要求把它原样回传，
     * 且必须排在对应的 function_call 之前；其它协议忽略此字段。
     */
    val reasoningItem: String? = null,
)

private const val DEEPSEEK_REASONING_PREFIX = "ds:"

/** DeepSeek 思考模式要求 assistant 历史原样带 reasoning_content，用不透明前缀持久化。 */
fun encodeDeepSeekReasoning(text: String): String? =
    text.takeIf { it.isNotBlank() }?.let {
        DEEPSEEK_REASONING_PREFIX + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
    }

fun decodeDeepSeekReasoning(item: String?): String? {
    if (item.isNullOrBlank() || !item.startsWith(DEEPSEEK_REASONING_PREFIX)) return null
    return runCatching {
        String(Base64.getDecoder().decode(item.removePrefix(DEEPSEEK_REASONING_PREFIX)), Charsets.UTF_8)
    }.getOrNull()
}

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 字符串 */
    val parametersJson: String,
)

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ReasoningDelta(val text: String) : LlmEvent
    /** 可直接回传的 reasoning item 完整 JSON（来自 output_item.added/done）。 */
    data class ReasoningItem(val json: String) : LlmEvent
    data class ToolCallStarted(val index: Int, val id: String, val name: String) : LlmEvent
    data class ToolCallArguments(val index: Int, val delta: String) : LlmEvent
    data class Usage(val input: Int?, val output: Int?) : LlmEvent
    data class Finished(val reason: String?) : LlmEvent
}

class LlmException(
    val statusCode: Int,
    val errorBody: String,
) : Exception(friendlyMessage(statusCode, errorBody)) {
    companion object {
        fun friendlyMessage(code: Int, body: String): String {
            val detail = extractMessage(body)
            return when (code) {
                0 -> "响应流错误" + detail
                401, 403 -> "Key 无效或没有权限（HTTP $code）" + detail
                404 -> "接口地址不对（HTTP 404），检查 Base URL 是否要带 /v1" + detail
                408 -> "请求超时（HTTP 408）" + detail
                413 -> "请求体太大（HTTP 413），先 /compact 或减少图片" + detail
                429 -> "被限流或额度用完（HTTP 429）" + detail
                400 -> "请求参数被服务端拒绝（HTTP 400）" + detail
                in 500..599 -> "服务端出错（HTTP $code）" + detail
                else -> "请求失败（HTTP $code）" + detail
            }
        }

        private fun extractMessage(body: String): String {
            val m = Regex("\"(?:message|error_description)\"\\s*:\\s*\"([^\"]{0,200})").find(body)
            return if (m != null) "：${m.groupValues[1]}" else if (body.isBlank()) "" else "：${body.take(160)}"
        }
    }
}

interface LlmClient {
    fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent>

    /** 设置页的「测试连接」，返回 null 表示成功，否则返回错误描述。 */
    suspend fun probe(config: LlmConfig): String?
}
