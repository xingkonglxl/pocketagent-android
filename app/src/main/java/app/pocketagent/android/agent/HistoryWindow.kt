package app.pocketagent.android.agent

import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role

/**
 * 按模型真实的上下文窗口决定发多少历史，和桌面 Codex 的行为保持一致：
 * 只要没超过窗口就全量发，超过了才从最旧的消息开始丢。
 * 以整条消息为单位裁剪：assistant 的 tool_calls 和结果都挂在自己身上，
 * 所以不会出现「有 tool 结果没 tool 调用」的坏请求。
 */
object HistoryWindow {
    /** 模型目录里查不到窗口大小时用的兜底值（token）。 */
    const val DEFAULT_CONTEXT_TOKENS = 128_000

    /** 发历史最多用窗口的 90%，剩下的留给系统提示、工具定义和模型输出。 */
    private const val USABLE_PERCENT = 90

    /** 条数上限只是防御极端情况（几百条超短消息），正常由 token 预算决定。 */
    const val MAX_MESSAGES = 4_000

    /**
     * 中转站按请求体字节数设了 8MiB 上限，纯 token 预算挡不住图片和长中文。
     * 留 1MiB 给系统提示、工具定义和 JSON 包装，和 Mac 的 DeepSeek adapter 对齐。
     */
    const val DEFAULT_MAX_REQUEST_BYTES = 7 * 1024 * 1024

    /** 图片按每张 ~1000 token 粗算。 */
    private const val TOKENS_PER_IMAGE = 1_000

    /** 每条消息本身的角色、分隔符开销。 */
    private const val PER_MESSAGE_OVERHEAD = 4

    /**
     * 可分配给历史消息的 token 预算。
     *
     * 系统提示和工具 schema 也占窗口；以前只扣了固定的 10%，长 system prompt 下仍可能
     * 把请求顶过模型上限。调用方传入实际预留量后，预算会更接近 Codex 的真实可用上下文。
     */
    fun budgetFor(contextTokens: Int, reservedTokens: Int = 0): Int {
        val tokens = if (contextTokens > 0) contextTokens else DEFAULT_CONTEXT_TOKENS
        val usable = (tokens.toLong() * USABLE_PERCENT / 100).toInt()
        return (usable - reservedTokens.coerceAtLeast(0)).coerceAtLeast(1_000)
    }

    /**
     * 粗估 token：ASCII 约 4 字符/token，中文等非 ASCII 约 1 字符/token。
     * 宁可高估一点，也不要让请求超窗口被服务端拒绝。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var ascii = 0
        for (ch in text) {
            if (ch.code < 0x80) ascii++ else wide++
        }
        return wide + (ascii + 3) / 4
    }

    fun tokensOf(message: ChatMessage): Int {
        var tokens = PER_MESSAGE_OVERHEAD +
            estimateTokens(message.text) +
            estimateTokens(message.reasoningItem.orEmpty()) +
            message.images.size * TOKENS_PER_IMAGE
        for (call in message.toolCalls) {
            tokens += 8 + estimateTokens(call.arguments) + estimateTokens(call.result.orEmpty())
        }
        return tokens
    }

    fun requestBytesOf(message: ChatMessage): Int {
        var bytes = 64 +
            message.text.toByteArray(Charsets.UTF_8).size +
            message.reasoningItem.orEmpty().toByteArray(Charsets.UTF_8).size
        message.images.forEach { bytes += it.toByteArray(Charsets.UTF_8).size + 32 }
        for (call in message.toolCalls) {
            bytes += call.arguments.toByteArray(Charsets.UTF_8).size +
                call.result.orEmpty().toByteArray(Charsets.UTF_8).size + 64
        }
        return bytes
    }

    fun trim(
        messages: List<ChatMessage>,
        maxMessages: Int = MAX_MESSAGES,
        maxTokens: Int = budgetFor(DEFAULT_CONTEXT_TOKENS),
        maxBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val kept = ArrayDeque<ChatMessage>()
        var tokens = 0
        var bytes = 0

        for (message in messages.asReversed()) {
            val size = tokensOf(message)
            val sizeBytes = requestBytesOf(message)
            val full = kept.size >= maxMessages ||
                tokens + size > maxTokens ||
                bytes + sizeBytes > maxBytes
            if (kept.isNotEmpty() && full) break
            kept.addFirst(message)
            tokens += size
            bytes += sizeBytes
        }

        // 最后一句用户输入必须保留，否则模型不知道要回答什么
        val lastUser = messages.lastOrNull { it.role == Role.USER }
        if (lastUser != null && kept.none { it.id == lastUser.id }) {
            kept.addFirst(lastUser)
        }
        return kept.toList()
    }
}
