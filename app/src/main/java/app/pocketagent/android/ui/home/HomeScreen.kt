package app.pocketagent.android.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.Provider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.pocketagent.android.agent.ApprovalRecord
import app.pocketagent.android.agent.decodeReplyText
import app.pocketagent.android.ui.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    conversations: List<Conversation>,
    providers: List<Provider>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onProviders: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PocketAgent", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (providers.isEmpty()) tr("还没接入模型") else "${providers.size} 个来源可用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onProviders) {
                        Icon(Icons.Outlined.Tune, contentDescription = tr("模型来源"))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = tr("设置"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(tr("新对话")) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (conversations.isEmpty()) {
                HomeEmpty(hasProvider = providers.isNotEmpty(), onNew = onNew, onProviders = onProviders)
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        Text(
                            tr("最近对话"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(conversations, key = { it.id }) { conv ->
                        ConversationRow(
                            conv = conv,
                            providerName = providers.firstOrNull { it.id == conv.providerId }?.name,
                            onOpen = onOpen,
                            onDelete = onDelete,
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conv: Conversation,
    providerName: String?,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // 删除前二次确认：首页一点就删太容易误触
    var confirmDelete by remember { mutableStateOf(false) }
    // 本地提示（/undo 等，model=local）和旧版本存下的斜杠命令回显都不是模型回复，
    // 预览要落到上一条真实内容。
    val rawText = conv.messages
        .lastOrNull {
            it.text.isNotBlank() &&
                it.model != app.pocketagent.android.agent.LOCAL_NOTICE_MODEL &&
                !it.text.trim().startsWith("/")
        }
        ?.text.orEmpty()
    val lastText = decodeReplyText(rawText).body
    // 桌面 Codex 同步过来的审批复核 JSON 不要当正文展示
    val approval = remember(lastText) { ApprovalRecord.parse(lastText) }
    val preview = if (approval != null) {
        (if (approval.allowed) "审批记录：允许 · " else "审批记录：拒绝 · ") +
            approval.rationale.replace(Regex("\\s+"), " ")
    } else {
        plainMarkdownPreview(lastText)
    }
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(conv.updatedAt))
    val meta = buildString {
        providerName?.let { append(it).append(" · ") }
        append(time)
        if (conv.messages.isNotEmpty()) append(" · ${conv.messages.size} 条")
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(conv.id) },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    conv.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = tr("更多"))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("删除对话")) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("删除对话")) },
            text = { Text(tr("会同时从手机和 Mac 删除，可在「设置 → 历史上下文」里恢复。")) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(conv.id) }) {
                    Text(tr("删除"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(tr("取消")) }
            },
        )
    }
}

/** 首页只放纯文本摘要；把 Markdown 标记剥掉，避免卡片里出现 `**625` 这类原始符号。 */
internal fun plainMarkdownPreview(raw: String): String {
    var text = raw
        .replace(Regex("(?s)```.*?```"), " ")
        .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("__([^_]+)__"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s*>\\s?"), "")
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
        .replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        .replace(Regex("https?://\\S+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (text.isEmpty()) text = "（空消息）"
    return text
}

@Composable
private fun HomeEmpty(hasProvider: Boolean, onNew: () -> Unit, onProviders: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr("把 Codex 装进口袋"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Text(
            tr("接入 DeepSeek 或 Codex，用 key 直接跑；模型可以读写应用内文件、抓网页，动手前会先问你。"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        if (hasProvider) {
            TextButton(onClick = onNew) { Text(tr("开始第一个对话")) }
        } else {
            TextButton(onClick = onProviders) { Text(tr("添加模型来源")) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Key 只保存在本机的系统密钥库里"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
