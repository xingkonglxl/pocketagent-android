package app.pocketagent.android.agent

import app.pocketagent.android.net.ApiMessage

/**
 * 单轮工具循环里的上下文保护。
 *
 * 回合开始前已经由 HistoryWindow 裁剪过一次；但一次 fs_read / shell_exec / 网页抓取
 * 就可能在工具循环中途把请求顶过窗口。这里只在「完整工具批次结束、下一次模型请求之前」
 * 压缩，且以整轮 tool_calls + tool 结果为原子单位，绝不拆出孤立的 tool 消息。
 */
object MidTurnCompactor {
    const val SUMMARY_PREFIX = "（上下文压缩摘要：较早的工具记录已折叠）"

    private const val PER_MESSAGE_OVERHEAD = 4
    private const val TOKENS_PER_IMAGE = 1_000
    private const val PER_TOOL_CALL_OVERHEAD = 12
    private const val TRIGGER_PERCENT = 90
    private const val TARGET_PERCENT = 55
    private const val MAX_SUMMARY_CHARS = 6_000
    private const val SUMMARY_ITEM_CHARS = 240

    fun estimateTokens(messages: List<ApiMessage>): Int {
        var total = 0
        for (message in messages) {
            total += PER_MESSAGE_OVERHEAD +
                HistoryWindow.estimateTokens(message.content) +
                HistoryWindow.estimateTokens(message.reasoningItem.orEmpty()) +
                message.images.size * TOKENS_PER_IMAGE
            for (call in message.toolCalls) {
                total += PER_TOOL_CALL_OVERHEAD +
                    HistoryWindow.estimateTokens(call.id) +
                    HistoryWindow.estimateTokens(call.name) +
                    HistoryWindow.estimateTokens(call.arguments)
            }
        }
        return total
    }

    fun estimateBytes(messages: List<ApiMessage>): Int {
        var total = 0
        for (message in messages) {
            total += 64 +
                message.content.toByteArray(Charsets.UTF_8).size +
                message.reasoningItem.orEmpty().toByteArray(Charsets.UTF_8).size
            message.images.forEach { total += it.toByteArray(Charsets.UTF_8).size + 32 }
            for (call in message.toolCalls) {
                total += call.id.toByteArray(Charsets.UTF_8).size +
                    call.name.toByteArray(Charsets.UTF_8).size +
                    call.arguments.toByteArray(Charsets.UTF_8).size + 64
            }
        }
        return total
    }

    fun needsCompaction(
        messages: List<ApiMessage>,
        budgetTokens: Int,
        budgetBytes: Int = HistoryWindow.DEFAULT_MAX_REQUEST_BYTES,
    ): Boolean {
        if (messages.isEmpty()) return false
        val tokenLimit = budgetTokens.coerceAtLeast(1).toLong() * TRIGGER_PERCENT / 100
        val byteLimit = budgetBytes.coerceAtLeast(1).toLong() * TRIGGER_PERCENT / 100
        return estimateTokens(messages) >= tokenLimit || estimateBytes(messages) >= byteLimit
    }

    /**
     * 协议校验：assistant 声明的每个 tool call id 必须在同一批次里恰好有一条结果，
     * tool 消息不能没有 owner；图片 user 消息允许插在工具批次中间（兼容历史数据）。
     */
    fun isValid(messages: List<ApiMessage>): Boolean {
        val pending = LinkedHashSet<String>()
        for (message in messages) {
            when (message.role) {
                "assistant" -> {
                    if (pending.isNotEmpty()) return false
                    for (call in message.toolCalls) {
                        if (!pending.add(call.id)) return false
                    }
                }
                "tool" -> {
                    val id = message.toolCallId ?: return false
                    if (!pending.remove(id)) return false
                }
                "user" -> {
                    // 旧顺序里图片可能紧跟 assistant 插在多个 tool 结果之间。
                    if (pending.isNotEmpty() && message.images.isEmpty()) return false
                }
                else -> if (pending.isNotEmpty()) return false
            }
        }
        return pending.isEmpty()
    }

    /**
     * 返回压缩后的消息。不调用大模型：摘要由用户请求、助手正文和工具台账确定性拼成，
     * 既不会递归触发工具循环，也不会把提示注入内容当成新的指令。
     */
    fun compact(
        messages: List<ApiMessage>,
        budgetTokens: Int,
        budgetBytes: Int = HistoryWindow.DEFAULT_MAX_REQUEST_BYTES,
        keepRecentUnits: Int = 2,
    ): List<ApiMessage> {
        if (messages.isEmpty()) return messages
        val targetTokens = (budgetTokens.coerceAtLeast(1).toLong() * TARGET_PERCENT / 100)
            .coerceAtLeast(1_000L).toInt()
        val targetBytes = (budgetBytes.coerceAtLeast(1).toLong() * TARGET_PERCENT / 100)
            .coerceAtLeast(1_000L).toInt()

        val units = splitUnits(messages)
        if (units.isEmpty()) return messages

        val pinnedIndex = units.indexOfFirst { unit ->
            unit.messages.size == 1 &&
                unit.messages[0].role == "user" &&
                unit.messages[0].images.isEmpty() &&
                !unit.messages[0].content.startsWith(SUMMARY_PREFIX)
        }
        val pinned = if (pinnedIndex >= 0) shrinkUnit(units[pinnedIndex], 1_200) else null

        val kept = ArrayDeque<Unit>()
        val keptIndices = mutableSetOf<Int>()
        var tokens = pinned?.let { estimateTokens(it.messages) } ?: 0
        var bytes = pinned?.let { estimateBytes(it.messages) } ?: 0
        var recent = 0
        for (index in units.indices.reversed()) {
            if (index == pinnedIndex) continue
            if (recent >= keepRecentUnits) break
            val unit = units[index]
            val shrunk = shrinkUnit(unit, if (recent == 0) 6_000 else 2_000)
            val size = estimateTokens(shrunk.messages)
            val sizeBytes = estimateBytes(shrunk.messages)
            if (kept.isNotEmpty() && (tokens + size > targetTokens || bytes + sizeBytes > targetBytes)) break
            kept.addFirst(shrunk)
            keptIndices += index
            tokens += size
            bytes += sizeBytes
            recent++
        }

        // 至少保留最后一条完整工具轮/最后一条消息，否则模型看不到当前步骤。
        if (kept.isEmpty()) {
            val lastIndex = units.lastIndex
            if (lastIndex != pinnedIndex) {
                kept.addLast(shrinkUnit(units[lastIndex], 1_200))
                keptIndices += lastIndex
            }
        }

        val dropped = units.indices.filter { it != pinnedIndex && it !in keptIndices }.map { units[it] }
        val summaryLimit = (targetTokens / 5).coerceIn(600, MAX_SUMMARY_CHARS)
        val summary = buildSummary(dropped, summaryLimit)
        val result = mutableListOf<ApiMessage>()
        pinned?.let { result += it.messages }
        if (summary.isNotBlank()) {
            result += ApiMessage(role = "user", content = summary)
        }
        kept.forEach { result += it.messages }

        val normalized = fitToBudget(dropExtraImages(result), targetTokens, targetBytes)
        if (isValid(normalized)) return normalized

        // 极少数历史本身就不配对：保守回退到「原任务 + 最近一个完整批次」。
        val fallback = mutableListOf<ApiMessage>()
        pinned?.let { fallback += it.messages }
        fallback += shrinkUnit(units.last(), 1_200).messages
        return if (isValid(fallback)) fallback else messages
    }

    private data class Unit(val messages: List<ApiMessage>)

    private fun splitUnits(messages: List<ApiMessage>): List<Unit> {
        val units = mutableListOf<Unit>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.role == "assistant" && message.toolCalls.isNotEmpty()) {
                val ids = message.toolCalls.map { it.id }.toMutableSet()
                val batch = mutableListOf(message)
                index++
                while (index < messages.size) {
                    val next = messages[index]
                    val isResult = next.role == "tool" && next.toolCallId != null && ids.remove(next.toolCallId)
                    val isImage = next.role == "user" && next.images.isNotEmpty()
                    if (!isResult && !isImage) break
                    batch += next
                    index++
                }
                units += Unit(batch)
            } else {
                units += Unit(listOf(message))
                index++
            }
        }
        return units
    }

    private fun shrinkUnit(unit: Unit, toolChars: Int): Unit {
        val shrunk = unit.messages.map { message ->
            when {
                message.role == "tool" -> message.copy(content = clip(message.content, toolChars))
                message.role == "assistant" && message.toolCalls.isNotEmpty() ->
                    message.copy(
                        content = clip(message.content, toolChars),
                        reasoningItem = message.reasoningItem?.let { clip(it, 2_000) },
                        toolCalls = message.toolCalls.map { call ->
                            call.copy(arguments = clip(call.arguments, 2_000))
                        },
                    )
                else -> message
            }
        }
        return Unit(shrunk)
    }

    private fun buildSummary(units: List<Unit>, maxChars: Int): String {
        if (units.isEmpty()) return ""
        val builder = StringBuilder(SUMMARY_PREFIX)
        val tools = mutableListOf<String>()
        var assistantNotes = 0
        for (unit in units) {
            for (message in unit.messages) {
                when (message.role) {
                    "user" -> {
                        if (message.images.isEmpty() && !message.content.startsWith(SUMMARY_PREFIX)) {
                            builder.append("\n- 用户请求：").append(clean(message.content).take(SUMMARY_ITEM_CHARS))
                        }
                    }
                    "assistant" -> {
                        if (message.content.isNotBlank() && assistantNotes < 6) {
                            builder.append("\n- 助手进展：").append(clean(message.content).take(SUMMARY_ITEM_CHARS))
                            assistantNotes++
                        }
                        for (call in message.toolCalls) {
                            val result = unit.messages.firstOrNull {
                                it.role == "tool" && it.toolCallId == call.id
                            }?.content.orEmpty()
                            tools += "  - ${call.name}(${clip(clean(call.arguments), 160)}) " +
                                "=> ${clip(clean(result), 200)}"
                        }
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            builder.append("\n- 工具台账：")
            tools.take(24).forEach { builder.append('\n').append(it) }
            if (tools.size > 24) builder.append("\n  - 其余 ${tools.size - 24} 条已省略")
        }
        return clip(builder.toString(), maxChars)
    }

    /** 摘要之后仍可能超目标线，逐级截短正文和工具结果，保留消息骨架与配对。 */
    private fun fitToBudget(
        messages: List<ApiMessage>,
        targetTokens: Int,
        targetBytes: Int,
    ): List<ApiMessage> {
        var current = messages
        for (cap in intArrayOf(4_000, 1_500, 600, 200)) {
            if (estimateTokens(current) <= targetTokens && estimateBytes(current) <= targetBytes) {
                return current
            }
            current = current.map { message ->
                when (message.role) {
                    "tool" -> message.copy(content = clip(message.content, cap))
                    "assistant" -> message.copy(
                        content = clip(message.content, cap),
                        reasoningItem = message.reasoningItem?.let { clip(it, cap) },
                    )
                    "user" -> if (message.content.startsWith(SUMMARY_PREFIX)) {
                        message.copy(content = clip(message.content, cap))
                    } else {
                        message
                    }
                    else -> message
                }
            }
        }
        return current
    }

    private fun dropExtraImages(messages: List<ApiMessage>): List<ApiMessage> {
        val newestImages = messages.indexOfLast { it.images.isNotEmpty() }
        if (newestImages < 0) return messages
        return messages.mapIndexedNotNull { index, message ->
            when {
                message.images.isEmpty() -> message
                index == newestImages -> message
                message.role == "user" && message.content.startsWith("（上面") -> null
                else -> message.copy(images = emptyList())
            }
        }
    }

    private fun clean(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').trim()

    private fun clip(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        if (maxChars <= 40) return text.take(maxChars)
        val head = maxChars * 2 / 3
        val tail = maxChars - head - 20
        return text.take(head) + "\n…（中间省略）…\n" + text.takeLast(tail.coerceAtLeast(1))
    }
}
