package app.pocketagent.android

import android.content.Context
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.ChatEngine
import app.pocketagent.android.agent.UiApprovalGate
import app.pocketagent.android.data.ConversationStore
import app.pocketagent.android.data.ModelProbeStore
import app.pocketagent.android.data.ProviderStore
import app.pocketagent.android.data.SettingsStore
import app.pocketagent.android.data.SyncStore
import app.pocketagent.android.mcp.McpServerStore
import app.pocketagent.android.mcp.McpToolManager
import app.pocketagent.android.remote.MacSyncBackend
import app.pocketagent.android.security.KeystoreSecretStore
import app.pocketagent.android.security.SecretStore
import app.pocketagent.android.sync.SyncService
import app.pocketagent.android.tools.ApplyPatchTool
import app.pocketagent.android.tools.ExecCommandTool
import app.pocketagent.android.tools.FsDeleteTool
import app.pocketagent.android.tools.FsEditTool
import app.pocketagent.android.tools.ShellExecTool
import app.pocketagent.android.tools.SkillReadTool
import app.pocketagent.android.tools.FsGlobTool
import app.pocketagent.android.tools.FsSearchTool
import app.pocketagent.android.tools.FsListTool
import app.pocketagent.android.tools.FsReadTool
import app.pocketagent.android.tools.FsWriteTool
import app.pocketagent.android.tools.HttpGetTool
import app.pocketagent.android.tools.LocalWorkspace
import app.pocketagent.android.tools.PdfReadTool
import app.pocketagent.android.tools.ProcessSessionManager
import app.pocketagent.android.tools.RemoteExecTool
import app.pocketagent.android.tools.RemoteCodexTool
import app.pocketagent.android.tools.RemoteListTool
import app.pocketagent.android.tools.RemoteReadTool
import app.pocketagent.android.tools.RemoteWriteTool
import app.pocketagent.android.tools.ToolRegistry
import app.pocketagent.android.tools.UpdatePlanTool
import app.pocketagent.android.tools.ViewImageTool
import app.pocketagent.android.tools.WebSearchTool
import app.pocketagent.android.tools.WriteStdinTool
import app.pocketagent.android.tools.Workspace
import app.pocketagent.android.tools.WorkspaceSwitcher
import app.pocketagent.android.remote.RemoteConfigStore
import app.pocketagent.android.remote.RemoteManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context, secretStoreOverride: SecretStore? = null) {
    val appContext: Context = context.applicationContext
    /** 远端 harness 必须比 Activity/ViewModel 活得久；完成结果直接写会话存储。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 内置模型目录（assets/model_catalog.json），/model 用 */
    val modelCatalog: List<app.pocketagent.android.data.CatalogModel>
        get() = app.pocketagent.android.data.ModelCatalog.all(appContext)

    val secretStore: SecretStore = secretStoreOverride ?: KeystoreSecretStore(appContext)

    /** 会话/同步状态加密落盘（Keystore 主密钥）；测试里用内存 store 时不加密 */
    // 只用来把老版本写的密文读出来；新写入一律明文（见 writeStore），
    // 避免「Keystore 取不到 → 读成空 → 写回空」这种丢历史的情况。
    private val storeCipher: app.pocketagent.android.data.StoreCipher? = runCatching {
        (secretStore as? KeystoreSecretStore)?.storeCipher()
    }.getOrNull()

    val providerStore = ProviderStore(
        File(appContext.filesDir, "store"),
        catalog = app.pocketagent.android.data.ModelCatalog.all(appContext),
    )
    val conversationStore = ConversationStore(File(appContext.filesDir, "store"), cipher = storeCipher)
    private val syncStoreDir = File(appContext.filesDir, "store")
    val syncStore = SyncStore(syncStoreDir, cipher = storeCipher)
    private val scopedSyncStores = java.util.concurrent.ConcurrentHashMap<String, SyncStore>()

    /** 每个同步目标单独一份 base/conflicts/deleted，切换远端不会串上下文 */
    fun syncStoreFor(hostId: String?): SyncStore =
        if (hostId.isNullOrBlank()) syncStore
        else scopedSyncStores.getOrPut(hostId) { SyncStore(syncStoreDir, cipher = storeCipher, scope = hostId) }
    val modelProbeStore = ModelProbeStore(File(appContext.filesDir, "store"))
    val customModelStore = app.pocketagent.android.data.CustomModelStore(File(appContext.filesDir, "store"))
    val hiddenModelStore = app.pocketagent.android.data.HiddenModelStore(File(appContext.filesDir, "store"))
    val settings = SettingsStore(appContext)

    val workspaceDir: File = File(appContext.filesDir, "workspace").apply { mkdirs() }

    val remoteStore = RemoteConfigStore(File(appContext.filesDir, "store"))
    val remoteManager = RemoteManager(
        remoteStore,
        secretStore,
        knownHostsFile = File(appContext.filesDir, "store/ssh_known_hosts"),
    )
    private val syncHelperScript: String? by lazy {
        runCatching {
            appContext.assets.open("pocketagent-export-context").bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /** 手机 ⇄ Mac 的会话双向同步（每组会话独立，冲突等用户选） */
    val syncService = SyncService(
        store = syncStore,
        conversations = conversationStore,
        backend = MacSyncBackend(remoteManager, helperScript = syncHelperScript),
        storeForHost = ::syncStoreFor,
    )

    /** 文件工具的工作区：默认手机本地，可在 /workspace 里切到远端主机 */
    val workspace: Workspace = WorkspaceSwitcher(settings, LocalWorkspace(workspaceDir), remoteManager)
    /** 手机本地后台命令会话；exec_command / write_stdin 共用。 */
    val processSessions = ProcessSessionManager(workspaceDir)
    val remoteCodexTool = RemoteCodexTool(
        remoteManager,
        workspaceHostId = { settings.remoteWorkspaceHostId },
        workspaceDir = { settings.remoteWorkspaceDir },
    )

    /** 用户配置的 MCP 服务器；工具列表按 TTL 缓存，动态挂在 [toolRegistry] 上。 */
    val mcpStore = McpServerStore(File(appContext.filesDir, "store"))
    val mcpTools = McpToolManager(mcpStore, secretStore)

    val toolRegistry = ToolRegistry(
        listOf(
            FsListTool(workspace),
            FsReadTool(workspace),
            FsGlobTool(workspace),
            FsSearchTool(workspace),
            FsEditTool(workspace),
            ApplyPatchTool(workspace),
            ShellExecTool(workspaceDir),
            ExecCommandTool(processSessions),
            WriteStdinTool(processSessions),
            SkillReadTool(workspace),
            FsWriteTool(workspace),
            FsDeleteTool(workspace),
            PdfReadTool(workspace),
            ViewImageTool(workspace),
            UpdatePlanTool(),
            HttpGetTool(),
            WebSearchTool(),
            RemoteExecTool(remoteManager),
            RemoteReadTool(remoteManager),
            RemoteWriteTool(remoteManager),
            RemoteListTool(remoteManager),
            remoteCodexTool,
        ),
        dynamicTools = { mcpTools.tools() },
    )

    val approvalGate = UiApprovalGate()
    val runner = AgentRunner(toolRegistry, approvalGate)
    val chatEngine = ChatEngine(runner)

    init {
        // MCP 发现要走网络，放到后台；失败不影响 App 启动，用户可在 MCP 设置里重试。
        if (mcpStore.load().any { it.enabled && it.url.isNotBlank() }) {
            appScope.launch { runCatching { mcpTools.ensureFresh(force = true) } }
        }
        // 中转站没有 gpt-5-codex / claude-sonnet-4-5 的 channel（会 503 / model_not_found），
        // 老配置启动时自动换成实测可用的模型，免得用户以为「harness 坏了」。
        runCatching {
            val known = mapOf(
                "gpt-5-codex" to "gpt-6-astra",
                "claude-sonnet-4-5" to "claude-opus-4-8",
            )
            val providers = providerStore.load()
            val fixed = providers.map { p ->
                val replacement = known[p.model]
                if (app.pocketagent.android.data.ProviderPresets.isRelay(p.baseUrl) && replacement != null) {
                    p.copy(model = replacement)
                } else {
                    p
                }
            }
            if (fixed != providers) fixed.forEach { providerStore.upsert(it) }
        }
    }
}
