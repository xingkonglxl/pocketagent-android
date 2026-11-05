package app.pocketagent.android.ui

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.pocketagent.android.AppContainer
import app.pocketagent.android.BuildConfig
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderPresets
import app.pocketagent.android.data.ThemeMode
import app.pocketagent.android.security.biometricAvailable
import app.pocketagent.android.security.maskSecret
import app.pocketagent.android.security.promptBiometric
import app.pocketagent.android.ui.chat.ChatScreen
import app.pocketagent.android.ui.chat.ChatViewModel
import app.pocketagent.android.ui.components.ApprovalDialog
import app.pocketagent.android.ui.home.HomeScreen
import app.pocketagent.android.ui.home.HomeViewModel
import app.pocketagent.android.ui.legal.ConsentScreen
import app.pocketagent.android.ui.legal.LegalDocs
import app.pocketagent.android.ui.legal.LegalScreen
import app.pocketagent.android.ui.providers.ProviderEditScreen
import app.pocketagent.android.ui.providers.ProvidersScreen
import app.pocketagent.android.ui.providers.ProvidersViewModel
import app.pocketagent.android.ui.settings.SettingsScreen
import app.pocketagent.android.ui.i18n.tr

private const val ROUTE_HOME = "home"
private const val ROUTE_CHAT = "chat"
private const val ROUTE_PROVIDERS = "providers"
private const val ROUTE_PROVIDER_EDIT = "providerEdit"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_REMOTE = "remote"
private const val ROUTE_MCP = "mcp"
private const val ROUTE_SYNC = "sync"
private const val ROUTE_PRIVACY = "privacy"
private const val ROUTE_TERMS = "terms"
private const val ROUTE_LICENSES = "licenses"

/** 同意书版本：隐私政策或用户协议有实质修改时加一，用户会重新看到确认弹窗。 */
private const val LEGAL_VERSION = 1

@Composable
fun AppRoot(
    container: AppContainer,
    activity: FragmentActivity,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    language: String = container.settings.language,
    onLanguageChange: (String) -> Unit = {},
) {
    var route by rememberSaveable { mutableStateOf(ROUTE_HOME) }
    var routeArg by rememberSaveable { mutableStateOf<String?>(null) }
    var secureScreen by remember { mutableStateOf(container.settings.secureScreen) }
    var legalAccepted by remember { mutableStateOf(container.settings.legalVersion >= LEGAL_VERSION) }
    var legalDoc by remember { mutableStateOf<String?>(null) }

    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(container) } },
    )
    val providersVm: ProvidersViewModel = viewModel(
        factory = viewModelFactory { initializer { ProvidersViewModel(container) } },
    )
    val remoteVm: app.pocketagent.android.ui.remote.RemoteViewModel = viewModel(
        factory = viewModelFactory { initializer { app.pocketagent.android.ui.remote.RemoteViewModel(container) } },
    )
    val customModelsVm: app.pocketagent.android.ui.settings.CustomModelsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { app.pocketagent.android.ui.settings.CustomModelsViewModel(container) }
        },
    )
    val syncVm: app.pocketagent.android.ui.sync.SyncViewModel = viewModel(
        factory = viewModelFactory { initializer { app.pocketagent.android.ui.sync.SyncViewModel(container) } },
    )
    val mcpVm: app.pocketagent.android.ui.mcp.McpViewModel = viewModel(
        factory = viewModelFactory { initializer { app.pocketagent.android.ui.mcp.McpViewModel(container) } },
    )
    val homeConversations by homeVm.conversations.collectAsStateWithLifecycle()
    val homeProviders by homeVm.providers.collectAsStateWithLifecycle()
    val providersState by providersVm.state.collectAsStateWithLifecycle()
    val remoteState by remoteVm.state.collectAsStateWithLifecycle()
    val syncState by syncVm.state.collectAsStateWithLifecycle()
    val customModelsState by customModelsVm.state.collectAsStateWithLifecycle()
    val mcpState by mcpVm.state.collectAsStateWithLifecycle()

    LaunchedEffect(route) {
        when (route) {
            ROUTE_HOME -> homeVm.refresh()
            ROUTE_PROVIDERS -> providersVm.refresh()
            ROUTE_REMOTE -> remoteVm.refresh()
            ROUTE_SYNC -> syncVm.refresh()
            ROUTE_MCP -> mcpVm.refresh()
        }
    }

    // 回到前台也刷一次：同步可能在别处改了会话列表（比如在 Mac 上跑完再切回来）
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (route == ROUTE_HOME) homeVm.refresh()
    }

    val secure = secureScreen || route in setOf(ROUTE_PROVIDER_EDIT, ROUTE_REMOTE, ROUTE_MCP)
    DisposableEffect(secure, activity) {
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { ActivityCleanup(activity) }
    }

    var unlocked by remember { mutableStateOf(!container.settings.appLock) }
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        if (container.settings.appLock) unlocked = false
    }

    if (!legalAccepted) {
        // 首次启动：先确认隐私政策与用户协议，不同意就退出（合规要求先同意再处理任何信息）
        // 用整屏页面而不是弹窗：点「隐私政策/用户协议」时正文整页显示，不会被浮层挡住
        when (legalDoc) {
            "privacy" -> LegalScreen(
                title = tr("隐私政策"),
                body = LegalDocs.privacy(),
                onBack = { legalDoc = null },
            )
            "terms" -> LegalScreen(
                title = tr("用户协议"),
                body = LegalDocs.terms(),
                onBack = { legalDoc = null },
            )
            else -> ConsentScreen(
                onAccept = {
                    container.settings.legalVersion = LEGAL_VERSION
                    legalAccepted = true
                },
                onDecline = { activity.finish() },
                onOpenPrivacy = { legalDoc = "privacy" },
                onOpenTerms = { legalDoc = "terms" },
            )
        }
    } else if (!unlocked) {
        LockScreen(
            onUnlock = {
                promptBiometric(activity) { ok -> if (ok) unlocked = true }
            },
        )
    } else {
        when (route) {
            ROUTE_HOME -> HomeScreen(
                conversations = homeConversations,
                providers = homeProviders,
                onOpen = { id -> routeArg = id; route = ROUTE_CHAT },
                onNew = {
                    homeVm.createConversation { id ->
                        routeArg = id
                        route = ROUTE_CHAT
                    }
                },
                onDelete = homeVm::delete,
                onProviders = { route = ROUTE_PROVIDERS },
                onSettings = { route = ROUTE_SETTINGS },
            )

            ROUTE_CHAT -> {
                val id = routeArg
                if (id == null) {
                    route = ROUTE_HOME
                } else {
                    val chatVm: ChatViewModel = viewModel(
                        key = "chat-$id",
                        factory = viewModelFactory {
                            initializer { ChatViewModel(container, id) }
                        },
                    )
                    val chatState by chatVm.state.collectAsStateWithLifecycle()
                    ChatScreen(
                        state = chatState,
                        onBack = { route = ROUTE_HOME },
                        onOpenProviders = { route = ROUTE_PROVIDERS },
                        onSend = chatVm::send,
                        onStop = chatVm::stop,
                        onClearQueue = chatVm::clearQueuedSends,
                        onSelectProvider = chatVm::selectProvider,
                        onClear = chatVm::clearMessages,
                        onDismissError = chatVm::dismissError,
                        onRetry = chatVm::retryLast,
                        onDismissModelPicker = chatVm::dismissModelPicker,
                        onPickProvider = chatVm::pickModelFromSheet,
                        onPickModel = chatVm::pickModel,
                        onPickReasoning = chatVm::setReasoning,
                        onPickLevel = chatVm::confirmModelLevel,
                        onBackToModelList = chatVm::backToModelList,
                        onSendImages = chatVm::send,
                        onDismissWorkspace = chatVm::dismissWorkspaceSheet,
                        onPickWorkspace = chatVm::switchWorkspace,
                        onDismissReasoning = chatVm::dismissReasoningPicker,
                        onPickReasoningLevel = chatVm::pickReasoningLevel,
                        onAddCustomModel = chatVm::addCustomModel,
                        onDismissApprovals = chatVm::dismissApprovalsPicker,
                        onPickApprovals = chatVm::pickApprovals,
                        onDismissPermissions = chatVm::dismissPermissionsSheet,
                        onTogglePermission = chatVm::togglePermission,
                        onDismissGoal = chatVm::dismissGoalEditor,
                        onSaveGoal = chatVm::saveGoal,
                        onFinishGoal = chatVm::finishGoal,
                        onContinueGoal = chatVm::continueGoal,
                        onTestModels = chatVm::testModels,
                    )
                }
            }

            ROUTE_PROVIDERS -> ProvidersScreen(
                state = providersState,
                onBack = { route = ROUTE_HOME },
                onAdd = { routeArg = null; route = ROUTE_PROVIDER_EDIT },
                onEdit = { id -> routeArg = id; route = ROUTE_PROVIDER_EDIT },
                onDelete = providersVm::delete,
                onProbe = providersVm::probe,
                onDismissMessage = providersVm::clearMessage,
                onAddVariants = providersVm::addRelayVariants,
            )

            ROUTE_PROVIDER_EDIT -> {
                val editing: Provider? = providersState.providers.firstOrNull { it.id == routeArg }
                val initial = editing ?: ProviderPresets.custom()
                ProviderEditScreen(
                    initial = initial,
                    isNew = editing == null,
                    existingKeyMask = editing?.let {
                        maskSecret(container.secretStore.get(it.id))
                    },
                    onBack = { route = ROUTE_PROVIDERS },
                    onSave = { provider, apiKey ->
                        providersVm.save(provider, apiKey)
                        container.settings.lastProviderId = provider.id
                        route = ROUTE_PROVIDERS
                    },
                )
            }

            ROUTE_SETTINGS -> SettingsScreen(
                theme = themeMode,
                language = language,
                onLanguage = onLanguageChange,
                appLock = container.settings.appLock,
                secureScreen = secureScreen,
                biometricReady = biometricAvailable(activity),
                workspacePath = container.workspace.label,
                version = BuildConfig.VERSION_NAME,
                onTheme = onThemeChange,
                onAppLock = { container.settings.appLock = it },
                onSecureScreen = {
                    container.settings.secureScreen = it
                    secureScreen = it
                },
                onClearWorkspace = {
                    container.workspaceDir.listFiles()?.forEach { f -> f.deleteRecursively() }
                },
                onOpenProviders = { route = ROUTE_PROVIDERS },
                onOpenRemote = { route = ROUTE_REMOTE },
                onOpenMcp = { route = ROUTE_MCP },
                onOpenSync = { route = ROUTE_SYNC },
                // 公开包（-PshareBuild=true）不展示 SSH 远程与同步：类目敏感，审核风险高
                showRemote = !BuildConfig.SHARE_BUILD,
                showSync = !BuildConfig.SHARE_BUILD,
                customModelsState = customModelsState,
                onAddCustomModel = customModelsVm::add,
                onRemoveCustomModel = customModelsVm::remove,
                onCleanupCustomModels = customModelsVm::cleanup,
                onHideModel = customModelsVm::hide,
                onRestoreAllModels = customModelsVm::restoreAll,
                onDismissCustomMessage = customModelsVm::clearMessage,
                onBack = { route = ROUTE_HOME },
            )

            ROUTE_SYNC -> app.pocketagent.android.ui.sync.SyncScreen(
                state = syncState,
                onBack = { route = ROUTE_SETTINGS },
                onSync = syncVm::syncNow,
                onSyncOne = syncVm::syncOne,
                onSelectHost = syncVm::selectHost,
                onToggle = syncVm::toggle,
                onSelectAll = syncVm::selectAll,
                onClearSelection = syncVm::clearSelection,
                onDeleteSelected = syncVm::deleteSelected,
                onResolve = syncVm::resolve,
                onResolveAll = syncVm::resolveAll,
                onDismissMessage = syncVm::dismissMessage,
                onDismissLastResult = syncVm::dismissLastResult,
                onOpenTrash = syncVm::openTrash,
                onCloseTrash = syncVm::closeTrash,
                onToggleTrash = syncVm::toggleTrash,
                onSelectAllTrash = syncVm::selectAllTrash,
                onRestoreSelectedTrash = syncVm::restoreSelectedTrash,
                onPermanentlyDeleteSelectedTrash = syncVm::permanentlyDeleteSelectedTrash,
                onCancelSync = syncVm::cancelSync,
            )

            ROUTE_REMOTE -> app.pocketagent.android.ui.remote.RemoteScreen(
                state = remoteState,
                onBack = { route = ROUTE_SETTINGS },
                onSave = remoteVm::save,
                onDelete = remoteVm::delete,
                onTest = remoteVm::test,
            )

            ROUTE_MCP -> app.pocketagent.android.ui.mcp.McpScreen(
                state = mcpState,
                onBack = { route = ROUTE_SETTINGS },
                onSave = mcpVm::save,
                onDelete = mcpVm::delete,
                onTest = mcpVm::test,
                onToggle = mcpVm::setEnabled,
                onRefreshTools = mcpVm::refreshTools,
            )

            ROUTE_PRIVACY -> LegalScreen(
                title = tr("隐私政策"),
                body = LegalDocs.privacy(),
                onBack = { route = ROUTE_SETTINGS },
            )

            ROUTE_TERMS -> LegalScreen(
                title = tr("用户协议"),
                body = LegalDocs.terms(),
                onBack = { route = ROUTE_SETTINGS },
            )

            ROUTE_LICENSES -> LegalScreen(
                title = tr("开源许可"),
                body = LegalDocs.oss(),
                onBack = { route = ROUTE_SETTINGS },
            )
        }
    }

    val pending by container.approvalGate.pending.collectAsStateWithLifecycle()
    pending?.let { request ->
        ApprovalDialog(
            request = request,
            onDecision = { allow -> container.approvalGate.answer(allow) },
            onAllowSession = if (request.sessionAllowable) {
                { container.approvalGate.allowAllForSession() }
            } else {
                null
            },
        )
    }
}

private fun ActivityCleanup(activity: FragmentActivity) {
    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(tr("PocketAgent 已锁定"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                tr("Key 和对话都在本机，解锁后才能继续。"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onUnlock) { Text(tr("用指纹/面容解锁")) }
        }
    }
}

