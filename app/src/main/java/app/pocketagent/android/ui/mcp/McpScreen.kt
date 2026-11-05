package app.pocketagent.android.ui.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pocketagent.android.mcp.McpServerConfig
import app.pocketagent.android.ui.i18n.tr

/**
 * MCP 服务器管理。只支持 HTTP 的 Streamable MCP；stdio 服务器在手机上跑不了，
 * 需要放到远端机器，用 `/workspace` + `remote_codex`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    state: McpUiState,
    onBack: () -> Unit,
    onSave: (McpServerConfig, String?, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onTest: (McpServerConfig, String?) -> Unit,
    onToggle: (McpServerConfig, Boolean) -> Unit,
    onRefreshTools: () -> Unit,
) {
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var token by remember { mutableStateOf("") }
    var clearToken by remember { mutableStateOf(false) }

    fun startEdit(server: McpServerConfig) {
        editing = server
        token = ""
        clearToken = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("MCP 服务器")) },
                navigationIcon = {
                    IconButton(onClick = { if (editing != null) editing = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                actions = {
                    if (editing == null) {
                        TextButton(onClick = { startEdit(McpServerConfig()) }) { Text(tr("添加")) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.message?.let { message ->
                Card(Modifier.fillMaxWidth()) {
                    Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            val current = editing
            if (current == null) {
                Text(
                    tr("接入支持 Streamable HTTP 的 MCP 服务器后，模型就能直接调用它的工具。" +
                        "访问令牌保存在本机 Keystore，不写进会话、日志或同步文件。" +
                        "MCP 工具默认每次确认，可在输入框用 /permissions 整类放行。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.servers.isEmpty()) {
                    Text(
                        tr("还没有 MCP 服务器。点右上角「添加」，填名称和 https 地址；需要令牌的服务器再填令牌。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.servers.forEach { server ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    server.name.ifBlank { server.url },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = { onToggle(server, it) },
                                )
                            }
                            Text(
                                server.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                tr("访问令牌：") + (state.tokenMasks[server.id] ?: tr("未设置")),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { startEdit(server) }) { Text(tr("编辑")) }
                                TextButton(onClick = { onTest(server, null) }) { Text(tr("测试")) }
                                TextButton(onClick = { onDelete(server.id) }) {
                                    Text(tr("删除"), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onRefreshTools, enabled = !state.busy) {
                    Text(if (state.busy) tr("处理中…") else tr("重新发现工具"))
                }
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (current.url.isBlank()) tr("添加 MCP 服务器") else tr("编辑 MCP 服务器"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = current.name,
                            onValueChange = { editing = current.copy(name = it) },
                            label = { Text(tr("名称")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = current.url,
                            onValueChange = { editing = current.copy(url = it) },
                            label = { Text(tr("地址（https://…/mcp）")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it; clearToken = false },
                            label = { Text(tr("访问令牌（可选，保存后不再显示）")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val stored = state.tokenMasks[current.id]
                        if (stored != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tr("已保存：") + stored,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { clearToken = true; token = "" }) {
                                    Text(
                                        if (clearToken) tr("将清除") else tr("清除令牌"),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("启用"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(
                                checked = current.enabled,
                                onCheckedChange = { editing = current.copy(enabled = it) },
                            )
                        }
                        Text(
                            tr("只支持 Streamable HTTP。stdio 类型（本地 node / python 进程）在手机上跑不了，" +
                                "请在远端机器上用 /workspace + remote_codex。"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val target = current.copy(name = current.name.trim(), url = current.url.trim())
                                    if (target.url.isBlank()) return@Button
                                    onSave(target, token.takeIf { it.isNotBlank() }, clearToken)
                                    editing = null
                                    token = ""
                                    clearToken = false
                                },
                                enabled = current.url.isNotBlank() && !state.busy,
                            ) { Text(tr("保存")) }
                            OutlinedButton(
                                onClick = { onTest(current, token.takeIf { it.isNotBlank() }) },
                                enabled = current.url.isNotBlank() && !state.busy,
                            ) { Text(tr("测试连接")) }
                            TextButton(onClick = { editing = null; token = ""; clearToken = false }) {
                                Text(tr("取消"))
                            }
                        }
                    }
                }
            }
        }
    }
}
