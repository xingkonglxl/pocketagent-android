package app.pocketagent.android.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProviderKind {
    /** OpenAI 兼容的 /chat/completions，DeepSeek 等走这条 */
    CHAT_COMPLETIONS,

    /** OpenAI 的 /responses，Codex 模型走这条 */
    RESPONSES,

    /** Anthropic 的 /v1/messages，Claude 走这条 */
    ANTHROPIC,
}

@Serializable
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: ProviderKind = ProviderKind.CHAT_COMPLETIONS,
    val baseUrl: String,
    val model: String,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Double? = null,
    /** Claude 必填（其它家也可用作上限）。4096 太短，长分析会被截断，默认给到 8192。 */
    val maxOutputTokens: Int = 8192,
    /** none/low/medium/high/xhigh/max；null = 用服务端默认 */
    val reasoningEffort: String? = null,
    /**
     * 用户是否亲手选过思考档位（含「默认」）。
     * 没选过时由模型目录挑最接近桌面 Codex 的档位，见 ProviderStore.sanitized()。
     */
    val reasoningPinned: Boolean = false,
    /**
     * Responses 的 text.verbosity：low/medium/high；null = 不发，用服务端默认
     * （桌面 Codex 也不发这个字段，写死 low 会让回答明显变短、分析变浅）。
     */
    val verbosity: String? = null,
    /** Responses 的 service_tier；桌面 /fast 对应 priority。null = 服务端默认 */
    val serviceTier: String? = null,
    /** 单轮里最多跑多少次「模型 → 工具 → 回灌」；桌面 Codex 会一直跑到任务完成 */
    val maxSteps: Int = 24,
    val toolsEnabled: Boolean = true,
    val autoApprove: Boolean = false,
    /**
     * 免确认的工具类别，见 [app.pocketagent.android.agent.ToolPermissions]。
     * 默认只放行只读操作；写文件、删文件、执行命令仍逐次确认。
     */
    val permissions: Set<String> = app.pocketagent.android.agent.ToolPermissions.defaults,
)

/** v0.1 最早写进 providers.json 的提示词，加载时自动升级成 [DEFAULT_SYSTEM_PROMPT]。 */
const val LEGACY_SYSTEM_PROMPT: String =
    "你是 PocketAgent，一个在手机上工作的编程助手。" +
        "回答先给结论，再给必要的细节；代码用 markdown 代码块。" +
        "需要读写文件或访问网络时，调用提供的工具，不要凭空猜测文件内容。"

/** 0.1.1 之前写进 providers.json 的默认提示词，加载时迁移到新的 DEFAULT_SYSTEM_PROMPT。 */
const val PREVIOUS_SYSTEM_PROMPT: String =
    "你是 PocketAgent，一个跑在手机上的编程/运维助手。\n" +
        "1. 闲聊、解释概念、给建议、写代码片段：直接回答，**不要调用工具**。\n" +
        "2. 只有用户明确要求查看/修改/执行真实机器上的东西，或答案必须依赖真实文件内容、命令输出时才调用工具。\n" +
        "3. 工具够用就停：拿到结果后马上给结论，不要为了「确认一下」反复试探、不要顺手写文件。\n" +
        "4. 回答先给结论，再给必要细节；代码用 markdown 代码块。\n" +
        "5. 用户只问「好了没 / 能用了么」这类进度问题，就用一句话回答当前状态。"

const val DEFAULT_SYSTEM_PROMPT: String =
    "你是 PocketAgent，一个运行在 Android 手机上的通用 agent，工作方式与桌面 Codex 对齐。\n" +
        "先理解用户真正要什么；需要行动就做到底并验证，不要只给计划或半截结论。\n" +
        "需要实时事实、真实文件或远端状态时先调用工具，工具返回后继续推理到最终答案。\n" +
        "默认中文回答，先给结论，再给关键证据、具体路径和验证方法；不确定就直说。"

object ProviderPresets {
    fun deepSeek() = Provider(
        name = "DeepSeek",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-chat",
    )

    fun openAi() = Provider(
        name = "Codex / OpenAI",
        kind = ProviderKind.RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-5-codex",
    )

    fun custom() = Provider(
        name = "",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = "",
        model = "",
        systemPrompt = DEFAULT_SYSTEM_PROMPT,
    )

    /** 中转站（OpenAI 兼容）：DeepSeek 与 Codex 共用同一把 key，只换协议。 */
    /** 发布版不带中转站域名；只有 -PrelayBaseUrl=... 构建时才启用这三个预设 */
    fun relayEnabled(): Boolean = app.pocketagent.android.BuildConfig.RELAY_BASE_URL.isNotBlank()

    fun isRelay(url: String): Boolean =
        relayEnabled() && url.startsWith(app.pocketagent.android.BuildConfig.RELAY_BASE_URL)

    fun relayChat() = Provider(
        name = "DeepSeek 中转",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = app.pocketagent.android.BuildConfig.RELAY_BASE_URL + "/v1",
        // 与本地 codex 配置一致（~/.codex/config.toml 里的 model）
        model = "deepseek-v4-flash",
        // 桌面 config.toml 对同一模型设的是 max；服务端默认只有 low，差异会非常明显
        reasoningEffort = "max",
    )

    fun relayResponses() = Provider(
        name = "Codex 中转",
        kind = ProviderKind.RESPONSES,
        baseUrl = app.pocketagent.android.BuildConfig.RELAY_BASE_URL + "/v1",
        // 中转站没有 gpt-5-codex（会 503 No available channel），这几个是实测可用的
        model = "gpt-6-astra",
    )

    fun relayClaude() = Provider(
        name = "Claude 中转",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = app.pocketagent.android.BuildConfig.RELAY_BASE_URL,
        // 中转站的 claude-sonnet-4-5 没开 channel，claude-opus-4-8 实测可用
        model = "claude-opus-4-8",
    )

    fun claudeOfficial() = Provider(
        name = "Claude（官方）",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        model = "claude-sonnet-4-5",
    )
}

@Serializable
enum class Role { USER, ASSISTANT, TOOL }

@Serializable
enum class ToolStatus { PENDING, APPROVED, DENIED, RUNNING, OK, ERROR }

@Serializable
data class ToolCallRecord(
    val id: String,
    val name: String,
    val arguments: String,
    val status: ToolStatus = ToolStatus.PENDING,
    val result: String? = null,
    val durationMs: Long = 0,
)

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String = "",
    /** 图片以 data URL 保存（data:image/png;base64,...），只保存在本机 */
    val images: List<String> = emptyList(),
    /** deepseek-reasoner 之类的思考过程，只在界面上折叠展示，不回传给模型 */
    val reasoning: String = "",
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val model: String? = null,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val stopped: Boolean = false,
    /**
     * Responses API 的 reasoning item 原文，跨轮也必须原样回传。
     * 默认 null 保证旧会话 JSON 仍能反序列化；Chat Completions / Anthropic 不使用。
     */
    val reasoningItem: String? = null,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val providerId: String? = null,
    /** /goal 设定的目标；完成为 null */
    val goal: String? = null,
    /** /goal pause 之后为 true（目标还在，但不推进） */
    val goalPaused: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 这条会话从哪来：phone = 手机新建；mac = 从 Mac 的 Codex 同步过来 */
    val origin: String = "phone",
    /** 使用远端 Codex harness 时记住它自己的 session id，便于后续 resume。 */
    val remoteThreadId: String? = null,
    /**
     * [remoteThreadId] 属于哪台远端主机。
     *
     * session id 不是跨机器通用的；如果只记 id，从 Mac 同步后把 /workspace 切到另一台机器，
     * 就可能去 resume 一台机器上根本不存在的会话。旧数据没有这个字段时仍按“可尝试”处理。
     */
    val remoteHostId: String? = null,
    /** 这个对话自己的工作区；null 表示手机本地，不能沿用上一个对话的远端选择。 */
    val workspaceHostId: String? = null,
    val workspaceDir: String? = null,
) {
    fun titleFromFirstMessage(): Conversation {
        if (title != "新对话") return this
        // 斜杠命令（/model 等）和本地提示（model=local）不代表对话主题，标题要跳过
        val first = messages.firstOrNull {
            it.role == Role.USER && it.model != "local" && !it.text.trim().startsWith("/")
        } ?: return this
        val t = first.text.replace(Regex("\\s+"), " ").trim().take(24)
        return if (t.isBlank()) this else copy(title = t)
    }

    /** 读取时修正历史遗留的坏标题：`/model` 这类命令，或者一直没设上的「新对话」。 */
    fun normalizedTitle(): Conversation {
        val bad = title.isBlank() || title == "新对话" || title.trim().startsWith("/")
        if (!bad) return this
        val derived = copy(title = "新对话").titleFromFirstMessage()
        return if (derived.title == "新对话") this else derived
    }
}
