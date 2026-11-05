package app.pocketagent.android.agent

import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.ApiToolCall

/**
 * 一条回复里真正给用户看的正文，以及不属于正文的运行状态。
 *
 * ChatMessage 目前没有独立的 notice 字段；为了让通知能随会话持久化、同步，
 * 同时又不参与模型上下文，这里把通知放在正文末尾的隐藏 HTML 注释里。
 */
data class ReplyView(
    val body: String,
    val notices: List<String>,
) {
    val hasAutomaticLimitNotice: Boolean
        get() = notices.any(::isAutomaticLimitNotice)
}

private const val REPLY_NOTICE_OPEN = "<!--pocketagent-reply-notice:v1\n"
private const val REPLY_NOTICE_CLOSE = "\n-->"

fun encodeReplyText(body: String, notices: List<String>): String {
    val clean = notices.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (clean.isEmpty()) return body
    val visible = body.trimEnd()
    val prefix = if (visible.isBlank()) "" else "$visible\n\n"
    return prefix + REPLY_NOTICE_OPEN + clean.joinToString("\n") + REPLY_NOTICE_CLOSE
}

fun decodeReplyText(raw: String): ReplyView {
    val marker = raw.lastIndexOf(REPLY_NOTICE_OPEN)
    if (marker < 0 || !raw.endsWith(REPLY_NOTICE_CLOSE)) return ReplyView(raw, emptyList())

    val start = marker + REPLY_NOTICE_OPEN.length
    val end = raw.length - REPLY_NOTICE_CLOSE.length
    if (start > end) return ReplyView(raw, emptyList())

    val notices = raw.substring(start, end)
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return ReplyView(raw.substring(0, marker).trimEnd(), notices)
}

/** 这些是 harness 自动收尾/截断提示，不代表用户主动停止。 */
fun isAutomaticLimitNotice(text: String): Boolean =
    text.contains("已达到最大步数") || text.contains("模型达到输出上限")

/**
 * 兼容旧会话：旧版本既把上限提示拼成 `> ...` 正文，又把 stopped 错写成 true。
 */
fun isAutomaticLimitReply(raw: String): Boolean {
    val reply = decodeReplyText(raw)
    if (reply.hasAutomaticLimitNotice) return true
    return reply.hasLegacyAutomaticLimitBlock
}

/** 只识别旧版本拼进正文的 `> ...` 提示；新格式的 notice 有独立标记。 */
fun isLegacyAutomaticLimitReply(raw: String): Boolean =
    decodeReplyText(raw).hasLegacyAutomaticLimitBlock

private val ReplyView.hasLegacyAutomaticLimitBlock: Boolean
    get() = notices.isEmpty() && body.lineSequence().any { line ->
        val text = line.trimStart()
        text.startsWith("> 已达到最大步数（") || text.startsWith("> 收尾时模型没有返回内容")
    }

object MessageMapper {
    fun toApiMessages(messages: List<ChatMessage>): List<ApiMessage> = buildList {
        messages.forEach { m ->
            // 本地记录（斜杠命令回显、/undo 提示等）不是对话内容，任何角色都不回灌。
            // 旧版本存下的命令回显没有 model=local 标记，这里按“以 / 开头”兜底。
            if (m.model == LOCAL_NOTICE_MODEL) return@forEach
            if (m.role == Role.USER && m.text.trim().startsWith("/")) return@forEach
            when (m.role) {
                Role.USER -> add(ApiMessage(role = "user", content = m.text, images = m.images))
                Role.ASSISTANT -> {
                    val content = decodeReplyText(m.text).body
                    // 桌面同步过来的审批复核 JSON 是内部记录，不是模型真正的回答。
                    // 回灌给模型会让它把这串 JSON 当成自己上一轮的输出去模仿，直接过滤掉。
                    if (m.toolCalls.isEmpty() && ApprovalRecord.parse(content) != null) {
                        return@forEach
                    }
                    val calls = m.toolCalls.map { ApiToolCall(it.id, it.name, it.arguments) }
                    if (content.isNotBlank() || calls.isNotEmpty()) {
                        add(
                            ApiMessage(
                                role = "assistant",
                                content = content,
                                toolCalls = calls,
                                reasoningItem = m.reasoningItem,
                            ),
                        )
                    }
                    m.toolCalls.forEach { rec ->
                        add(
                            ApiMessage(
                                role = "tool",
                                content = rec.result
                                    ?: "This tool call produced no recorded result; the turn ended before it completed.",
                                toolCallId = rec.id,
                            ),
                        )
                    }
                }
                Role.TOOL -> Unit
            }
        }
    }
}

/** appendLocal() 写入的消息用这个 model 标记；只在本机显示，不回灌给模型。 */
const val LOCAL_NOTICE_MODEL = "local"

/**
 * 把一次「用户输入 → 最终回答」的完整过程跑完，返回落库用的 assistant 消息。
 * 过程中通过 onMessage 回传实时状态（含工具卡片），UI 直接渲染。
 */
class ChatEngine(private val runner: AgentRunner) {

    suspend fun runOnce(
        provider: Provider,
        apiKey: String,
        history: List<ChatMessage>,
        cacheKey: String? = null,
        historyBudgetTokens: Int? = null,
        steering: TurnSteering? = null,
        onSteeringApplied: suspend (List<ChatMessage>) -> Unit = {},
        onMessage: (ChatMessage) -> Unit,
    ): ChatMessage {
        var text = StringBuilder()
        // 工具调用前那一步的说明属于“过程”，不能拼进最终答案。
        // 桌面 Codex 把它们当独立的进度更新显示，App 统一收进折叠的思考/过程区。
        val progress = StringBuilder()
        var reasoning = StringBuilder()
        var reasoningItem: String? = null
        val notices = mutableListOf<String>()
        val tools = linkedMapOf<String, ToolCallRecord>()
        var tokensIn = 0
        var tokensOut = 0
        var messageId = java.util.UUID.randomUUID().toString()

        fun combinedReasoning(): String = when {
            progress.isEmpty() -> reasoning.toString()
            reasoning.isEmpty() -> progress.toString()
            else -> progress.toString() + "\n\n" + reasoning.toString()
        }

        fun snapshot(): ChatMessage = ChatMessage(
            id = messageId,
            role = Role.ASSISTANT,
            text = encodeReplyText(text.toString(), notices),
            reasoning = combinedReasoning(),
            reasoningItem = reasoningItem,
            toolCalls = tools.values.toList(),
            model = provider.model,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            // 这里只表示“用户主动停止”。步数用尽由 notice 表达，不能复用这个字段。
            stopped = false,
        )

        fun addNotice(value: String) {
            val clean = value.trim()
            if (clean.isNotEmpty() && clean !in notices) notices += clean
        }

        val result = runner.run(
            provider = provider,
            apiKey = apiKey,
            history = MessageMapper.toApiMessages(history),
            cacheKey = cacheKey,
            historyBudgetTokens = historyBudgetTokens,
            allowSubagents = true,
            steering = steering,
        ) { event ->
            when (event) {
                is AgentEvent.FollowUpsApplied -> {
                    // Close the completed tool segment BEFORE the real follow-up user messages.
                    // Discarded draft text is visible only in the folded process; replaying the
                    // conversation must not place old tool results after the new instruction.
                    if (text.isNotBlank()) {
                        if (progress.isNotEmpty()) progress.append("\n\n")
                        progress.append(text)
                    }
                    text = StringBuilder()
                    if (tools.isNotEmpty() || progress.isNotEmpty() || reasoning.isNotEmpty()) {
                        onMessage(snapshot().copy(
                            text = encodeReplyText("", listOf("已按补充要求调整后续步骤")),
                            tokensIn = 0,
                            tokensOut = 0,
                        ))
                    }
                    messageId = java.util.UUID.randomUUID().toString()
                    progress.setLength(0)
                    reasoning = StringBuilder()
                    reasoningItem = null
                    tools.clear()
                    onSteeringApplied(event.messages)
                }
                is AgentEvent.TextDelta -> {
                    text.append(event.text)
                    onMessage(snapshot())
                }
                is AgentEvent.ReasoningDelta -> {
                    reasoning.append(event.text)
                    onMessage(snapshot())
                }
                is AgentEvent.ReasoningItem -> reasoningItem = event.json
                is AgentEvent.Usage -> {
                    tokensIn += event.input
                    tokensOut += event.output
                }
                is AgentEvent.ToolStarted -> {
                    tools[event.callId] = ToolCallRecord(
                        id = event.callId,
                        name = event.name,
                        arguments = event.arguments,
                        status = ToolStatus.RUNNING,
                    )
                    onMessage(snapshot())
                }
                is AgentEvent.ToolWaitingApproval -> {
                    tools[event.callId] = (tools[event.callId] ?: ToolCallRecord(
                        event.callId, event.name, event.arguments,
                    )).copy(status = ToolStatus.PENDING)
                    onMessage(snapshot())
                }
                is AgentEvent.ToolDenied -> {
                    tools[event.callId] = tools[event.callId]?.copy(
                        status = ToolStatus.DENIED,
                        result = "DENIED: 用户拒绝执行",
                    ) ?: ToolCallRecord(
                        event.callId,
                        event.name,
                        "",
                        ToolStatus.DENIED,
                        "DENIED: 用户拒绝执行",
                    )
                    onMessage(snapshot())
                }
                is AgentEvent.ToolFinished -> {
                    tools[event.callId] = (tools[event.callId] ?: ToolCallRecord(
                        event.callId, event.name, "",
                    )).copy(
                        status = if (event.ok) ToolStatus.OK else ToolStatus.ERROR,
                        result = event.output,
                        durationMs = event.durationMs,
                    )
                    onMessage(snapshot())
                }
                is AgentEvent.Notice -> {
                    // 步数上限时 AgentRunner 会在同一 step 内再追一次收尾回答；
                    // 和调用工具前的说明分开，避免两句直接粘成一句。
                    if (event.text.trim() !in notices &&
                        event.text.contains("已达到最大步数") &&
                        text.isNotBlank() &&
                        !text.endsWith("\n\n")
                    ) {
                        text.append("\n\n")
                    }
                    addNotice(event.text)
                    onMessage(snapshot())
                }
                is AgentEvent.StepStarted -> {
                    // 每个 step 是独立的一次模型输出。上一步的正文其实是“我这就去查”这类进度说明，
                    // 挪进过程区；否则最终答案会变成 过程 + 过程 + 结论 的流水账。
                    if (text.isNotBlank()) {
                        val narrated = text.toString().trim()
                        if (narrated.isNotEmpty()) {
                            if (progress.isNotEmpty()) progress.append("\n\n")
                            progress.append(narrated)
                        }
                        text = StringBuilder()
                        onMessage(snapshot())
                    }
                }
            }
        }

        // Notice 不拼进正文，避免被当成模型回答，或被下一轮当成历史回答回灌。
        // 兜底：若最后一轮没有正文（例如中途被停止），至少把过程内容当正文显示，不要留空气泡。
        val body = text.toString().trimEnd().ifBlank { progress.toString().trimEnd() }
        if (result.stoppedByLimit && notices.none { isAutomaticLimitNotice(it) }) {
            addNotice("已达到最大步数（${provider.maxSteps.coerceIn(1, 64)}），本轮已自动收尾")
        }
        if (body.isBlank() && notices.isEmpty()) {
            addNotice("这轮没有产生最终的正文回答。")
            tools.values.lastOrNull { it.status == ToolStatus.RUNNING }?.let {
                addNotice("最后仍在执行：${it.name}")
            }
        }
        return snapshot().copy(
            text = encodeReplyText(body, notices),
            reasoningItem = result.reasoningItem ?: reasoningItem,
            tokensIn = result.tokensIn,
            tokensOut = result.tokensOut,
            stopped = false,
        )
    }
}
