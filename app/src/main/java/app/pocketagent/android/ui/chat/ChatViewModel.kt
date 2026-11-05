package app.pocketagent.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pocketagent.android.AppContainer
import app.pocketagent.android.agent.TurnSteering
import app.pocketagent.android.agent.SlashCommands
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.CatalogModel
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.ModelCatalog
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.Role
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.LlmClients
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.pocketagent.android.ui.i18n.L10n
import java.util.UUID

data class QueuedSendPreview(
    val id: String,
    val preview: String,
    val imageCount: Int,
)

data class ChatUiState(
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val provider: Provider? = null,
    val running: Boolean = false,
    val error: String? = null,
    /** 尚未被当前 harness 接受的补充要求。 */
    val queuedSends: List<QueuedSendPreview> = emptyList(),
    val appliedFollowUps: Int = 0,
    val unconfirmedFollowUps: Boolean = false,
    val needsKey: Boolean = false,
    val notice: String? = null,
    /** /fast：使用服务端 priority 档；工具仍开启，和桌面 Codex 的 fast 语义一致 */
    val fastMode: Boolean = false,
    /** 远端 harness 是否显示思考过程；默认关闭以节省手机流量。 */
    val showRemoteThinking: Boolean = false,
    /** 命令面板（输入框左侧的 / 按钮打开） */
    val commandSheet: Boolean = false,
    /** /model 的模型选择列表 */
    val modelPicker: Boolean = false,
    /** 第一步选好了模型，正在给这个模型挑思考档位（null = 还在选模型） */
    val pendingModel: String? = null,
    /** 内置模型目录 */
    val catalog: List<CatalogModel> = emptyList(),
    /** /goal 设的目标 */
    val goal: String? = null,
    /** 目标是否被 /goal pause 暂停 */
    val goalPaused: Boolean = false,
    /** /workspace 的工作区选择面板 */
    val workspaceSheet: Boolean = false,
    /** /reasoning 的档位选择面板 */
    val reasoningPicker: Boolean = false,
    /** /approvals 的审批策略面板 */
    val approvalsPicker: Boolean = false,
    /** /permissions 的工具权限面板 */
    val permissionsSheet: Boolean = false,
    /** /goal 的编辑面板 */
    val goalEditor: Boolean = false,
    /** 当前工作区（手机本地 / 某台远端机器） */
    val workspaceLabel: String = "",
    val workspaceHostId: String? = null,
    val workspaceDir: String = "~/pocketagent",
    /** 当前对话绑定的远端 Codex session；用于在 /workspace 面板里明确显示续接状态。 */
    val remoteThreadId: String? = null,
    val remoteHostId: String? = null,
    /** 可选的远端主机 */
    val hosts: List<app.pocketagent.android.remote.SshHost> = emptyList(),
    /** 每个模型最近一次实测结果（/test models 写入），/model 列表里标 ✅/❌ */
    val probes: Map<String, app.pocketagent.android.data.ModelProbe> = emptyMap(),
    /** 用户自己加的模型 id */
    val customModels: List<String> = emptyList(),
)

private const val AUTO_COMPACT_KEEP_RECENT = 6
/** 流式增量合并窗口：约 20 次/秒，避免每个 token 都触发整条消息重绘。 */
private const val STREAM_UPDATE_INTERVAL_MS = 50L
private const val QUEUED_PREVIEW_LENGTH = 80

/** /fast 只切换服务端档位，不改变工具、步数或模型能力。 */
internal fun withFastMode(provider: Provider, fast: Boolean): Provider =
    provider.copy(
        serviceTier = if (fast && provider.kind == app.pocketagent.android.data.ProviderKind.RESPONSES) {
            "priority"
        } else {
            null
        },
    )

class ChatViewModel(
    private val container: AppContainer,
    private val conversationId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state = _state.asStateFlow()

    val approvals = container.approvalGate.pending
    private var job: Job? = null
    private var goalContinuation: Job? = null
    private var heldSteeringIds: Set<String> = emptySet()
    private val detectedHarnessHosts = mutableSetOf<app.pocketagent.android.remote.SshHost>()
    private val turnSteering = TurnSteering()
    /** 检测失败时保留本次输入，重试不能误删/重发上一轮。 */
    private var pendingHarnessSend: Pair<String, List<String>>? = null
    private var conversation: Conversation = Conversation(id = conversationId)

    /**
     * 会话落盘走单消费者 + 合并最新值：
     * 之前每次 persist 都各自 launch 一个 IO 协程，乱序完成会把旧快照写回去
     * （典型症状：/goal done 之后重启又冒出旧目标）。
     */
    private val persistChannel =
        kotlinx.coroutines.channels.Channel<Conversation>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        viewModelScope.launch {
            turnSteering.pending.collect { publishQueuedSends() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (snapshot in persistChannel) {
                runCatching { container.conversationStore.upsert(snapshot) }
            }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val (conv, providers) = withContext(Dispatchers.IO) {
                val c = container.conversationStore.load().firstOrNull { it.id == conversationId }
                    ?: Conversation(id = conversationId)
                val list = container.providerStore.load()
                c to list
            }
            // 工作区属于会话自己，不能再从上一个会话的全局选择继承。
            // 空会话和 workspaceHostId=null 的会话都回到手机本地。
            // 老会话可能只有 remoteThreadId/remoteHostId，没有后来新增的 workspaceHostId。
            // 这类会话本来就绑定过远端原生 session，升级后不能悄悄退回本地 harness。
            val resolvedHost = conv.workspaceHostId?.takeIf { it.isNotBlank() }
                ?: workspaceHostFromRemoteSession(conv.remoteThreadId, conv.remoteHostId)
            val resolvedDir = if (resolvedHost == null) {
                ""
            } else {
                conv.workspaceDir.orEmpty().ifBlank {
                    container.remoteManager.host(resolvedHost)?.workspaceDir.orEmpty()
                }
            }
            conversation = conv.copy(
                workspaceHostId = resolvedHost,
                workspaceDir = resolvedDir.ifBlank { null },
            )
            container.settings.remoteWorkspaceHostId = resolvedHost
            container.settings.remoteWorkspaceDir = resolvedDir
            if (conversation != conv) {
                withContext(Dispatchers.IO) { container.conversationStore.upsert(conversation) }
            }
            var chosen = providers.firstOrNull { it.id == conv.providerId }
                ?: providers.firstOrNull { it.id == container.settings.lastProviderId }
                ?: providers.firstOrNull()

            // 自愈：老数据里 model 可能被清空，空模型发出去只能靠服务端瞎猜，这里补回默认值
            if (chosen != null && chosen.model.isBlank()) {
                val repaired = chosen.copy(model = defaultModelFor(chosen))
                chosen = repaired
                withContext(Dispatchers.IO) { container.providerStore.upsert(repaired) }
            }
            val list = if (chosen == null) providers else providers.map { if (it.id == chosen.id) chosen else it }
            if (chosen != null && chosen.id != conv.providerId) {
                conversation = conversation.copy(providerId = chosen.id)
            }
            _state.value = ChatUiState(
                title = conv.title,
                messages = conv.messages,
                providers = list,
                provider = chosen,
                catalog = catalogWithCustom(),
                goal = conv.goal,
                goalPaused = conv.goalPaused,
                showRemoteThinking = container.settings.showRemoteThinking,
                workspaceLabel = container.workspace.label,
                workspaceHostId = resolvedHost,
                workspaceDir = resolvedDir,
                remoteThreadId = conv.remoteThreadId,
                remoteHostId = conv.remoteHostId,
                hosts = container.remoteManager.hosts(),
                probes = container.modelProbeStore.load(),
            )
        }
    }

    private fun defaultModelFor(provider: Provider): String = when {
        app.pocketagent.android.data.ProviderPresets.isRelay(provider.baseUrl) -> "deepseek-v4-flash"
        provider.kind == app.pocketagent.android.data.ProviderKind.ANTHROPIC -> "claude-sonnet-4-5"
        provider.kind == app.pocketagent.android.data.ProviderKind.RESPONSES -> "gpt-5-codex"
        else -> "deepseek-chat"
    }

    fun selectProvider(id: String) {
        val provider = _state.value.providers.firstOrNull { it.id == id } ?: return
        conversation = conversation.copy(providerId = id)
        container.settings.lastProviderId = id
        _state.update { it.copy(provider = provider, needsKey = false) }
        persist()
    }

    /** /model 里点某个具体模型：只改模型，不动来源（同一把 key 可以跑所有模型）。 */
    /** 第一步：选模型。有档位就进第二步让用户挑；没有档位直接生效。 */
    fun pickModel(modelId: String) {
        val entry = _state.value.catalog.firstOrNull { it.id == modelId }
        if (entry == null || entry.reasoningLevels.isEmpty()) {
            applyModel(modelId, null)
        } else {
            // 从 /model 文字命令进来时也要把面板打开，否则用户看不到第二步
            _state.update { it.copy(pendingModel = modelId, modelPicker = true) }
        }
    }

    /** 第二步：给刚选的模型定档位，level = null 表示用服务端默认。 */
    fun confirmModelLevel(level: String?) {
        val modelId = _state.value.pendingModel ?: return
        applyModel(modelId, level)
    }

    fun backToModelList() = _state.update { it.copy(pendingModel = null) }

    private fun applyModel(modelId: String, level: String?) {
        val provider = _state.value.provider ?: return
        val levels = _state.value.catalog.firstOrNull { it.id == modelId }?.reasoningLevels.orEmpty()
        val effort = level?.takeIf { levels.isEmpty() || it in levels }
        val kind = ModelCatalog.kindFor(provider.baseUrl, provider.kind, modelId)
        val updated = provider.copy(model = modelId, reasoningEffort = effort, kind = kind)
        applyProvider(updated)
        _state.update { it.copy(modelPicker = false, pendingModel = null) }
        appendLocal(
            "已切换到 ${updated.model}" +
                (updated.reasoningEffort?.let { e -> " · 思考强度 ${ModelCatalog.label(e)}" } ?: " · 服务端默认档位") +
                "\n可用档位：" + levels.joinToString(" / ") { ModelCatalog.label(it) }.ifBlank { "（该模型不可调）" },
        )
    }

    /** 改当前模型的思考强度。 */
    fun dismissReasoningPicker() = _state.update { it.copy(reasoningPicker = false) }

    fun dismissApprovalsPicker() = _state.update { it.copy(approvalsPicker = false) }

    fun dismissPermissionsSheet() = _state.update { it.copy(permissionsSheet = false) }

    /** 权限面板里切某一类：立即写回当前来源，下一次工具调用就按新策略走。 */
    fun togglePermission(category: String, allow: Boolean) {
        val provider = _state.value.provider ?: return
        if (category !in app.pocketagent.android.agent.ToolPermissions.all) return
        val next = if (allow) provider.permissions + category else provider.permissions - category
        applyProvider(provider.copy(permissions = next))
    }

    fun dismissGoalEditor() = _state.update { it.copy(goalEditor = false) }

    /** 目标面板里「保存」：填了就设，留空就当成清除 */
    fun saveGoal(text: String) {
        _state.update { it.copy(goalEditor = false) }
        val clean = text.trim()
        if (clean.isEmpty()) {
            handleGoalCommand("done")
        } else {
            handleGoalCommand(clean)
        }
    }

    /** 目标条上的「继续」：再推一轮 */
    fun continueGoal() {
        val goal = conversation.goal
        if (goal.isNullOrBlank()) {
            appendLocal(L10n.t("还没有目标，先发 /goal 你的目标。"))
            return
        }
        if (conversation.goalPaused) {
            conversation = conversation.copy(goalPaused = false)
            _state.update { it.copy(goalPaused = false) }
            persist()
        }
        kickoffGoal(goal)
    }

    /** 让 agent 真的朝目标干活（而不是只记一条目标） */
    private fun kickoffGoal(goal: String) {
        if (job != null || _state.value.running) {
            enqueueSend("目标更新为：$goal。请在安全节点按新目标调整后续步骤；完成后单独一行输出 GOAL_DONE。", emptyList())
            return
        }
        send(
            "【目标】$goal\n\n" +
                "现在开始朝这个目标干活：\n" +
                "1) 先用 3-5 步列出计划；\n" +
                "2) 立刻执行第一步，需要工具就直接调用，不用再问我计划；\n" +
                "3) 每完成一步用一句话说明进展；\n" +
                "4) 全部完成后给出结果，并在最后单独一行输出：GOAL_DONE（App 会自动结束 /goal）。",
        )
    }

    /**
     * 目标没完成就自动接着跑：桌面 Codex 的 goal 也是这样，不会一轮结束就停下。
     * 最多连续 12 次，避免模型卡住时无限烧 token。
     */
    private fun continueGoalIfNeeded(done: Boolean) {
        val goal = conversation.goal
        if (done || goal.isNullOrBlank() || conversation.goalPaused || heldSteeringIds.isNotEmpty()) return
        if (goalAutoContinues >= MAX_GOAL_AUTO_CONTINUES) {
            appendLocal("目标自动继续已达 $MAX_GOAL_AUTO_CONTINUES 次，先停在这里；发 /goal resume 可再继续。")
            return
        }
        goalAutoContinues++
        val round = goalAutoContinues
        goalContinuation?.cancel()
        goalContinuation = viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            if (job != null || _state.value.running || turnSteering.peek().isNotEmpty()) return@launch
            goalContinuation = null
            appendLocal("目标未完成，自动继续（第 $round 次）")
            autoContinuing = true
            send(
                "继续推进目标（第 $round 次）：不要重复已经完成的事，直接做下一步；" +
                    "全部完成后在最后一行输出 GOAL_DONE。",
            )
            autoContinuing = false
        }
    }

    /** 目标面板里「完成」 */
    fun finishGoal() {
        _state.update { it.copy(goalEditor = false) }
        handleGoalCommand("done")
    }

    /** 审批面板里点一项 */
    fun pickApprovals(auto: Boolean) {
        _state.update { it.copy(approvalsPicker = false) }
        handleApprovalsCommand(if (auto) "auto" else "ask")
    }

    /** 档位面板里点一项 */
    fun pickReasoningLevel(level: String?) {
        _state.update { it.copy(reasoningPicker = false) }
        setReasoning(level ?: "default")
    }

    fun setReasoning(level: String) {
        val provider = _state.value.provider ?: return
        // 选过就钉住：包括选「服务端默认」，之后不再被目录默认值覆盖
        val updated = provider.copy(
            reasoningEffort = level.takeUnless { it == "default" },
            reasoningPinned = true,
        )
        applyProvider(updated)
        appendLocal(
            "${updated.model} 的思考强度：${level.takeUnless { it == "default" }?.let { ModelCatalog.label(it) } ?: "服务端默认"}",
        )
    }

    /** 内置目录 + 用户自己加的模型（/model 里可以新增，中转站上新模型不用等发版） */
    private fun catalogWithCustom(): List<CatalogModel> {
        val hidden = runCatching { container.hiddenModelStore.load() }.getOrDefault(emptyList()).toSet()
        val builtin = container.modelCatalog.filterNot { it.id in hidden }
        val custom = container.customModelStore.load().filterNot { it in hidden }
        val extra = custom
            .filter { id -> builtin.none { it.id == id } }
            .map { id ->
                CatalogModel(
                    id = id,
                    displayName = id,
                    inRelay = true,
                    reasoningLevels = ModelCatalog.defaultLevels,
                )
            }
        return builtin + extra
    }

    /** /model 面板里「添加自定义模型」：先测通再加 */
    fun addCustomModel(id: String) {
        val clean = id.trim().filter { it.isLetterOrDigit() || it in "-._:/+" }
        if (clean.isEmpty() || job != null) return
        if (container.modelCatalog.any { it.id.equals(clean, true) }) {
            appendLocal("「$clean」是内置模型，直接 /model 里选就行")
            return
        }
        if (_state.value.customModels.any { it.equals(clean, true) }) {
            appendLocal("「$clean」已经在列表里了")
            return
        }
        val provider = _state.value.provider ?: run {
            appendLocal(L10n.t("先选一个来源"))
            return
        }
        val key = container.secretStore.get(provider.id).orEmpty()
        job = viewModelScope.launch {
            _state.update { it.copy(running = true) }
            appendLocal("正在测试 $clean …")
            val err = withContext(Dispatchers.IO) {
                app.pocketagent.android.agent.ModelProbe.probe(provider, key, clean)
            }
            if (err == null) {
                val list = container.customModelStore.add(clean)
                _state.update { it.copy(catalog = catalogWithCustom(), customModels = list) }
                appendLocal("✅ $clean 测试通过，已加入 /model")
            } else {
                appendLocal("❌ $clean 测试失败，未添加：$err")
            }
            _state.update { it.copy(running = false) }
            job = null
        }
    }

    fun removeCustomModel(id: String) {
        val list = container.customModelStore.remove(id)
        _state.update { it.copy(catalog = catalogWithCustom(), customModels = list) }
        appendLocal("已移除自定义模型：$id")
    }

    private fun applyProvider(updated: Provider) {
        viewModelScope.launch(Dispatchers.IO) { container.providerStore.upsert(updated) }
        _state.update { st ->
            st.copy(
                provider = updated,
                providers = st.providers.map { if (it.id == updated.id) updated else it },
            )
        }
    }

    /**
     * 本轮真正发给模型的工具 schema：注册表（含用户配置的 MCP）+ 内置 `delegate_task`。
     * 预算计算和提示词里的工具清单必须用同一份，否则 /context 会低估预留。
     */
    private fun toolSpecsFor(provider: Provider): List<ToolSpec> =
        if (!provider.toolsEnabled) {
            emptyList()
        } else {
            container.toolRegistry.specs() + app.pocketagent.android.agent.Subagents.spec
        }

    private suspend fun effectiveInstructions(provider: Provider): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEE", java.util.Locale.CHINA)
            .format(java.util.Date())
        val tools = toolSpecsFor(provider)
        val (agents, skills) = workspaceContext()
        val base = app.pocketagent.android.agent.AgentInstructions.build(
            provider = provider,
            workspaceLabel = container.workspace.label,
            agentsMd = agents,
            tools = tools,
            now = stamp,
            skills = skills,
            conversationId = conversationId,
        )
        val goal = conversation.goal
        if (goal.isNullOrBlank() || conversation.goalPaused) return base
        return base + "\n\n# 当前目标（必须朝它推进，不要停在计划上）\n" + goal +
            "\n全部完成后，在最后单独一行输出 GOAL_DONE。\n"
    }

    private var workspaceContextAt = 0L
    private var goalAutoContinues = 0
    private var autoContinuing = false
    private var workspaceContextCache: Pair<String?, List<Pair<String, String>>>? = null

    /**
     * AGENTS.md 和 skills 目录只在工作区里读一次，15 秒内复用。
     * 远端工作区每次读都是一次 SSH 往返，连发几条消息时会让首字延迟明显变差。
     */
    private suspend fun workspaceContext(): Pair<String?, List<Pair<String, String>>> {
        val now = System.currentTimeMillis()
        workspaceContextCache?.let { if (now - workspaceContextAt < WORKSPACE_CONTEXT_TTL_MS) return it }
        val agents = runCatching {
            app.pocketagent.android.agent.WorkspaceInstructions.load(container.workspace)
        }.getOrNull()
        val value = agents to discoverSkills()
        workspaceContextCache = value
        workspaceContextAt = now
        return value
    }

    private companion object {
        const val WORKSPACE_CONTEXT_TTL_MS = 15_000L
        const val MAX_GOAL_AUTO_CONTINUES = 12
        /** 写远端镜像前先读回来比对的上限；超过就不覆盖写，交给 /mirror pull */
        const val MIRROR_CHECK_BYTES = 1024 * 1024
    }

    /** skills/ 和 Codex 标准的 .agents/skills/；只取名字和描述，正文由 skill_read 按需读取。 */
    private suspend fun discoverSkills(): List<Pair<String, String>> = runCatching {
        app.pocketagent.android.tools.SkillCatalog.discover(container.workspace)
            .map { it.name to it.description }
    }.getOrDefault(emptyList())

    fun send(text: String) = send(text, emptyList())

    fun send(text: String, images: List<String>) {
        if (text.isBlank() && images.isEmpty()) return
        val raw = text.trim()
        goalContinuation?.cancel()
        goalContinuation = null
        if (heldSteeringIds.any { id -> turnSteering.peek().any { it.id == id } }) {
            _state.update { it.copy(error = "有补充要求尚未确认远端是否收到。请先同步核对，或清空待处理内容后再发送，避免重复执行。") }
            return
        }
        if (SlashCommands.isCommand(raw)) {
            runCommand(raw)
            return
        }
        if (job != null || _state.value.running) {
            enqueueSend(raw, images)
            return
        }
        pendingHarnessSend = null
        val remoteHostId = activeWorkspaceHostId()
        val remoteHost = remoteHostId?.let { container.remoteManager.host(it) }
        if (remoteHostId != null && remoteHost == null) {
            pendingHarnessSend = raw to images
            _state.update { it.copy(error = L10n.t("找不到当前远端工作区主机，请重新选择 /workspace。")) }
            return
        }
        if (remoteHost != null && remoteHost.copy(codexHarnessAvailable = null) !in detectedHarnessHosts) {
            pendingHarnessSend = raw to images
            _state.update { it.copy(running = true, error = null, notice = L10n.t("正在检测远端 harness…")) }
            job = viewModelScope.launch {
                try {
                    withContext(Dispatchers.IO) { detectHarness(remoteHost.id) }
                    _state.update { it.copy(running = false, notice = null, hosts = container.remoteManager.hosts()) }
                    job = null
                    sendWithDetectedHarness(raw, images)
                } catch (ce: CancellationException) {
                    _state.update { it.copy(running = false, notice = null) }
                    job = null
                    throw ce
                } catch (e: Exception) {
                    _state.update { it.copy(running = false, notice = null, error = e.message ?: L10n.t("远端 harness 检测失败，请重试")) }
                    job = null
                }
            }
            return
        }
        sendWithDetectedHarness(raw, images)
    }

    private fun enqueueSend(text: String, images: List<String>) {
        if (!turnSteering.offer(ChatMessage(role = Role.USER, text = text, images = images))) {
            _state.update {
                it.copy(error = "最多等待 ${TurnSteering.MAX_PENDING} 条补充要求；请等待读取或先清空待处理内容。")
            }
        }
        publishQueuedSends()
    }

    fun clearQueuedSends() {
        heldSteeringIds = emptySet()
        _state.update { it.copy(unconfirmedFollowUps = false, notice = null) }
        turnSteering.clear()
        publishQueuedSends()
    }

    private fun publishQueuedSends() {
        val previews = turnSteering.peek().map { queued ->
            val preview = queued.text.replace(Regex("\\s+"), " ").trim()
                .ifBlank { if (queued.images.isNotEmpty()) "图片附件" else "空消息" }
                .take(QUEUED_PREVIEW_LENGTH)
            QueuedSendPreview(queued.id, preview, queued.images.size)
        }
        _state.update { it.copy(queuedSends = previews) }
    }

    private suspend fun recordAppliedFollowUps(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            _state.update { st ->
                val known = st.messages.map { it.id }.toSet()
                val added = messages.distinctBy { it.id }.filterNot { it.id in known }
                st.copy(
                    messages = st.messages + added,
                    appliedFollowUps = st.appliedFollowUps + added.size,
                )
            }
            conversation = conversation.copy(messages = _state.value.messages)
            persist()
            publishQueuedSends()
        }
    }

    /** A late arrival or an older remote CLI still gets a real turn, never a false read receipt. */
    private fun dispatchQueuedSend() {
        if (job != null || _state.value.running || heldSteeringIds.isNotEmpty()) return
        val next = turnSteering.peek().firstOrNull() ?: return
        if (turnSteering.acknowledge(setOf(next.id)).isEmpty()) return
        publishQueuedSends()
        send(next.text, next.images)
    }

    private suspend fun detectHarness(hostId: String) {
        val host = container.remoteManager.host(hostId) ?: error("远端主机不存在")
        val available = container.remoteCodexTool.isAvailable(hostId)
        container.remoteStore.upsert(host.copy(codexHarnessAvailable = available))
        detectedHarnessHosts += host.copy(codexHarnessAvailable = null)
    }

    private fun sendWithDetectedHarness(raw: String, images: List<String>) {
        pendingHarnessSend = null
        if (shouldUseRemoteHarness()) {
            sendViaRemoteHarness(raw, images)
            return
        }
        val provider = _state.value.provider
        if (provider == null) {
            _state.update { it.copy(error = "还没有模型来源，先去添加一个", needsKey = true) }
            return
        }
        val key = container.secretStore.get(provider.id).orEmpty()
        if (key.isBlank()) {
            _state.update { it.copy(error = "这个来源还没填 API Key", needsKey = true) }
            return
        }

        if (!autoContinuing) goalAutoContinues = 0
        val userMessage = ChatMessage(role = Role.USER, text = raw, images = images)
        val history = _state.value.messages + userMessage
        _state.update { it.copy(messages = history, running = true, error = null, notice = null, appliedFollowUps = 0) }
        conversation = conversation.copy(messages = history).titleFromFirstMessage()
        _state.update { it.copy(title = conversation.title) }
        persist()

        // 桌面 Codex 的 fast 是 service_tier=priority，不是“关工具、只回一轮”。
        val effective = withFastMode(provider, _state.value.fastMode)

        job = viewModelScope.launch {
            // 挂前台服务：切到别的 App 或锁屏时系统不会冻结进程，流式回复能继续跑完。
            app.pocketagent.android.ChatRunService.start(container.appContext)
            try {
                // 和桌面 Codex 一样：系统提示词后面补上环境信息、AGENTS.md 和工作方式
                val withInstructions = withContext(Dispatchers.IO) {
                    effective.copy(systemPrompt = effectiveInstructions(effective))
                }
                // 和桌面 Codex 一致：按模型真实窗口决定带多少历史，没超窗口就全量发
                val contextTokens = ModelCatalog.contextTokensFor(container.modelCatalog, provider.model)
                val toolSpecs = toolSpecsFor(effective)
                val reserved = app.pocketagent.android.agent.HistoryWindow.estimateTokens(withInstructions.systemPrompt) +
                    toolSpecs.sumOf { spec ->
                        24 +
                            app.pocketagent.android.agent.HistoryWindow.estimateTokens(spec.name) +
                            app.pocketagent.android.agent.HistoryWindow.estimateTokens(spec.description) +
                            app.pocketagent.android.agent.HistoryWindow.estimateTokens(spec.parametersJson)
                    }
                val maxTokens = app.pocketagent.android.agent.HistoryWindow.budgetFor(
                    contextTokens,
                    reservedTokens = reserved,
                )
                var working = history
                var forModel = app.pocketagent.android.agent.HistoryWindow.trim(working, maxTokens = maxTokens)
                if (forModel.size < working.size) {
                    val compacted = try {
                        autoCompact(provider, key, working)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        null
                    }
                    if (compacted != null) {
                        val summarized = working.size - AUTO_COMPACT_KEEP_RECENT
                        working = compacted
                        _state.update {
                            it.copy(
                                messages = compacted,
                                notice = "上下文接近上限，已自动把前面 $summarized 条对话压缩成摘要。",
                            )
                        }
                        conversation = conversation.copy(messages = compacted)
                        persist()

                        // 只尝试一次。摘要本身仍可能超预算，此时接受裁剪，避免反复压缩。
                        forModel = app.pocketagent.android.agent.HistoryWindow.trim(
                            working,
                            maxTokens = maxTokens,
                        )
                        if (forModel.size < working.size) {
                            _state.update {
                                it.copy(
                                    notice = "已自动压缩前面 $summarized 条对话，但压缩后仍超过模型窗口，" +
                                        "本轮只带最近 ${forModel.size} 条（压缩后共 ${working.size} 条），" +
                                        "可再用 /compact 精简后继续",
                                )
                            }
                        }
                    } else {
                        // 摘要调用失败时退回原来的裁剪策略，至少保证本轮还能发出请求。
                        _state.update {
                            it.copy(
                                notice = "上下文超过模型窗口或单次请求大小上限，本轮只带最近 ${forModel.size} 条" +
                                    "（共 ${working.size} 条），可用 /compact 压缩后再继续",
                            )
                        }
                    }
                }
                // 流式增量按帧合并：模型每秒能吐几十个 delta，每个都整条重绘会让长回答明显卡顿。
                // 这里限制到约 20 次/秒，最终状态仍然由 runOnce 返回后完整写回。
                var lastStreamUpdate = 0L
                var lastStreamMessageId: String? = null
                val final = withContext(Dispatchers.IO) {
                    container.chatEngine.runOnce(
                        provider = withInstructions,
                        apiKey = key,
                        history = forModel,
                        cacheKey = conversationId,
                        historyBudgetTokens = maxTokens,
                        steering = turnSteering,
                        onSteeringApplied = ::recordAppliedFollowUps,
                    ) { live ->
                        val now = System.currentTimeMillis()
                        val boundary = live.id != lastStreamMessageId ||
                            app.pocketagent.android.agent.decodeReplyText(live.text).notices
                                .contains("已按补充要求调整后续步骤")
                        if (!boundary && now - lastStreamUpdate < STREAM_UPDATE_INTERVAL_MS) return@runOnce
                        lastStreamUpdate = now
                        lastStreamMessageId = live.id
                        _state.update { st ->
                            val others = st.messages.filterNot { it.id == live.id }
                            st.copy(messages = others + live)
                        }
                    }
                }
                val done = final.text.contains("GOAL_DONE") && turnSteering.peek().isEmpty()
                val shown = if (done) final.copy(text = final.text.replace("GOAL_DONE", "").trim()) else final
                _state.update { st ->
                    val others = st.messages.filterNot { it.id == shown.id }
                    st.copy(messages = others + shown, running = false, notice = null)
                }
                conversation = conversation.copy(messages = _state.value.messages)
                if (done && !conversation.goal.isNullOrBlank()) {
                    conversation = conversation.copy(goal = null, goalPaused = false)
                    _state.update { it.copy(goal = null, goalPaused = false) }
                    appendLocal(L10n.t("目标已完成 ✅ 已自动结束 /goal"))
                }
                persist()
                // 会话镜像：开启后把这一轮的结果同步到远端工作区，方便在远程机器上看到最新上下文。
                mirrorSessionToRemote()
                continueGoalIfNeeded(done)
            } catch (ce: CancellationException) {
                _state.update { st ->
                    val updated = st.messages.map { m ->
                        if (m.role == Role.ASSISTANT && !m.stopped) m.copy(stopped = true) else m
                    }
                    st.copy(messages = updated, running = false)
                }
                conversation = conversation.copy(messages = _state.value.messages)
                persist()
                throw ce
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.message ?: "请求失败") }
            } finally {
                job = null
                app.pocketagent.android.ChatRunService.stop(container.appContext)
                dispatchQueuedSend()
            }
        }
    }

    private fun shouldUseRemoteHarness(): Boolean {
        val hostId = activeWorkspaceHostId() ?: return false
        val host = container.remoteManager.host(hostId) ?: return false
        val boundHere = conversation.remoteThreadId != null &&
            (conversation.remoteHostId.isNullOrBlank() || conversation.remoteHostId == host.id)
        return shouldUseRemoteCodexHarness(boundHere, host.codexHarnessAvailable)
    }

    /**
     * 当前会话实际绑定的工作区主机。会话绑定优先于全局设置，避免新开/切换会话时
     * 把上一个会话的远端选择带进来，也避免老会话只剩 remoteHostId 时退回本地。
     */
    private fun activeWorkspaceHostId(): String? =
        conversation.workspaceHostId?.takeIf { it.isNotBlank() }
            ?: container.settings.remoteWorkspaceHostId?.takeIf { it.isNotBlank() }

    private fun sendViaRemoteHarness(raw: String, images: List<String> = emptyList()) {
        val hostId = activeWorkspaceHostId()
            ?: return appendLocal("远端工作区没有选择主机。")
        val host = container.remoteManager.host(hostId)
            ?: return appendLocal("找不到当前远端工作区主机，请重新选择 /workspace。")
        val remoteImages = images.mapNotNull { app.pocketagent.android.tools.parseImageDataUrl(it) }
        if (remoteImages.size != images.size) {
            appendLocal("附件里有无法识别的图片格式；远端 harness 支持 PNG/JPEG/WebP/GIF。")
            return
        }
        val remotePromptText = raw.ifBlank { "请查看这张图片并完成用户的要求。" }
        val cwd = conversation.workspaceDir.orEmpty()
            .ifBlank { container.settings.remoteWorkspaceDir }
            .ifBlank { host.workspaceDir.ifBlank { "~" } }
        val priorMessages = _state.value.messages
        val priorConversation = conversation.copy(messages = priorMessages)
        val hasSyncedOnTarget = runCatching {
            container.syncService.state(host.id).base.containsKey(conversation.id)
        }.getOrDefault(false)
        val requestedResume = chooseRemoteResume(
            remoteThreadId = conversation.remoteThreadId,
            remoteHostId = conversation.remoteHostId,
            currentHostId = host.id,
            conversationId = conversation.id,
            origin = conversation.origin,
            hasSyncedOnTarget = hasSyncedOnTarget,
        )
        val selectedProvider = _state.value.provider
        val remoteModel = selectedProvider?.model?.takeIf { it.isNotBlank() }
        val remoteEffort = selectedProvider?.reasoningEffort?.takeIf { it.isNotBlank() }
        val includeReasoning = _state.value.showRemoteThinking
        val userMessage = ChatMessage(role = Role.USER, text = raw, images = images)
        val progress = ChatMessage(
            role = Role.ASSISTANT,
            text = "正在连接 ${host.name} Codex harness" +
                (remoteModel?.let { " · $it" } ?: "") + "…",
            model = app.pocketagent.android.agent.LOCAL_NOTICE_MODEL,
        )
        val progressLines = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun renderProgress(): String = buildString {
            append("正在连接 ${host.name} Codex harness")
            remoteModel?.let { append(" · ").append(it) }
            synchronized(progressLines) {
                progressLines.takeLast(6).forEach { line ->
                    append('\n').append("• ").append(line)
                }
            }
        }
        fun noteProgress(line: String) {
            val clean = line.trim()
            if (clean.isEmpty()) return
            synchronized(progressLines) {
                if (progressLines.lastOrNull() == clean) return
                progressLines += clean
                while (progressLines.size > 8) progressLines.removeAt(0)
            }
            val text = renderProgress()
            _state.update { st ->
                st.copy(
                    messages = st.messages.map { message ->
                        if (message.id == progress.id) message.copy(text = text) else message
                    },
                )
            }
        }
        val history = _state.value.messages + userMessage
        _state.update {
            it.copy(messages = history + progress, running = true, error = null, notice = null, appliedFollowUps = 0)
        }
        conversation = conversation.copy(messages = history).titleFromFirstMessage()
        _state.update { it.copy(title = conversation.title) }
        persist()

        job = container.appScope.launch {
            app.pocketagent.android.ChatRunService.start(
                container.appContext,
                "${host.name} · ${remoteModel ?: "Codex"} 正在工作",
            )
            try {
                // Activity 被系统销毁时 ViewModel 的持久化队列也会停止；先直接落盘用户消息。
                withContext(Dispatchers.IO) { container.conversationStore.upsert(conversation) }
                // 手机新建、尚未同步过的会话：先把已有正文落成远端 Codex 原生 rollout，
                // 再 resume 同一个 id。这样首轮也使用完整原生上下文，电脑上不会多出一条重复会话。
                var resumeUsed = requestedResume
                val replayable = remoteReplayableMessages(priorMessages)
                if (resumeUsed == null) {
                    resumeUsed = runCatching {
                        withContext(Dispatchers.IO) {
                            container.syncService.ensureNativeThread(
                                host.id,
                                priorConversation.copy(messages = replayable),
                            )
                        }
                    }.getOrNull()
                }
                var promptForAttempt = if (resumeUsed == null) {
                    remoteHarnessPrompt(priorMessages, remotePromptText)
                } else {
                    remotePromptText
                }
                var result = withContext(Dispatchers.IO) {
                    container.remoteCodexTool.run(
                        host = host.id,
                        cwd = cwd,
                        prompt = promptForAttempt,
                        resume = resumeUsed,
                        model = remoteModel,
                        effort = remoteEffort,
                        includeReasoning = includeReasoning,
                        images = remoteImages,
                        timeout = 1_800_000L,
                        steering = turnSteering,
                        onSteeringApplied = ::recordAppliedFollowUps,
                        onApproval = { container.approvalGate.request(it) },
                        onProgress = { noteProgress(it.text) },
                    )
                }
                // 原生会话找不到/版本不兼容时，必须带着完整手机上下文新建；
                // 只发最后一句会让远端突然“失忆”，而且之后无法可靠同步。
                if (
                    resumeUsed != null && result.finalText.isBlank() &&
                    result.unconfirmedSteeringIds.isEmpty() &&
                    remoteResumeUnavailable(result)
                ) {
                    noteProgress("原 session 无法续接，正在新建远端 session")
                    resumeUsed = null
                    promptForAttempt = remoteHarnessPrompt(priorMessages, remotePromptText)
                    result = withContext(Dispatchers.IO) {
                        container.remoteCodexTool.run(
                            host = host.id,
                            cwd = cwd,
                            prompt = promptForAttempt,
                            resume = null,
                            model = remoteModel,
                            effort = remoteEffort,
                            includeReasoning = includeReasoning,
                            images = remoteImages,
                            timeout = 1_800_000L,
                            steering = turnSteering,
                            onSteeringApplied = ::recordAppliedFollowUps,
                            onApproval = { container.approvalGate.request(it) },
                            onProgress = { noteProgress(it.text) },
                        )
                    }
                }
                if (result.unconfirmedSteeringIds.isNotEmpty()) {
                    heldSteeringIds = result.unconfirmedSteeringIds
                    result.threadId?.let { threadId ->
                        conversation = conversation.copy(remoteThreadId = threadId, remoteHostId = host.id)
                    }
                    _state.update {
                        it.copy(
                            unconfirmedFollowUps = true,
                            remoteThreadId = conversation.remoteThreadId,
                            remoteHostId = conversation.remoteHostId,
                            notice = L10n.t("远端接收待确认，请先同步核对后继续"),
                        )
                    }
                }
                val text = result.finalText.trim()
                if (!result.ok || text.isBlank()) {
                    val why = result.errors.lastOrNull()
                        ?: if (result.ok) "远端 Codex 没有返回正文" else "exit=${result.exitCode}"
                    val partial = text.takeIf { it.isNotBlank() && !result.ok }
                        ?.let { "\n\n部分正文（未作为成功结果保存）：\n${it.take(2_000)}" }
                        .orEmpty()
                    val errorMessage = ChatMessage(
                        role = Role.ASSISTANT,
                        text = "远端 harness 失败：$why$partial",
                        model = app.pocketagent.android.agent.LOCAL_NOTICE_MODEL,
                    )
                    conversation = conversation.copy(
                        messages = conversation.messages + errorMessage,
                        updatedAt = System.currentTimeMillis(),
                    )
                    withContext(Dispatchers.IO) { container.conversationStore.upsert(conversation) }
                    _state.update {
                        it.copy(
                            messages = conversation.messages,
                            running = false,
                            error = "远端 harness 失败：$why",
                        )
                    }
                    return@launch
                }
                val done = text.contains("GOAL_DONE") && turnSteering.peek().isEmpty() && heldSteeringIds.isEmpty()
                val answer = ChatMessage(
                    role = Role.ASSISTANT,
                    text = if (done) text.replace("GOAL_DONE", "").trim() else text,
                    reasoning = result.reasoning.joinToString("\n\n"),
                    model = "remote-codex",
                    tokensIn = result.usage?.first ?: 0,
                    tokensOut = result.usage?.second ?: 0,
                )
                val updated = _state.value.messages.filterNot { m -> m.id == progress.id } + answer
                val remoteThreadId = result.threadId ?: resumeUsed
                conversation = conversation.copy(
                    messages = updated,
                    updatedAt = System.currentTimeMillis(),
                    remoteThreadId = remoteThreadId,
                    remoteHostId = remoteThreadId?.let { host.id },
                )
                withContext(Dispatchers.IO) { container.conversationStore.upsert(conversation) }
                _state.update {
                    it.copy(
                        messages = updated,
                        running = false,
                        error = null,
                        remoteThreadId = remoteThreadId,
                        remoteHostId = conversation.remoteHostId,
                    )
                }
                if (done && !conversation.goal.isNullOrBlank()) {
                    conversation = conversation.copy(goal = null, goalPaused = false)
                    _state.update { it.copy(goal = null, goalPaused = false) }
                    appendLocal(L10n.t("目标已完成 ✅ 已自动结束 /goal"))
                }
                persist()
                // 远端 harness 已经把这轮写进原生 rollout；把同步基线推到最新，
                // 下次同步不会因为“手机和电脑都变了”误报冲突。
                if (remoteThreadId != null) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            container.syncService.rebaseAfterRemoteTurn(host.id, conversation)
                        }
                    }
                }
                continueGoalIfNeeded(done)
            } catch (ce: CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    container.conversationStore.upsert(conversation)
                }
                _state.update {
                    it.copy(
                        messages = it.messages.filterNot { m -> m.id == progress.id },
                        running = false,
                    )
                }
                throw ce
            } catch (e: Exception) {
                val errorMessage = ChatMessage(
                    role = Role.ASSISTANT,
                    text = "远端 harness 失败：${e.message}",
                    model = app.pocketagent.android.agent.LOCAL_NOTICE_MODEL,
                )
                conversation = conversation.copy(
                    messages = conversation.messages + errorMessage,
                    updatedAt = System.currentTimeMillis(),
                )
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    container.conversationStore.upsert(conversation)
                }
                _state.update {
                    it.copy(
                        messages = it.messages.filterNot { m -> m.id == progress.id },
                        running = false,
                        error = "远端 harness 失败：${e.message}",
                    )
                }
            } finally {
                job = null
                app.pocketagent.android.ChatRunService.stop(container.appContext)
                dispatchQueuedSend()
            }
        }
    }

    fun stop() {
        goalContinuation?.cancel()
        goalContinuation = null
        heldSteeringIds = emptySet()
        pendingHarnessSend = null
        turnSteering.clear()
        job?.cancel()
        job = null
        _state.update { st ->
            val lastAssistantId = st.messages.lastOrNull { it.role == Role.ASSISTANT }?.id
            val updated = st.messages.map { m ->
                if (m.id == lastAssistantId) m.copy(stopped = true) else m
            }
            st.copy(messages = updated, running = false, queuedSends = emptyList(), unconfirmedFollowUps = false)
        }
        conversation = conversation.copy(messages = _state.value.messages)
        persist()
    }

    /** 删掉最后一轮问答，用同样的输入重新跑一次。 */
    fun retryLast() {
        if (job != null) return
        pendingHarnessSend?.let { (text, images) ->
            send(text, images)
            return
        }
        val messages = _state.value.messages
        val lastUser = messages.lastOrNull { it.role == Role.USER } ?: return
        val trimmed = messages.takeWhile { it.id != lastUser.id }
        _state.update { it.copy(messages = trimmed, error = null) }
        conversation = conversation.copy(messages = trimmed)
        persist()
        send(lastUser.text, lastUser.images)
    }

    fun answerApproval(allow: Boolean) {
        container.approvalGate.answer(allow)
    }

    fun clearMessages() {
        goalContinuation?.cancel()
        goalContinuation = null
        heldSteeringIds = emptySet()
        pendingHarnessSend = null
        turnSteering.clear()
        _state.update { it.copy(messages = emptyList(), error = null, queuedSends = emptyList(), unconfirmedFollowUps = false) }
        container.settings.remoteWorkspaceHostId = null
        container.settings.remoteWorkspaceDir = ""
        conversation = conversation.copy(
            messages = emptyList(),
            title = "新对话",
            workspaceHostId = null,
            workspaceDir = null,
        )
        _state.update {
            it.copy(
                title = "新对话",
                workspaceHostId = null,
                workspaceDir = "",
                workspaceLabel = container.workspace.label,
            )
        }
        persist()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun openCommandSheet() = _state.update { it.copy(commandSheet = true) }
    fun dismissCommandSheet() = _state.update { it.copy(commandSheet = false) }
    fun openModelPicker() = _state.update { it.copy(modelPicker = true) }
    fun dismissModelPicker() = _state.update { it.copy(modelPicker = false, pendingModel = null) }

    /** 模型选择弹窗里点某一条：切过去并留一条本地记录。 */
    fun pickModelFromSheet(id: String) {
        selectProvider(id)
        _state.update { it.copy(modelPicker = false) }
        val p = _state.value.providers.firstOrNull { it.id == id } ?: return
        val keyHint = if (container.secretStore.get(p.id).isNullOrBlank()) "（这个来源还没填 Key）" else ""
        appendLocal("已切换到 ${p.name} · ${p.model}$keyHint")
    }

    // ---------------------------------------------------------------- 斜杠命令

    private fun runCommand(raw: String) {
        val parsed = SlashCommands.parse(raw)
        // undo 不能先把自己这条命令记进历史，否则撤掉的是 /undo 本身
        if (parsed.name == "undo") {
            undoLastTurn()
            return
        }
        echoCommand(raw)
        when (parsed.name) {
            "", "help" -> appendLocal(SlashCommands.helpText())
            "model" -> handleModelCommand(parsed.arg)
            "reasoning", "effort" -> handleReasoningCommand(parsed.arg)
            "thinking" -> handleRemoteThinkingCommand(parsed.arg)
            "goal" -> handleGoalCommand(parsed.arg)
            "status" -> showStatus()
            "context" -> showContext()
            "approvals" -> handleApprovalsCommand(parsed.arg)
            "permissions" -> handlePermissionsCommand(parsed.arg)
            "mcp" -> showMcp(parsed.arg)
            "undo" -> undoLastTurn()
            "export" -> exportConversation()
            "mirror" -> handleMirrorCommand(parsed.arg)
            "init" -> initAgentsFile()
            "remember" -> remember(parsed.arg)
            "skills" -> showSkills()
            "ps" -> showRunning()
            "stop" -> stopRunning()
            "fast" -> toggleFast()
            "tools" -> handleToolsCommand(parsed.arg)
            "workspace" -> handleWorkspaceCommand(parsed.arg)
            "new" -> clearMessages()
            "compact" -> compact()
            "test" -> if (parsed.arg.trim().equals("models", true)) testModels() else testAllProviders()
            else -> appendLocal("没有这个命令：/${parsed.name}\n\n" + SlashCommands.helpText())
        }
    }

    private fun handleReasoningCommand(arg: String) {
        val provider = _state.value.provider ?: run {
            appendLocal(L10n.t("还没有模型来源。"))
            return
        }
        val levels = _state.value.catalog.firstOrNull { it.id == provider.model }?.reasoningLevels
            ?: ModelCatalog.defaultLevels
        if (arg.isBlank()) {
            // 手机上没有行号输入：直接弹可点的档位面板
            _state.update { it.copy(reasoningPicker = true) }
            return
        }
        val level = arg.lowercase()
        if (level != "default" && level !in levels) {
            appendLocal("「$arg」不支持。可选：" + levels.joinToString(" / ") + "，或 default")
            return
        }
        setReasoning(level)
    }

    private fun handleRemoteThinkingCommand(arg: String) {
        if (activeWorkspaceHostId() == null) {
            appendLocal("/thinking 只在 /workspace 启用远端主机时生效。")
            return
        }
        val enable = when (arg.trim().lowercase()) {
            "on", "show", "1", "true", "显示", "开" -> true
            "off", "hide", "0", "false", "隐藏", "关" -> false
            "" -> !container.settings.showRemoteThinking
            else -> {
                appendLocal("用法：/thinking on | /thinking off")
                return
            }
        }
        container.settings.showRemoteThinking = enable
        _state.update { it.copy(showRemoteThinking = enable) }
        appendLocal(
            if (enable) "已显示远端 Codex 的思考过程；会增加手机流量。"
            else "已隐藏远端 Codex 的思考过程；reasoning 不再传回手机。",
        )
    }

    private fun handleGoalCommand(arg: String) {
        when {
            arg.isBlank() || arg.equals("view", true) || arg.equals("show", true) -> {
                // 先把状态回一条，避免「点了没反应」的感觉；再弹面板方便改
                val goal = conversation.goal
                appendLocal(
                    when {
                        goal.isNullOrBlank() -> "当前没有设定目标。点面板输入，或发 /goal 你的目标。"
                        conversation.goalPaused -> "当前目标（已暂停）：$goal\n发 /goal resume 继续，/goal clear 清除。"
                        else -> "当前目标（进行中）：$goal\n/goal edit 修改，/goal pause 暂停，/goal clear 清除，/goal done 完成。"
                    },
                )
                _state.update { it.copy(goalEditor = true) }
            }

            arg.equals("pause", true) || arg.equals("暂停", true) -> {
                if (conversation.goal.isNullOrBlank()) {
                    appendLocal(L10n.t("还没有目标，先发 /goal 你的目标。"))
                } else {
                    conversation = conversation.copy(goalPaused = true)
                    _state.update { it.copy(goalPaused = true) }
                    persist()
                    appendLocal("目标已暂停：${conversation.goal}\n发 /goal resume 继续。")
                }
            }

            arg.equals("resume", true) || arg.equals("继续", true) -> {
                if (conversation.goal.isNullOrBlank()) {
                    appendLocal(L10n.t("还没有目标，先发 /goal 你的目标。"))
                } else {
                    conversation = conversation.copy(goalPaused = false)
                    _state.update { it.copy(goalPaused = false) }
                    persist()
                    appendLocal("目标已继续：${conversation.goal}")
                    kickoffGoal(conversation.goal!!)
                }
            }

            arg.equals("edit", true) || arg.equals("编辑", true) -> _state.update { it.copy(goalEditor = true) }
            arg.equals("done", true) || arg.equals("clear", true) || arg.equals("完成", true) || arg.equals("清除", true) -> {
                val had = conversation.goal
                conversation = conversation.copy(goal = null, goalPaused = false)
                _state.update { it.copy(goal = null, goalPaused = false) }
                persist()
                appendLocal(if (had.isNullOrBlank()) "本来就没有目标。" else "目标已完成：$had")
            }
            else -> {
                goalAutoContinues = 0
                conversation = conversation.copy(goal = arg, goalPaused = false)
                _state.update { it.copy(goal = arg, goalPaused = false) }
                persist()
                appendLocal(
                    "目标已设定：$arg\n开始推进；可用 /goal pause 暂停 · /goal resume 继续 · /goal done 完成",
                )
                kickoffGoal(arg)
            }
        }
    }

    private fun showStatus() {
        val st = _state.value
        val p = st.provider
        val lines = buildString {
            append("来源：${p?.name ?: "无"}\n")
            append("模型：${p?.model?.ifBlank { "（空，未设置）" } ?: "无"}\n")
            append("协议：${when (p?.kind) {
                app.pocketagent.android.data.ProviderKind.CHAT_COMPLETIONS -> "chat/completions"
                app.pocketagent.android.data.ProviderKind.RESPONSES -> "responses"
                app.pocketagent.android.data.ProviderKind.ANTHROPIC -> "anthropic /v1/messages"
                null -> "无"
            }}\n")
            append("地址：${p?.baseUrl ?: "无"}\n")
            append("思考强度：${p?.reasoningEffort?.let { ModelCatalog.label(it) + "（$it）" } ?: "服务端默认"}\n")
            append("工具：${if (p?.toolsEnabled == true) "开" else "关"}｜自动批准：${if (p?.autoApprove == true) "是" else "否"}\n")
            val mcpServers = container.mcpStore.load().count { it.enabled && it.url.isNotBlank() }
            val mcpTools = container.mcpTools.tools().size
            append("MCP：$mcpServers 台服务器 / $mcpTools 个工具")
            if (p?.toolsEnabled == true) append("｜子 agent：可用（只读）")
            append('\n')
            append("快速模式：${if (st.fastMode) "开（service_tier=priority）" else "关"}\n")
            append("消息数：${st.messages.size}｜目标：${st.goal ?: "未设定"}")
        }
        appendLocal(lines)
    }

    /**
     * `/context`：对齐桌面 Codex 状态栏的上下文信息。
     *
     * 手机 harness 的真实限制不只是“消息有多少”，还要扣掉 system prompt、AGENTS/skills
     * 和工具 schema。这里显示的是本轮实际会发送的估算，便于判断何时该 `/compact`。
     */
    private fun showContext() {
        val provider = _state.value.provider ?: run {
            appendLocal(L10n.t("还没有模型来源。"))
            return
        }
        viewModelScope.launch {
            val messages = _state.value.messages
            val contextTokens = ModelCatalog.contextTokensFor(container.modelCatalog, provider.model)
            val instructions = withContext(Dispatchers.IO) {
                runCatching { effectiveInstructions(provider) }.getOrDefault(provider.systemPrompt)
            }
            val toolSpecs = toolSpecsFor(provider)
            val reserved = app.pocketagent.android.agent.HistoryWindow.estimateTokens(instructions) +
                toolSpecs.sumOf { spec ->
                    24 +
                        app.pocketagent.android.agent.HistoryWindow.estimateTokens(spec.name) +
                        app.pocketagent.android.agent.HistoryWindow.estimateTokens(spec.description) +
                        app.pocketagent.android.agent.HistoryWindow.estimateTokens(spec.parametersJson)
                }
            val window = contextTokens.takeIf { it > 0 }
                ?: app.pocketagent.android.agent.HistoryWindow.DEFAULT_CONTEXT_TOKENS
            val budget = app.pocketagent.android.agent.HistoryWindow.budgetFor(window, reserved)
            val used = messages.sumOf(app.pocketagent.android.agent.HistoryWindow::tokensOf)
            val kept = app.pocketagent.android.agent.HistoryWindow.trim(messages, maxTokens = budget)
            val keptTokens = kept.sumOf(app.pocketagent.android.agent.HistoryWindow::tokensOf)
            val percent = if (budget <= 0) 0 else used * 100 / budget
            val remoteHost = activeWorkspaceHostId()?.let { container.remoteManager.host(it) }
            val remoteThread = _state.value.remoteThreadId?.takeIf {
                _state.value.remoteHostId.isNullOrBlank() ||
                    _state.value.remoteHostId == remoteHost?.id
            }

            appendLocal(
                buildString {
                    append("上下文预算（估算）\n")
                    append("模型：").append(provider.model).append('\n')
                    append("窗口：").append(window).append(" tokens")
                    if (contextTokens <= 0) append("（目录未登记，按默认值）")
                    append('\n')
                    append("系统提示 + 工具预留：").append(reserved).append(" tokens\n")
                    append("历史可用：").append(budget).append(" tokens\n")
                    append("当前历史：").append(used).append(" tokens（约 ").append(percent).append("%）\n")
                    append("本轮发送：").append(kept.size).append('/').append(messages.size)
                    append(" 条，估算 ").append(keptTokens).append(" tokens\n")
                    if (kept.size < messages.size) {
                        append("较旧历史会被裁剪；长任务可先 /compact 生成摘要。\n")
                    } else {
                        append("当前未触发历史裁剪。\n")
                    }
                    if (remoteHost != null) {
                        append("\n远端模式：").append(remoteHost.name)
                        if (remoteThread != null) {
                            append(" · 原生 session ").append(remoteThread.take(8)).append('…')
                        }
                        append("\n这轮由远端 harness 执行，真实模型上下文以那台机器的原生 session 为准；")
                        append("上面的 token 数是手机端显示的估算。")
                    }
                },
            )
        }
    }

    private fun handleApprovalsCommand(arg: String) {
        val provider = _state.value.provider ?: run {
            appendLocal(L10n.t("还没有模型来源。"))
            return
        }
        when (arg.lowercase()) {
            "auto", "自动", "on" -> {
                applyProvider(provider.copy(autoApprove = true))
                appendLocal(L10n.t("已设为自动批准：工具不再逐条询问。"))
            }
            "ask", "询问", "off" -> {
                applyProvider(provider.copy(autoApprove = false))
                container.approvalGate.resetSessionAutoAllow()
                appendLocal(L10n.t("已改回每次询问。"))
            }
            else -> _state.update { it.copy(approvalsPicker = true) }
        }
    }

    /**
     * `/permissions`：不带参数打开面板；带参数支持 `/permissions web off` 这类快速开关。
     * 面板里的开关直接写回当前来源，下一次工具调用立即生效。
     */
    private fun handlePermissionsCommand(arg: String) {
        val parts = arg.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            _state.update { it.copy(permissionsSheet = true) }
            return
        }
        val category = parts[0]
        if (category !in app.pocketagent.android.agent.ToolPermissions.all) {
            appendLocal(
                "「$category」不是有效类别。可选：" +
                    app.pocketagent.android.agent.ToolPermissions.all.joinToString(" / "),
            )
            return
        }
        val allow = when (parts.getOrNull(1) ?: "on") {
            "on", "allow", "true", "yes" -> true
            "off", "deny", "false", "no" -> false
            else -> {
                appendLocal("用法：/permissions $category on|off")
                return
            }
        }
        togglePermission(category, allow)
        val label = app.pocketagent.android.agent.ToolPermissions.label(category)
        appendLocal("$label：" + if (allow) "已放行，不再逐个确认" else "改为每次确认")
    }

    private fun undoLastTurn() {
        if (job != null) {
            appendLocal(L10n.t("正在回答，先 /stop 再撤回。"))
            return
        }
        val messages = _state.value.messages
        val lastUserIndex = messages.indexOfLast { it.role == Role.USER }
        if (lastUserIndex < 0) {
            appendLocal(L10n.t("没有可撤回的内容。"))
            return
        }
        val trimmed = messages.take(lastUserIndex)
        _state.update { it.copy(messages = trimmed) }
        conversation = conversation.copy(messages = trimmed)
        persist()
        appendLocal(L10n.t("已撤回上一轮（含用户输入）。"))
    }

    private fun stopRunning() {
        if (job == null) {
            appendLocal(L10n.t("现在没有在生成。"))
            return
        }
        stop()
        appendLocal(L10n.t("已停止当前回答。"))
    }

    /** /workspace：把文件工具的工作区切到手机本地或远端主机。 */
    private fun handleWorkspaceCommand(arg: String) {
        val parts = arg.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        when {
            parts.isEmpty() -> {
                // 手机上没有终端行号，直接弹可点的选择面板
                _state.update { it.copy(workspaceSheet = true) }
            }

            parts[0].equals("local", true) -> switchWorkspace(null, "")

            parts[0].equals("remote", true) -> {
                val hosts = container.remoteManager.hosts()
                val wanted = parts.getOrNull(1)
                val host = hosts.firstOrNull { it.name == wanted || it.id == wanted }
                    ?: hosts.firstOrNull { h -> wanted != null && h.name.contains(wanted, true) }
                if (host == null) {
                    appendLocal(L10n.t("找不到这台主机，先去「设置 → 远程主机」加一台。"))
                    return
                }
                switchWorkspace(host.id, parts.getOrNull(2) ?: "~/pocketagent")
            }

            else -> appendLocal(L10n.t("用法：/workspace（弹出选择）| /workspace local | /workspace remote <主机名> [目录]"))
        }
    }

    fun dismissWorkspaceSheet() = _state.update { it.copy(workspaceSheet = false) }

    /**
     * 工作区面板里点某台机器：先真的写一个探针文件再读回来，
     * 确认目录可写才切过去（避免「界面显示的远端与实际连接的主机不一致」这种错觉）。
     */
    fun switchWorkspace(hostId: String?, dir: String) {
        if (job != null || _state.value.running) return
        val targetHost = hostId?.takeIf { it.isNotBlank() }
        val oldHost = container.settings.remoteWorkspaceHostId
        val oldDir = container.settings.remoteWorkspaceDir
        val oldWorkspaceHost = conversation.workspaceHostId
        val oldWorkspaceDir = conversation.workspaceDir
        val hostConfigBeforeSwitch = targetHost?.let { container.remoteManager.host(it) }
        val resolvedDir = if (targetHost == null) {
            ""
        } else {
            dir.ifBlank { hostConfigBeforeSwitch?.workspaceDir.orEmpty() }.ifBlank { "~/pocketagent" }
        }
        container.settings.remoteWorkspaceHostId = targetHost
        container.settings.remoteWorkspaceDir = resolvedDir
        if (targetHost != null) {
            // 每台机器各记一份，下次切回来还是这个目录
            runCatching {
                hostConfigBeforeSwitch?.let { h ->
                    if (h.workspaceDir != resolvedDir) {
                        container.remoteStore.upsert(h.copy(workspaceDir = resolvedDir))
                    }
                }
            }
        }
        _state.update {
            it.copy(
                workspaceSheet = false,
                workspaceHostId = targetHost,
                workspaceDir = resolvedDir,
                running = true,
                notice = L10n.t("正在连接工作区并检测远端 harness…"),
            )
        }
        job = viewModelScope.launch {
            val probe = "PocketAgent 工作区\nhost=$targetHost\ndir=$resolvedDir\nat=${System.currentTimeMillis()}\n"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    container.workspace.write(".pocketagent-workspace", probe)
                    container.workspace.read(".pocketagent-workspace", 0, 4096)
                    targetHost?.let { detectHarness(it) }
                }
            }
            val label = container.workspace.label
            result.onSuccess {
                // 工作区换了，AGENTS.md / skills 的缓存必须失效
                workspaceContextCache = null
                workspaceContextAt = 0L
                _state.update { it.copy(workspaceLabel = label, hosts = container.remoteManager.hosts(), running = false, notice = null) }
                conversation = conversation.copy(
                    workspaceHostId = targetHost,
                    workspaceDir = resolvedDir.ifBlank { null },
                )
                persist()
                appendLocal(
                    if (targetHost == null) "工作区：$label（代码/文档存手机）"
                    else "工作区已连上并验证可写：$label\n" +
                        if (container.remoteManager.host(targetHost)?.codexHarnessAvailable == true) {
                            L10n.t("已检测到 Codex：之后使用这台机器自己的 Codex/DeepSeek harness、配置和原生会话。")
                        } else {
                            L10n.t("未检测到 Codex：由手机 harness 调用远端文件和命令工具；安装后重新选择此工作区即可检测。")
                        },
                )
            }.onFailure { e ->
                container.settings.remoteWorkspaceHostId = oldHost
                container.settings.remoteWorkspaceDir = oldDir
                runCatching { hostConfigBeforeSwitch?.let { container.remoteStore.upsert(it) } }
                conversation = conversation.copy(
                    workspaceHostId = oldWorkspaceHost,
                    workspaceDir = oldWorkspaceDir,
                )
                _state.update {
                    it.copy(
                        workspaceHostId = oldHost,
                        running = false,
                        notice = null,
                        workspaceDir = oldDir,
                        workspaceLabel = container.workspace.label,
                    )
                }
                appendLocal("切到 $label 失败（${e.message}），已恢复到 ${container.workspace.label}。")
            }
            job = null
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
    }

    /**
     * 同步可能改了当前这条对话（它本身也在同步范围里），
     * 重新进对话时从磁盘读一次，避免旧内存把同步结果盖回去。
     */
    fun syncFromStore() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                container.conversationStore.load().firstOrNull { it.id == conversationId }
            } ?: return@launch
            if (fresh == conversation) return@launch
            conversation = fresh
            _state.update {
                it.copy(
                    messages = fresh.messages,
                    title = fresh.title,
                    remoteThreadId = fresh.remoteThreadId,
                    remoteHostId = fresh.remoteHostId,
                )
            }
        }
    }

    private fun showRunning() {
        appendLocal(if (job == null) "空闲：没有正在生成的回答。" else "正在生成中，要中断用 /stop。")
    }

    private fun showSkills() {
        val enabled = _state.value.provider?.toolsEnabled == true
        val specs = toolSpecsFor(_state.value.provider ?: return)
        appendLocal(
            (if (enabled) "工具已启用：" else "工具当前关闭（/tools on 可开）：") + "\n" +
                specs.joinToString("\n") { "· ${it.name} — ${it.description.take(60)}" },
        )
    }

    /** `/mcp` 看配置和已发现的工具，`/mcp refresh` 强制重新拉 tools/list。 */
    private fun showMcp(arg: String) {
        val force = arg.trim().equals("refresh", true) || arg.trim().equals("reload", true)
        viewModelScope.launch {
            val (servers, summary) = withContext(Dispatchers.IO) {
                val configured = container.mcpStore.load()
                val refreshed = runCatching { container.mcpTools.ensureFresh(force = force) }.getOrNull()
                configured to refreshed
            }
            if (servers.isEmpty()) {
                appendLocal(
                    "还没有配置 MCP 服务器。\n" +
                        "去「设置 → 工具扩展 → MCP 服务器」添加 HTTP 地址和访问令牌；" +
                        "令牌只存本机 Keystore。",
                )
                return@launch
            }
            val tools = container.mcpTools.tools()
            val text = buildString {
                append(summary?.message() ?: "MCP 状态：未刷新")
                append('\n')
                servers.forEach { server ->
                    val own = tools.filterIsInstance<app.pocketagent.android.mcp.McpServerTool>()
                        .filter { it.serverId == server.id }
                    append("· ").append(server.name.ifBlank { server.url })
                    append(if (server.enabled) "（启用）" else "（已停用）")
                    if (own.isEmpty()) {
                        append("：暂无工具")
                    } else {
                        append("：").append(own.joinToString(", ") { it.spec.name })
                    }
                    append('\n')
                }
                append("MCP 工具默认每次确认；可在 /permissions 里整类放行。")
            }
            appendLocal(text.trimEnd())
        }
    }

    private fun remember(note: String) {
        if (note.isBlank()) {
            appendLocal(L10n.t("用法：/remember 以后默认用中文回答"))
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date())
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val old = runCatching { container.workspace.read("NOTES.md", 0, 64 * 1024) }.getOrDefault("")
                    container.workspace.write("NOTES.md", old + "- [$stamp] $note\n")
                    container.workspace.label
                }
            }
            result.onSuccess { where -> appendLocal("已记到 $where/NOTES.md：$note") }
                .onFailure { appendLocal("记笔记失败：${it.message}") }
        }
    }

    private fun initAgentsFile() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val where = container.workspace.label
                    val existing = runCatching { container.workspace.read("AGENTS.md", 0, 64 * 1024) }.getOrNull()
                    if (existing != null) return@runCatching "AGENTS.md 已存在（$where），没有覆盖。"
                    container.workspace.write(
                        "AGENTS.md",
                        "# AGENTS.md\n\n## 约定\n- 回答用中文，先给结论。\n- 改动前先说明要改什么。\n- 命令输出只保留关键几行。\n",
                    )
                    "已生成 $where/AGENTS.md"
                }
            }
            appendLocal(result.getOrElse { "生成失败：${it.message}" })
        }
    }

    private fun exportConversation() {
        val msgs = _state.value.messages
        if (msgs.isEmpty()) {
            appendLocal(L10n.t("还没有内容可导出。"))
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.CHINA)
            .format(java.util.Date())
        val name = "chat-$stamp.md"
        val md = app.pocketagent.android.agent.ExportMarkdown.build(
            title = _state.value.title,
            messages = msgs,
            exportedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date()),
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.workspace.write(name, md)
                    "已导出：${container.workspace.label}/$name（${md.length} 字符）"
                }
            }
            appendLocal(result.getOrElse { "导出失败：${it.message}" })
        }
    }

    /**
     * `/mirror`：把会话镜像到远端工作区，方便在远程机器上直接看到手机产生的最新上下文。
     * 默认关闭——会话里可能包含主机密码等敏感内容，必须由用户显式开启。
     */
    private fun handleMirrorCommand(arg: String) {
        val hostId = container.settings.remoteWorkspaceHostId
        when (arg.trim().lowercase()) {
            "", "status" -> {
                val on = container.settings.mirrorSessionsToRemote
                val target = if (hostId == null) "当前是手机本地工作区" else "当前远端工作区：${_state.value.workspaceLabel}"
                appendLocal(
                    "会话镜像：${if (on) "已开启" else "已关闭"}\n$target\n" +
                        "开启后每轮结束会把会话写到远端工作区 sessions/<会话id>.md。" +
                        "注意：会话里可能有密码等敏感内容，确认远端机器可信再开。\n" +
                        "用法：/mirror on | /mirror off",
                )
            }
            "on", "开", "true", "1" -> {
                if (hostId == null) {
                    appendLocal("当前工作区在手机上，先把 /workspace 切到一台远端机器再开镜像。")
                    return
                }
                container.settings.mirrorSessionsToRemote = true
                appendLocal(
                    "已开启会话镜像：每轮结束写入远端工作区 sessions/。\n" +
                        "会话里如果有密码、私钥等内容也会一起写过去，请确认这台机器可信。",
                )
                mirrorSessionToRemote()
            }
            "off", "关", "false", "0" -> {
                container.settings.mirrorSessionsToRemote = false
                appendLocal("已关闭会话镜像。")
            }
            "pull", "拉", "import", "导入" -> mirrorPullFromRemote()
            else -> appendLocal("用法：/mirror on | /mirror off | /mirror pull")
        }
    }

    /**
     * `/mirror pull`：把远端 sessions/<id>.md 里比手机多的消息并回当前会话。
     * 手机 → 电脑 → 手机 这样来回切换时，电脑上新写的部分靠它回到手机。
     */
    private fun mirrorPullFromRemote() {
        if (container.settings.remoteWorkspaceHostId == null) {
            appendLocal("当前工作区在手机上，先把 /workspace 切到那台远端机器再拉取。")
            return
        }
        viewModelScope.launch {
            val name = "sessions/$conversationId.md"
            val remote = withContext(Dispatchers.IO) {
                runCatching { container.workspace.read(name, 0, 2 * 1024 * 1024) }
            }
            val text = remote.getOrElse {
                appendLocal("读取远端会失败：${it.message ?: it.javaClass.simpleName}")
                return@launch
            }
            if (text.isBlank()) {
                appendLocal("远端还没有这个会话的镜像（$name）。先在手机上聊一轮，或确认 /workspace 指对了机器。")
                return@launch
            }
            val parsed = app.pocketagent.android.agent.SessionMirror.parse(text)
            val (merged, added) = app.pocketagent.android.agent.SessionMirror.merge(
                _state.value.messages,
                parsed,
            )
            if (added == 0) {
                appendLocal("远端没有比手机更新的内容（解析到 ${parsed.size} 条，手机里都已存在）。")
                return@launch
            }
            _state.update { it.copy(messages = merged) }
            conversation = conversation.copy(messages = merged)
            persist()
            appendLocal("已从远端导入 $added 条新消息（来自 $name）。")
        }
    }

    /** 每轮结束后调用：开启镜像且工作区在远端时，把整段会话写到 sessions/<会话id>.md。 */
    private fun mirrorSessionToRemote() {
        if (!container.settings.mirrorSessionsToRemote) return
        if (container.settings.remoteWorkspaceHostId == null) return
        val msgs = _state.value.messages
        if (msgs.isEmpty()) return
        val md = app.pocketagent.android.agent.ExportMarkdown.build(
            title = _state.value.title,
            messages = msgs,
            exportedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date()),
        )
        val name = "sessions/$conversationId.md"
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    // 手机这边是整段覆盖写：先确认电脑没往同一组里加过东西，否则会把它冲掉
                    // 第一次写时远端还没有这个文件，读不到就当作空，照常写
                    val remoteText = runCatching {
                        container.workspace.read(name, 0, MIRROR_CHECK_BYTES)
                    }.getOrDefault("")
                    when {
                        remoteText.length >= MIRROR_CHECK_BYTES - 1_024 -> MirrorWrite.TooBigToCheck
                        remoteText.isBlank() -> {
                            container.workspace.write(name, md)
                            MirrorWrite.Done
                        }
                        app.pocketagent.android.agent.SessionMirror.hasRemoteOnly(
                            msgs,
                            app.pocketagent.android.agent.SessionMirror.parse(remoteText),
                        ) -> MirrorWrite.RemoteNewer
                        else -> {
                            container.workspace.write(name, md)
                            MirrorWrite.Done
                        }
                    }
                }
            }
            outcome.getOrNull()?.let { warnMirrorSkipped(it) }
        }
    }

    private enum class MirrorWrite { Done, RemoteNewer, TooBigToCheck }

    /** 这次没写远端时给个明确提示，别让人以为已经同步过了。 */
    private fun warnMirrorSkipped(kind: MirrorWrite) {
        val notice = when (kind) {
            MirrorWrite.Done -> return
            MirrorWrite.RemoteNewer ->
                "远端这组会话里有手机还没有的内容（电脑上改过），这次没有覆盖写入，免得把电脑上的记录冲掉。\n" +
                    "先执行 /mirror pull 合并，再继续。"
            MirrorWrite.TooBigToCheck ->
                "远端这组会话的镜像超过 1MB，没法先确认是否被电脑改过，这次没有覆盖写入。\n" +
                    "先执行 /mirror pull 把内容并回手机。"
        }
        if (_state.value.messages.lastOrNull()?.text == notice) return
        appendLocal(notice)
    }

    private fun echoCommand(raw: String) {
        // 斜杠命令只是本地操作回显，不是发给模型的用户发言
        // 连按同一个命令（例如反复打开 /permissions 面板）不重复堆气泡。
        val last = _state.value.messages.lastOrNull()
        if (last != null &&
            last.role == Role.USER &&
            last.model == app.pocketagent.android.agent.LOCAL_NOTICE_MODEL &&
            last.text == raw
        ) {
            return
        }
        val msg = ChatMessage(role = Role.USER, text = raw, model = app.pocketagent.android.agent.LOCAL_NOTICE_MODEL)
        _state.update { it.copy(messages = it.messages + msg) }
        conversation = conversation.copy(messages = _state.value.messages)
        persist()
    }

    private fun appendLocal(text: String) {
        val msg = ChatMessage(role = Role.ASSISTANT, text = text, model = "local")
        _state.update { it.copy(messages = it.messages + msg) }
        conversation = conversation.copy(messages = _state.value.messages)
        persist()
    }

    private fun handleModelCommand(arg: String) {
        val providers = _state.value.providers
        if (providers.isEmpty()) {
            appendLocal(L10n.t("还没有任何模型来源。点右上角「调节」→「添加来源」。"))
            return
        }
        if (arg.isBlank()) {
            // 手机上没有 terminal 的行号输入，直接弹可点的模型列表
            openModelPicker()
            return
        }
        val byIndex = arg.toIntOrNull()?.let { providers.getOrNull(it - 1) }
        val target = byIndex
            ?: providers.firstOrNull { it.name.equals(arg, ignoreCase = true) }
            ?: providers.firstOrNull { it.name.contains(arg, ignoreCase = true) }
            ?: providers.firstOrNull { it.model.contains(arg, ignoreCase = true) }
        if (target != null) {
            selectProvider(target.id)
            val keyHint = if (container.secretStore.get(target.id).isNullOrBlank()) "（注意：这个来源还没填 Key）" else ""
            appendLocal("已切换到 ${target.name} · ${target.model}$keyHint")
            return
        }

        // 名字没匹配到来源，再按内置模型目录找：/model gpt6 astra → gpt-6-astra
        val model = matchCatalogModel(arg)
        if (model != null) {
            pickModel(model.id)
            return
        }
        appendLocal(
            "没找到「$arg」。\n来源：" + providers.mapIndexed { i, p -> "${i + 1}.${p.name}" }.joinToString("，") +
                "\n模型可以直接输 /model 打开列表选。",
        )
    }

    /** 宽松匹配：「gpt6 astra」「GPT-6-Astra」「gpt6astra」都能命中 gpt-6-astra。 */
    private fun matchCatalogModel(arg: String): CatalogModel? {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val target = norm(arg)
        if (target.isEmpty()) return null
        val catalog = _state.value.catalog.ifEmpty { container.modelCatalog }
        return catalog.firstOrNull { norm(it.id) == target }
            ?: catalog.firstOrNull { norm(it.displayName) == target }
            ?: catalog.firstOrNull { norm(it.id).contains(target) }
            ?: catalog.firstOrNull { norm(it.displayName).contains(target) }
    }

    private fun toggleFast() {
        val next = !_state.value.fastMode
        _state.update { it.copy(fastMode = next) }
        val provider = _state.value.provider
        if (next && provider != null && provider.kind != app.pocketagent.android.data.ProviderKind.RESPONSES) {
            appendLocal(
                "已打开快速模式，但当前来源走 ${provider.kind}，不支持 service_tier=priority；" +
                    "工具和回答不受影响。",
            )
            return
        }
        appendLocal(
            if (next) {
                "已打开快速模式：使用 service_tier=priority，工具仍保留。" +
                    "该档可能改变服务端费用或排队优先级；再输一次 /fast 关闭。"
            } else {
                "已关闭快速模式：回到服务端默认档。"
            },
        )
    }

    private fun handleToolsCommand(arg: String) {
        val provider = _state.value.provider ?: run {
            appendLocal(L10n.t("还没有模型来源。"))
            return
        }
        val enable = when (arg.lowercase()) {
            "on", "1", "true", "开" -> true
            "off", "0", "false", "关" -> false
            else -> !provider.toolsEnabled
        }
        val updated = provider.copy(toolsEnabled = enable)
        viewModelScope.launch(Dispatchers.IO) { container.providerStore.upsert(updated) }
        _state.update { st ->
            st.copy(
                provider = updated,
                providers = st.providers.map { if (it.id == updated.id) updated else it },
            )
        }
        appendLocal("${provider.name} 的工具已${if (enable) "打开" else "关闭"}。")
    }

    private fun compact() {
        if (job != null) return
        val messages = _state.value.messages
        if (messages.size <= 6) {
            appendLocal("当前只有 ${messages.size} 条消息，还不用压缩。")
            return
        }
        val provider = _state.value.provider
        val key = provider?.let { container.secretStore.get(it.id).orEmpty() }.orEmpty()
        if (provider == null || key.isBlank()) {
            appendLocal(L10n.t("压缩需要当前来源可用（有 Key）。先去配置。"))
            return
        }
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            try {
                val keep = messages.takeLast(4)
                val old = messages.dropLast(4)
                val transcript = old.joinToString("\n\n") { m ->
                    "${m.role.name.lowercase()}: ${m.text}"
                }
                // 长会话分段摘要再合并：原来只截前 24k，等于把最旧的 24k 当全部，
                // 越长的会话丢得越多。现在按 12k 一段、取最近 8 段，最后再合并成一份。
                val chunks = app.pocketagent.android.agent.CompactPlanner.split(transcript)
                val parts = withContext(Dispatchers.IO) {
                    if (chunks.size <= 1) {
                        listOf(summarize(provider, key, chunks.firstOrNull() ?: ""))
                    } else {
                        val partial = chunks.mapIndexed { i, chunk ->
                            summarize(provider, key, "第 ${i + 1}/${chunks.size} 段对话：\n$chunk")
                        }
                        listOf(summarize(provider, key, "把下面这些分段摘要合并成一份完整摘要：\n\n" + partial.joinToString("\n\n")))
                    }
                }
                val summary = parts.joinToString("\n")
                val summaryMsg = compactSummaryMessage(summary, old.size, provider.model)
                val compacted = listOf(summaryMsg) + keep
                _state.update { it.copy(messages = compacted, running = false) }
                conversation = conversation.copy(messages = compacted)
                persist()
            } catch (ce: CancellationException) {
                _state.update { it.copy(running = false) }
                throw ce
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = "压缩失败：${e.message}") }
            } finally {
                job = null
            }
        }
    }

    /**
     * send() 发现历史会被裁掉时调用：把最近 6 条之前的内容分段摘要成一条 USER 消息。
     * 不标 model=local，MessageMapper 才会把它作为上下文真正发给模型。
     */
    private suspend fun autoCompact(
        provider: Provider,
        key: String,
        working: List<ChatMessage>,
    ): List<ChatMessage>? = withContext(Dispatchers.IO) {
        buildAutoCompactHistory(working, provider.model) { transcript ->
            summarize(provider, key, transcript)
        }
    }

    private suspend fun summarize(provider: Provider, key: String, transcript: String): String {
        val client = LlmClients.of(provider.kind)
        val out = StringBuilder()
        client.stream(
            LlmConfig(provider.copy(toolsEnabled = false, maxSteps = 1), key),
            "你是上下文压缩器。把下面的对话压成要点：用户目标、已确认的事实、做过的改动、未完成的事。中文，300-600 字，不要寒暄。",
            listOf(ApiMessage(role = "user", content = transcript)),
            emptyList(),
        ).collect { ev ->
            if (ev is LlmEvent.TextDelta) out.append(ev.text)
        }
        val text = out.toString().trim()
        if (text.isBlank()) throw IllegalStateException("模型没有返回摘要")
        return text
    }

    /**
     * /test models：把目录里所有中转站模型挨个实测一遍（用当前来源的协议和 Key），
     * 结果存起来，/model 列表里会给失败的模型打标记。
     */
    fun testModels() {
        // 结果写进对话，弹层必须先收起来，否则用户看不到任何反馈
        _state.update { it.copy(modelPicker = false, pendingModel = null) }
        if (job != null) {
            appendLocal(L10n.t("当前还有任务在跑，先点「停止」再实测。"))
            return
        }
        val provider = _state.value.provider ?: run {
            appendLocal(L10n.t("先选一个来源（/model）。"))
            return
        }
        val key = container.secretStore.get(provider.id).orEmpty()
        if (key.isBlank()) {
            appendLocal("${provider.name} 还没填 Key。")
            return
        }
        val models = _state.value.catalog.filter { it.inRelay }
        if (models.isEmpty()) {
            appendLocal(L10n.t("模型目录是空的。"))
            return
        }
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            appendLocal("开始实测 ${models.size} 个模型（${provider.kind} 协议）…")
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    models.chunked(4).flatMap { batch ->
                        batch.map { m ->
                            async {
                                // 和中转站实测一致：claude → messages，其余 → responses
                                val kind = ModelCatalog.kindFor(provider.baseUrl, provider.kind, m.id)
                                val base = if (kind == app.pocketagent.android.data.ProviderKind.ANTHROPIC) {
                                    provider.baseUrl.removeSuffix("/v1")
                                } else {
                                    provider.baseUrl
                                }
                                val err = runCatching {
                                    LlmClients.of(kind).probe(
                                        LlmConfig(
                                            provider.copy(kind = kind, baseUrl = base, model = m.id, toolsEnabled = false),
                                            key,
                                        ),
                                    )
                                }.getOrElse { e -> e.message ?: "失败" }
                                app.pocketagent.android.data.ModelProbe(
                                    model = m.id,
                                    ok = err == null,
                                    error = err.orEmpty().take(160),
                                )
                            }
                        }.awaitAll()
                    }
                }
            }
            val saved = withContext(Dispatchers.IO) { container.modelProbeStore.put(results) }
            _state.update { it.copy(running = false, probes = saved) }
            val ok = results.count { it.ok }
            appendLocal(
                "模型实测：$ok/${results.size} 可用\n" +
                    results.joinToString("\n") { (if (it.ok) "✅ " else "❌ ") + it.model + (if (it.ok) "" else " → " + it.error) },
            )
        }
    }

    private fun testAllProviders() {
        if (job != null) return
        val providers = _state.value.providers
        if (providers.isEmpty()) {
            appendLocal(L10n.t("还没有模型来源。"))
            return
        }
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    providers.map { p ->
                        async {
                            val key = container.secretStore.get(p.id).orEmpty()
                            if (key.isBlank()) {
                                p to "未配置 Key"
                            } else {
                                val err = runCatching {
                                    LlmClients.of(p.kind).probe(LlmConfig(p, key))
                                }.getOrElse { e -> e.message ?: "失败" }
                                p to (err ?: "OK")
                            }
                        }
                    }.awaitAll()
                }
            }
            _state.update { it.copy(running = false) }
            appendLocal(
                L10n.t("连接测试：\n") + results.joinToString("\n") { (p, r) ->
                    "${if (r == "OK") "✅" else "❌"} ${p.name} · ${p.model} → $r"
                },
            )
        }
    }

    private fun persist() {
        val snapshot = conversation.copy(
            messages = _state.value.messages,
            updatedAt = System.currentTimeMillis(),
        )
        conversation = snapshot
        persistChannel.trySend(snapshot)
    }
}

internal fun shouldUseRemoteCodexHarness(
    hasRemoteThread: Boolean,
    detectedAvailable: Boolean? = null,
): Boolean = hasRemoteThread || detectedAvailable == true

/**
 * 0.1.47 之前保存的会话只有 remoteThreadId/remoteHostId，没有 workspaceHostId。
 * 两者同时存在就说明它确实在远端原生 session 上跑过，升级后应继续留在那台主机。
 */
internal fun workspaceHostFromRemoteSession(
    remoteThreadId: String?,
    remoteHostId: String?,
): String? = remoteHostId?.takeIf { it.isNotBlank() && !remoteThreadId.isNullOrBlank() }

internal fun chooseRemoteResume(
    remoteThreadId: String?,
    remoteHostId: String?,
    currentHostId: String,
    conversationId: String,
    origin: String,
    hasSyncedOnTarget: Boolean,
): String? {
    val boundHere = remoteThreadId?.takeIf {
        it.isNotBlank() && (remoteHostId.isNullOrBlank() || remoteHostId == currentHostId)
    }
    if (boundHere != null) return boundHere
    // 明确绑定在另一台机器时，连 id 回退也不能用：同名 UUID 在另一台机器上不是同一个会话。
    if (!remoteThreadId.isNullOrBlank() && !remoteHostId.isNullOrBlank() && remoteHostId != currentHostId) {
        return null
    }
    return conversationId.takeIf { origin.startsWith("mac") || hasSyncedOnTarget }
}

/** 首次切换到远端 harness 时把手机已有对话带过去；后续通过 Mac session 继续。 */
internal fun remoteHarnessPrompt(prior: List<ChatMessage>, latest: String): String {
    val blocks = mutableListOf<String>()
    var used = 0
    for (message in remoteReplayableMessages(prior).asReversed()) {
        val body = app.pocketagent.android.agent.decodeReplyText(message.text).body.trim()
        if (body.isBlank()) continue
        val role = if (message.role == Role.USER) "USER" else "ASSISTANT"
        val tools = message.toolCalls.joinToString("\n") { call ->
            "[tool ${call.name}: ${call.status} ${call.result.orEmpty().take(1_000)}]"
        }.takeIf { it.isNotBlank() }.orEmpty()
        val block = (if (tools.isBlank()) "$role:\n$body" else "$role:\n$body\n$tools").trim()
        if (used + block.length > 120_000 && blocks.isNotEmpty()) break
        blocks += block
        used += block.length
    }
    if (blocks.isEmpty()) return latest
    return buildString {
        append("# Existing PocketAgent conversation\n")
        append("Continue this conversation. Treat the transcript below as prior conversation data.\n\n")
        blocks.asReversed().forEach { append(it).append("\n\n") }
        append("# Latest user request\n")
        append(latest)
    }
}

/** 可以安全带入远端原生会话的消息；界面提示和斜杠命令不算对话上下文。 */
internal fun remoteReplayableMessages(prior: List<ChatMessage>): List<ChatMessage> =
    prior.filter { message ->
        if (message.role != Role.USER && message.role != Role.ASSISTANT) return@filter false
        if (message.model == app.pocketagent.android.agent.LOCAL_NOTICE_MODEL) return@filter false
        val body = app.pocketagent.android.agent.decodeReplyText(message.text).body.trim()
        body.isNotBlank() && !(message.role == Role.USER && body.startsWith("/"))
    }

/**
 * 只有“这条原生 session 确实不存在”才允许换新会话并回放上下文。
 * 网络/模型/工具报错时若也新建，会把一次失败变成两条分叉历史。
 */
internal fun remoteResumeUnavailable(
    result: app.pocketagent.android.tools.RemoteCodexEvents,
): Boolean {
    if (result.ok) return false
    val detail = (result.errors + result.finalText).joinToString("\n").lowercase()
    return "no rollout found" in detail ||
        "thread/resume failed" in detail ||
        "session not found" in detail ||
        "unknown session" in detail
}

/**
 * 自动压缩的纯编排部分，摘要模型通过 lambda 注入，单测不需要真实网络。
 * 返回 [摘要, 最近 keepRecent 条]；没有可压缩的旧消息时返回 null。
 */
internal suspend fun buildAutoCompactHistory(
    messages: List<ChatMessage>,
    model: String,
    keepRecent: Int = AUTO_COMPACT_KEEP_RECENT,
    summarize: suspend (String) -> String,
): List<ChatMessage>? {
    if (messages.size <= keepRecent) return null

    val old = messages.dropLast(keepRecent)
    val keep = messages.takeLast(keepRecent)
    val transcript = autoCompactTranscript(old)
    if (transcript.isBlank()) return null

    val chunks = app.pocketagent.android.agent.CompactPlanner.split(transcript)
    if (chunks.isEmpty()) return null
    val parts = if (chunks.size == 1) {
        listOf(summarize(chunks.single()))
    } else {
        val partial = chunks.mapIndexed { index, chunk ->
            summarize("第 ${index + 1}/${chunks.size} 段对话：\n$chunk")
        }
        listOf(summarize("把下面这些分段摘要合并成一份完整摘要：\n\n" + partial.joinToString("\n\n")))
    }
    val summary = parts.joinToString("\n").trim()
    if (summary.isBlank()) throw IllegalStateException("模型没有返回摘要")

    val summaryMessage = compactSummaryMessage(summary, old.size, model)
    return listOf(summaryMessage) + keep
}

/** 摘要必须作为可回灌的上下文，不能标成 local，否则压缩后旧历史就真的丢失了。 */
internal fun compactSummaryMessage(
    summary: String,
    oldMessageCount: Int,
    model: String,
): ChatMessage = ChatMessage(
    role = Role.USER,
    text = "〔前面 $oldMessageCount 条对话的压缩摘要，继续任务时以此为准〕\n\n${summary.trim()}",
    model = model.takeIf {
        it.isNotBlank() && it != app.pocketagent.android.agent.LOCAL_NOTICE_MODEL
    },
)

/** 本地提示、斜杠命令和空消息不进入摘要，避免把 UI 噪音当成对话事实。 */
private fun autoCompactTranscript(messages: List<ChatMessage>): String = buildList {
    for (message in messages) {
        if (message.model == app.pocketagent.android.agent.LOCAL_NOTICE_MODEL) continue
        if (message.role == Role.USER && message.text.trimStart().startsWith("/")) continue

        val text = message.text.trim()
        val toolNotes = message.toolCalls.joinToString("；") { call ->
            "${call.name}(${call.arguments.take(300)}) → ${call.status}" +
                call.result?.let { " ${it.take(500)}" }.orEmpty()
        }
        val imageNote = if (message.images.isEmpty()) "" else "〔图片 ${message.images.size} 张〕"
        if (text.isBlank() && toolNotes.isBlank() && imageNote.isBlank()) continue

        add(
            buildString {
                append(message.role.name.lowercase()).append(": ").append(text)
                if (imageNote.isNotBlank()) append("\n").append(imageNote)
                if (toolNotes.isNotBlank()) append("\n〔工具〕").append(toolNotes)
            },
        )
    }
}.joinToString("\n\n")
