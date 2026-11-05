package app.pocketagent.android.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pocketagent.android.AppContainer
import app.pocketagent.android.data.SyncConflict
import app.pocketagent.android.sync.SyncService
import app.pocketagent.android.sync.TrashContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.pocketagent.android.ui.i18n.L10n

data class SyncRow(
    val id: String,
    val title: String,
    val origin: String,
    val updatedAt: Long,
    val count: Int,
    val conflict: Boolean,
) {
    val originLabel: String
        get() = if (origin.startsWith("mac")) L10n.t("来自远端") else L10n.t("手机")

    val timeLabel: String
        get() = runCatching {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updatedAt))
        }.getOrDefault("")
}

data class SyncHostOption(
    val id: String,
    val name: String,
    val target: String,
)

data class SyncUiState(
    val rows: List<SyncRow> = emptyList(),
    val selected: Set<String> = emptySet(),
    val conflicts: List<SyncConflict> = emptyList(),
    /** 单条冲突处理失败的原因，直接显示在那张卡片上，避免看起来「点了没反应」 */
    val conflictErrors: Map<String, String> = emptyMap(),
    /** 上一次同步/冲突处理的结果，持久化，不会像提示条那样几秒后消失 */
    val lastResult: String = "",
    val lastResultLabel: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val hosts: List<SyncHostOption> = emptyList(),
    val selectedHostId: String? = null,
    val hostName: String = "",
    val lastSyncLabel: String = "",
    val deletedCount: Int = 0,
    val trashOpen: Boolean = false,
    val trashLoading: Boolean = false,
    val trash: List<TrashContext> = emptyList(),
    val trashSelected: Set<String> = emptySet(),
    val trashError: String? = null,
    val trashProgress: String = "",
)

class SyncViewModel(private val container: AppContainer) : ViewModel() {

    private var syncJob: kotlinx.coroutines.Job? = null
    private var refreshGeneration = 0L

    private val _state = MutableStateFlow(SyncUiState())
    val state = _state.asStateFlow()

    /** 当前明确选中的同步目标；none 表示还没选，不自动猜 Mac */
    private fun selectedHost(): app.pocketagent.android.remote.SshHost? {
        val id = container.settings.syncHostId ?: return null
        return container.remoteManager.host(id)
    }

    fun selectHost(hostId: String) {
        container.settings.syncHostId = hostId
        _state.update {
            it.copy(
                selectedHostId = hostId,
                message = null,
                conflicts = emptyList(),
                conflictErrors = emptyMap(),
                trashOpen = false,
                trash = emptyList(),
                trashSelected = emptySet(),
                trashError = null,
            )
        }
        refresh()
    }

    fun refresh() {
        val generation = ++refreshGeneration
        viewModelScope.launch {
            val host = selectedHost()
            val (conversations, syncState) = withContext(Dispatchers.IO) {
                container.conversationStore.load() to container.syncStoreFor(host?.id).load()
            }
            val conflicts = syncState.conflicts
            val hosts = container.remoteManager.hosts().map {
                SyncHostOption(it.id, it.name.ifBlank { it.host }, it.target)
            }
            if (generation != refreshGeneration || host?.id != selectedHost()?.id) return@launch
            _state.update {
                it.copy(
                    rows = conversations.map { c ->
                        SyncRow(
                            id = c.id,
                            title = c.title,
                            origin = c.origin,
                            updatedAt = c.updatedAt,
                            count = c.messages.size,
                            conflict = conflicts.any { k -> k.id == c.id },
                        )
                    },
                    conflicts = conflicts,
                    hosts = hosts,
                    selectedHostId = host?.id,
                    hostName = host?.let { it.name.ifBlank { it.host } }.orEmpty(),
                    lastSyncLabel = if (syncState.lastSyncAt > 0) {
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(syncState.lastSyncAt))
                    } else "",
                    deletedCount = syncState.deleted.size,
                    lastResult = container.settings.syncLastResult,
                    lastResultLabel = container.settings.syncLastResultAt
                        .takeIf { it > 0 }
                        ?.let { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(it)) }
                        .orEmpty(),
                )
            }
        }
    }

    /** 结果写两处：内存里立刻显示，SharedPreferences 里留着下次打开也能看。 */
    private fun recordResult(text: String) {
        val now = System.currentTimeMillis()
        val label = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(now))
        container.settings.syncLastResult = text
        container.settings.syncLastResultAt = now
        _state.update { it.copy(lastResult = text, lastResultLabel = label) }
    }

    fun toggle(id: String) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAll() = _state.update { it.copy(selected = it.rows.map { r -> r.id }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** 关掉常驻的「上次结果」面板（下次同步成功/失败会再出现） */
    fun dismissLastResult() {
        container.settings.syncLastResult = ""
        container.settings.syncLastResultAt = 0L
        _state.update { it.copy(lastResult = "", lastResultLabel = "") }
    }

    /** 同步太久可以中止；已经拉下来的会保留 */
    fun cancelSync() {
        syncJob?.cancel()
        // 等旧任务真正退出再允许重试，避免旧任务的 finally 清掉新任务的状态。
        _state.update { it.copy(message = L10n.t("正在停止同步…")) }
    }

    fun syncNow() = syncOnly(null)

    /** 只处理这一条；不会上传或拉取其它会话。 */
    fun syncOne(id: String) = syncOnly(id)

    private fun syncOnly(onlyId: String?) {
        if (_state.value.busy) return
        val host = selectedHost()
        if (host == null) {
            _state.update { it.copy(message = L10n.t("先选择同步目标")) }
            return
        }
        _state.update { it.copy(busy = true, message = "正在连接远端…") }
        launchConflictOperation(
            if (onlyId == null) "正在同步历史上下文（切到后台也会继续）"
            else "正在同步这一条历史上下文",
        ) {
            // 79 组会话可能要跑好几分钟；切到后台会被系统冻结，同步就停在那儿。
            // 和回复一样挂前台服务，切走/息屏也会跑完。
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (onlyId == null) {
                        container.syncService.sync(host.id) { progress ->
                            _state.update { it.copy(message = progress) }
                        }
                    } else {
                        container.syncService.syncOne(host.id, onlyId) { progress ->
                            _state.update { it.copy(message = progress) }
                        }
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                }
            }
            _state.update { it.copy(busy = false) }
            result.onSuccess { outcome ->
                val text = L10n.t("同步完成：") + outcome.summary
                _state.update { it.copy(message = text) }
                recordResult(text)
            }.onFailure { e ->
                val text = "同步失败：" + app.pocketagent.android.remote.SshDiagnostics.describeChain(e) +
                    "\n\n— 连接诊断 —\n" + app.pocketagent.android.remote.SshDiagnostics.tail(10)
                _state.update { it.copy(message = text) }
                recordResult(text)
            }
        }
    }

    fun deleteSelected() {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty() || _state.value.busy) return
        val host = selectedHost()
        if (host == null) {
            _state.update { it.copy(message = L10n.t("先选择同步目标")) }
            return
        }
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { container.syncService.deleteConversations(ids, host.id) }
                    .getOrElse { -1 }
            }
            _state.update {
                it.copy(
                    busy = false,
                    selected = emptySet(),
                    message = if (removed >= 0) "已删除 $removed 组上下文（以后不会再同步回来）"
                    else L10n.t("删除失败"),
                )
            }
            refresh()
        }
    }

    fun resolve(conflictId: String, choice: SyncService.Choice) {
        if (_state.value.busy) return
        val host = selectedHost()
        if (host == null) {
            _state.update { it.copy(message = L10n.t("先选择同步目标")) }
            return
        }
        _state.update { it.copy(busy = true) }
        launchConflictOperation {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.syncService.resolve(conflictId, choice, host.id) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            val msg = result.getOrElse { "处理失败：${it.message}" }
            // 处理成功就先把卡片撤掉，不等 refresh()（刷新要走 SSH，慢的时候看起来像没反应）
            val resolved = result.isSuccess
            if (!resolved) recordResult("冲突处理失败：" + msg)
            _state.update {
                it.copy(
                    busy = false,
                    message = msg,
                    conflicts = if (resolved) it.conflicts.filterNot { c -> c.id == conflictId } else it.conflicts,
                    conflictErrors = if (resolved) {
                        it.conflictErrors - conflictId
                    } else {
                        it.conflictErrors + (conflictId to msg)
                    },
                )
            }
            refresh()
        }
    }

    /**
     * 冲突很多时一键处理。
     * LOCAL = 手机版本推回远端（远端会被覆盖）；REMOTE = 手机换成远端版本；BOTH = 远端另存一份。
     */
    fun resolveAll(choice: SyncService.Choice) {
        if (_state.value.busy) return
        val host = selectedHost()
        if (host == null) {
            _state.update { it.copy(message = L10n.t("先选择同步目标")) }
            return
        }
        val pending = _state.value.conflicts
        if (pending.isEmpty()) return
        _state.update { it.copy(busy = true, message = "正在处理 0/${pending.size}…") }
        launchConflictOperation {
            var done = 0
            var failed = 0
            val resolved = mutableSetOf<String>()
            val errors = mutableMapOf<String, String>()
            pending.forEachIndexed { index, conflict ->
                val result = withContext(Dispatchers.IO) {
                    runCatching { container.syncService.resolve(conflict.id, choice, host.id) }
                        .onFailure { if (it is CancellationException) throw it }
                }
                val msg = result.getOrElse { "处理失败：${it.message}" }
                val ok = result.isSuccess
                if (ok) {
                    done++
                    resolved += conflict.id
                } else {
                    failed++
                    errors[conflict.id] = msg.ifBlank { "处理失败（没有返回原因）" }
                }
                // 处理一组就从列表上撤掉一组，别等全部跑完再刷新
                _state.update {
                    it.copy(
                        message = "正在处理 ${index + 1}/${pending.size}…（成功 $done，失败 $failed）",
                        conflicts = it.conflicts.filterNot { c -> c.id in resolved },
                        conflictErrors = it.conflictErrors + errors,
                    )
                }
            }
            val label = when (choice) {
                SyncService.Choice.LOCAL -> "保留手机"
                SyncService.Choice.REMOTE -> "保留远端"
                SyncService.Choice.BOTH -> "两个都留"
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = "已按「$label」批量处理 $done/${pending.size} 组" +
                        if (failed > 0) "，$failed 组失败，原因显示在对应冲突卡片上" else "",
                    conflicts = it.conflicts.filterNot { c -> c.id in resolved },
                    conflictErrors = (it.conflictErrors + errors) - resolved,
                )
            }
            if (failed > 0) {
                recordResult(
                    "冲突批量处理：$done/${pending.size} 成功，$failed 失败\n" +
                        errors.entries.joinToString("\n") { (id, why) -> "· ${id.take(8)}：$why" },
                )
            }
            refresh()
        }
    }

    private fun launchConflictOperation(
        notification: String = "正在处理同步冲突（切到后台也会继续）",
        block: suspend () -> Unit,
    ) {
        syncJob = viewModelScope.launch {
            app.pocketagent.android.ChatRunService.start(container.appContext, notification)
            try {
                block()
            } catch (e: CancellationException) {
                _state.update { it.copy(message = L10n.t("已停止同步")) }
                throw e
            } finally {
                app.pocketagent.android.ChatRunService.stop(container.appContext)
                _state.update { it.copy(busy = false) }
                syncJob = null
                refresh()
            }
        }
    }

    fun openTrash() {
        if (_state.value.busy) return
        val host = selectedHost()
        if (host == null) {
            _state.update { it.copy(message = L10n.t("先选择同步目标")) }
            return
        }
        _state.update { it.copy(busy = true, trashOpen = true, trashLoading = true, trashError = null) }
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { container.syncService.listTrash(host.id) }
                _state.update { it.copy(trash = entries, trashSelected = emptySet()) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.update { it.copy(trashError = "读取回收站失败：" + e.message) }
            } finally {
                _state.update { it.copy(busy = false, trashLoading = false) }
            }
        }
    }

    fun closeTrash() {
        if (!_state.value.busy) _state.update { it.copy(trashOpen = false, trashSelected = emptySet()) }
    }

    fun toggleTrash(id: String) = _state.update {
        if (it.busy) it else it.copy(
            trashSelected = if (id in it.trashSelected) it.trashSelected - id else it.trashSelected + id,
        )
    }

    fun selectAllTrash() = _state.update {
        if (it.busy) it else it.copy(
            trashSelected = if (it.trashSelected.isEmpty()) it.trash.map { row -> row.id }.toSet() else emptySet(),
        )
    }

    fun restoreSelectedTrash() = actOnTrash(permanent = false)

    fun permanentlyDeleteSelectedTrash() = actOnTrash(permanent = true)

    private fun actOnTrash(permanent: Boolean) {
        val current = _state.value
        if (current.busy || current.trashSelected.isEmpty()) return
        val host = selectedHost() ?: return
        // 只接受这个主机回收站清单里明确选中的 id。
        val ids = current.trash.filter { it.id in current.trashSelected }.map { it.id }
        if (ids.isEmpty()) return
        _state.update { it.copy(busy = true, trashError = null, trashProgress = "") }
        launchConflictOperation("正在处理回收站") {
            val result = try {
                withContext(Dispatchers.IO) {
                    val progress: (Int, Int) -> Unit = { done, total ->
                        _state.update { it.copy(trashProgress = "${L10n.t("正在处理回收站…")} $done/$total") }
                    }
                    if (permanent) container.syncService.permanentlyDelete(ids, host.id, progress)
                    else container.syncService.restoreDeleted(ids, host.id, progress)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.update { it.copy(trashError = "处理失败：" + e.message) }
                return@launchConflictOperation
            }
            val finished = result.completed + result.missing
            val message = (if (permanent) "已永久删除 ${result.completed.size} 组；不能恢复，以后不会自动同步回来。"
                else "已恢复 ${result.completed.size} 组。点「同步全部」把它们拉回手机。") +
                (if (result.missing.isNotEmpty()) " ${result.missing.size} 组已被清理，无法恢复。" else "") +
                (if (result.errors.isNotEmpty()) " ${result.errors.size} 组失败，保留在回收站列表中，可重试。" else "")
            _state.update {
                it.copy(
                    message = message,
                    trash = it.trash.filterNot { row -> row.id in finished },
                    trashSelected = it.trashSelected - finished,
                    trashError = result.errors.takeIf { errors -> errors.isNotEmpty() }?.let { errors ->
                        "${errors.size} 组处理失败，仍保留在列表中：\n" + errors.values.distinct().joinToString("\n")
                    },
                )
            }
            recordResult(message)
        }
    }
}
