package app.pocketagent.android.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pocketagent.android.data.SyncConflict
import app.pocketagent.android.sync.SyncService
import app.pocketagent.android.ui.i18n.tr

/**
 * 历史上下文：手机和 Mac 的会话都在这里，一组一条，勾选删除。
 * 冲突的会话在顶部，选「保留本机 / 保留远端 / 两个都留」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    state: SyncUiState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onSyncOne: (String) -> Unit = {},
    onSelectHost: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onResolve: (String, SyncService.Choice) -> Unit,
    onResolveAll: (SyncService.Choice) -> Unit = {},
    onDismissMessage: () -> Unit,
    onDismissLastResult: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onCloseTrash: () -> Unit = {},
    onToggleTrash: (String) -> Unit = {},
    onSelectAllTrash: () -> Unit = {},
    onRestoreSelectedTrash: () -> Unit = {},
    onPermanentlyDeleteSelectedTrash: () -> Unit = {},
    onCancelSync: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var messageExpanded by remember { mutableStateOf(false) }
    var hostMenu by remember { mutableStateOf(false) }
    // 冲突卡片可能在列表下方，顶部提示条看不见；每个操作结果再用 snackbar 弹一次。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(tr("历史上下文")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "已选 ${state.selected.size} 组",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = if (state.selected.isEmpty()) onSelectAll else onClearSelection) {
                        Text(if (state.selected.isEmpty()) tr("全选") else tr("取消全选"))
                    }
                    Button(
                        onClick = { confirmDelete = true },
                        enabled = state.selected.isNotEmpty() && !state.busy,
                    ) { Text(tr("删除所选")) }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr("同步目标"), style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(
                        onClick = { hostMenu = true },
                        enabled = !state.busy && state.hosts.isNotEmpty(),
                    ) {
                        Text(state.hostName.ifBlank { tr("选择远程主机") })
                        Text("  ▾")
                    }
                    DropdownMenu(expanded = hostMenu, onDismissRequest = { hostMenu = false }) {
                        state.hosts.forEach { host ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(host.name)
                                        Text(
                                            host.target,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    hostMenu = false
                                    onSelectHost(host.id)
                                },
                            )
                        }
                    }
                }
                if (state.hosts.isEmpty()) {
                    Text(
                        tr("还没有可同步的远程主机，先去设置 → 远程主机添加。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = if (state.busy) onCancelSync else onSync,
                    enabled = state.busy || state.selectedHostId != null,
                ) {
                    Text(
                        when {
                            state.busy -> tr("停止同步")
                            state.hostName.isBlank() -> tr("先选择同步目标")
                            else -> "从 ${state.hostName} 同步全部"
                        }
                    )
                }
                if (state.busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        "共 ${state.rows.size} 组 · 本机推上去、远端拉下来，各自独立成组。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.lastSyncLabel.isNotBlank()) {
                        Text(
                            "上次同步：${state.lastSyncLabel}" +
                                if (state.deletedCount > 0) " · 已删 ${state.deletedCount} 组" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            TextButton(
                onClick = onOpenTrash,
                enabled = !state.busy && state.selectedHostId != null,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text(tr("回收站：恢复 / 永久删除")) }

            // 全部放进同一个可滚动列表：之前消息卡片把冲突卡片顶到屏幕外面，
            // 用户只能看到一行「N 组冲突」的文字，点不到「保留本机/远端」。
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.lastResult.isNotBlank() && !state.busy) {
                    item(key = "last-result") {
                        LastResultCard(
                            text = state.lastResult,
                            whenLabel = state.lastResultLabel,
                            expanded = messageExpanded,
                            onToggleExpand = { messageExpanded = !messageExpanded },
                            onDismiss = onDismissLastResult,
                        )
                    }
                }
                state.message?.takeIf { it != state.lastResult }?.let { msg ->
                    item(key = "message") {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = if (messageExpanded) Int.MAX_VALUE else 6,
                                )
                                Row {
                                    if (msg.length > 180) {
                                        TextButton(onClick = { messageExpanded = !messageExpanded }) {
                                            Text(if (messageExpanded) tr("收起") else tr("展开全部"))
                                        }
                                    }
                                    TextButton(onClick = onDismissMessage) { Text(tr("知道了")) }
                                }
                            }
                        }
                    }
                }

                if (state.conflicts.isNotEmpty()) {
                    item(key = "conflict-title") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "冲突 ${state.conflicts.size} 组：两边都改过，选一个",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "保留手机会把手机版本推回远端（覆盖远端那份）；保留远端会换成电脑上的版本。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { onResolveAll(SyncService.Choice.LOCAL) },
                                    enabled = !state.busy,
                                ) { Text(tr("全部保留手机")) }
                                TextButton(
                                    onClick = { onResolveAll(SyncService.Choice.REMOTE) },
                                    enabled = !state.busy,
                                ) { Text(tr("全部保留远端")) }
                                TextButton(
                                    onClick = { onResolveAll(SyncService.Choice.BOTH) },
                                    enabled = !state.busy,
                                ) { Text(tr("全部都留")) }
                            }
                        }
                    }
                    items(state.conflicts, key = { "conflict-" + it.id }) { conflict ->
                        ConflictCard(
                            conflict = conflict,
                            busy = state.busy,
                            error = state.conflictErrors[conflict.id],
                            onResolve = onResolve,
                        )
                    }
                    item(key = "conflict-divider") {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                if (state.rows.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                tr("还没有会话。"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(state.rows, key = { it.id }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = row.id in state.selected,
                                onCheckedChange = { onToggle(row.id) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.title.ifBlank { tr("(无标题)") },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                )
                                Text(
                                    "${row.originLabel} · ${row.count} 条 · ${row.timeLabel}" +
                                        if (row.conflict) tr(" · ⚠︎ 有冲突") else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { onSyncOne(row.id) },
                                enabled = !state.busy && state.selectedHostId != null && !row.conflict,
                                modifier = Modifier.testTag("sync-one-${row.id}"),
                            ) { Text(tr("同步此条")) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${state.selected.size} 组上下文？") },
            text = {
                Text(
                    "手机上这 ${state.selected.size} 组会删掉，${state.hostName} 上对应的会话" +
                        if (state.hostName.contains("Mac", ignoreCase = true))
                            "会移到回收站，可在「回收站：恢复 / 永久删除」中管理；以后同步不会自动拉回来。"
                        else "会永久删除；以后同步不会自动拉回来。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteSelected()
                }) { Text(tr("删除")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(tr("取消")) }
            },
        )
    }

    if (state.trashOpen) {
        TrashDialog(
            state, onCloseTrash, onOpenTrash, onToggleTrash, onSelectAllTrash,
            onRestoreSelectedTrash, onPermanentlyDeleteSelectedTrash,
        )
    }
}

@Composable
internal fun TrashDialog(
    state: SyncUiState,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onRestore: () -> Unit,
    onPermanentlyDelete: () -> Unit,
) {
    // 保存确认时的选择，避免确认框打开后底层刷新导致删除范围变化。
    var confirmingIds by remember(state.selectedHostId) { mutableStateOf<Set<String>?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp).heightIn(max = 640.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.hostName} · ${tr("回收站")}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose, enabled = !state.busy) { Text(tr("关闭")) }
                }
                Text(
                    tr("手动勾选要处理的历史。永久删除后不能恢复，删除记录会保留，以后不会自动同步回来。"),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (state.trashLoading) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                } else if (state.trash.isEmpty()) {
                    Text(tr("回收站为空"), modifier = Modifier.padding(vertical = 16.dp))
                }
                state.trashError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onReload, enabled = !state.busy) { Text(tr("刷新回收站")) }
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(state.trash, key = { it.id }) { row ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = row.id in state.trashSelected,
                                onCheckedChange = { onToggle(row.id) },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("trash-select-${row.id}"),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(row.title.ifBlank { row.id }, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                                val date = remember(row.deletedAt) {
                                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                        .format(java.util.Date(row.deletedAt))
                                }
                                Text(
                                    "$date · ${row.bytes / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("已选 ${state.trashSelected.size} 组"), modifier = Modifier.weight(1f))
                    TextButton(onClick = onSelectAll, enabled = !state.busy && state.trash.isNotEmpty()) {
                        Text(if (state.trashSelected.isEmpty()) tr("全选") else tr("取消全选"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRestore, enabled = !state.busy && state.trashSelected.isNotEmpty()) {
                        Text(tr("恢复所选"))
                    }
                    TextButton(
                        onClick = { confirmingIds = state.trashSelected.toSet() },
                        enabled = !state.busy && state.trashSelected.isNotEmpty(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(tr("永久删除所选")) }
                }
                if (state.busy && !state.trashLoading) {
                    Text(state.trashProgress.ifBlank { tr("正在处理回收站…") }, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    confirmingIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { confirmingIds = null },
            title = { Text(tr("永久删除 ${ids.size} 组历史？")) },
            text = {
                Column {
                    Text(tr("将彻底删除所选历史在此主机回收站中的文件，无法恢复。未选中的历史不受影响。"))
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(state.trash.filter { it.id in ids }, key = { it.id }) {
                            Text("· ${it.title.ifBlank { it.id }}", modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy && ids == state.trashSelected,
                    onClick = { confirmingIds = null; onPermanentlyDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(tr("确认永久删除")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingIds = null }) { Text(tr("取消")) }
            },
        )
    }
}

@Composable
private fun LastResultCard(
    text: String,
    whenLabel: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "上次结果" + if (whenLabel.isNotBlank()) "（$whenLabel）" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
            )
            Row {
                if (text.length > 120) {
                    TextButton(onClick = onToggleExpand) {
                        Text(if (expanded) tr("收起") else tr("展开全部"))
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(tr("复制")) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(tr("知道了")) }
            }
        }
    }
}

@Composable
private fun ConflictCard(
    conflict: SyncConflict,
    busy: Boolean,
    error: String?,
    onResolve: (String, SyncService.Choice) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(conflict.title.ifBlank { conflict.id.take(8) }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "本机：${conflict.localRev}　远端：${conflict.remoteRev}" +
                    if (conflict.remoteTitle.isNotBlank() && conflict.remoteTitle != conflict.title)
                        "（远端标题：${conflict.remoteTitle}）" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!error.isNullOrBlank()) {
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onResolve(conflict.id, SyncService.Choice.LOCAL) }, enabled = !busy) {
                    Text(tr("保留本机"))
                }
                OutlinedButton(onClick = { onResolve(conflict.id, SyncService.Choice.REMOTE) }, enabled = !busy) {
                    Text(tr("保留远端"))
                }
                TextButton(onClick = { onResolve(conflict.id, SyncService.Choice.BOTH) }, enabled = !busy) {
                    Text(tr("两个都留"))
                }
            }
        }
    }
}
