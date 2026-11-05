package app.pocketagent.android.ui.remote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.pocketagent.android.remote.configurationError
import app.pocketagent.android.remote.normalized
import app.pocketagent.android.remote.SshHost
import app.pocketagent.android.ui.i18n.tr

/**
 * 远程主机管理：remote-host / Mac 之类。
 * 私钥不落 JSON，导入后进 Keystore；这里只显示是否已导入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    onBack: () -> Unit,
    onSave: (SshHost, String?) -> Unit,
    onDelete: (String) -> Unit,
    onTest: (SshHost, String?) -> Unit,
) {
    var editing by remember { mutableStateOf<SshHost?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("远程主机")) },
                navigationIcon = {
                    IconButton(onClick = { if (editing != null) editing = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                actions = {
                    if (editing == null) {
                        TextButton(onClick = { editing = SshHost(host = "", user = "") }) { Text(tr("添加")) }
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
            state.message?.let { msg ->
                Card(Modifier.fillMaxWidth()) {
                    Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            val current = editing
            if (current == null) {
                if (state.hosts.isEmpty()) {
                    Text(
                        tr("还没有主机。点右上角「添加」，填 host / user，再导入私钥（Keystore 加密保存）。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.hosts.forEach { host ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(host.name.ifBlank { host.host }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                host.target + if (host.viaJump) "（经 ${host.jumpUser ?: ""}@${host.jumpHost}）" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                tr("私钥：") + (state.keyMasks[host.id] ?: tr("未导入")) +
                                    tr("　工作区：") + host.workspaceDir,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                when (host.codexHarnessAvailable) {
                                    true -> tr("远端 harness：Codex（在该主机执行）")
                                    false -> tr("远端 harness：未检测到 Codex")
                                    null -> tr("远端 harness：选择工作区时自动检测")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editing = host }) { Text(tr("编辑")) }
                                TextButton(
                                    onClick = { onTest(host, null) },
                                    enabled = state.busyId == null,
                                ) {
                                    if (state.busyId == host.id) {
                                        CircularProgressIndicator(Modifier.height(16.dp).padding(end = 6.dp))
                                    }
                                    Text(tr("测试连接"))
                                }
                                TextButton(onClick = { onDelete(host.id) }) { Text(tr("删除")) }
                            }
                        }
                    }
                }
            } else {
                HostEditor(
                    initial = current,
                    keyHint = state.keyMasks[current.id],
                    busy = state.busyId == current.id,
                    onCancel = { editing = null },
                    onSave = { host, key ->
                        onSave(host, key)
                        editing = null
                    },
                    onTest = { host, key -> onTest(host, key) },
                )
            }
        }
    }
}

@Composable
private fun HostEditor(
    initial: SshHost,
    keyHint: String?,
    busy: Boolean,
    onCancel: () -> Unit,
    onSave: (SshHost, String?) -> Unit,
    onTest: (SshHost, String?) -> Unit,
) {
    var host by remember { mutableStateOf(initial) }
    var keyPem by remember { mutableStateOf("") }
    var keyFileName by remember { mutableStateOf<String?>(null) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    var jumpPortText by remember { mutableStateOf(initial.jumpPort.toString()) }
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = ByteArray(256 * 1024 + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val n = stream.read(bytes, count, bytes.size - count)
                        if (n < 0) break
                        count += n
                    }
                    require(count <= 256 * 1024) { "私钥文件过大（上限 256 KB）" }
                    String(bytes, 0, count, Charsets.UTF_8)
                } ?: error("无法读取所选文件")
            }.onSuccess { text ->
                if (text != null) {
                    keyError = null
                    keyPem = text
                    keyFileName = uri.lastPathSegment?.substringAfterLast('/')
                }
            }.onFailure { keyError = "私钥导入失败，请选择可读取的 OpenSSH / PEM / PPK 文件（不超过 256 KB）。" }
        }
    }

    Text(tr("名称"), style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(host.name, { host = host.copy(name = it) }, Modifier.fillMaxWidth(), singleLine = true)

    Text(tr("主机 / 端口 / 用户"), style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(host.host, { host = host.copy(host = it) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("192.168.1.10") })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            portText,
            { v -> portText = v.filter { it.isDigit() }.take(5); host = host.copy(port = portText.toIntOrNull() ?: 0) },
            Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(host.user, { host = host.copy(user = it) }, Modifier.weight(2f), singleLine = true, placeholder = { Text(tr("用户名")) })
    }

    Text(tr("跳板机（可留空）"), style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(host.jumpHost.orEmpty(), { host = host.copy(jumpHost = it.ifBlank { null }) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(tr("公网跳板地址")) })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            jumpPortText,
            { v -> jumpPortText = v.filter { it.isDigit() }.take(5); host = host.copy(jumpPort = jumpPortText.toIntOrNull() ?: 0) },
            Modifier.weight(1f),
            singleLine = true,
        )
        OutlinedTextField(host.jumpUser.orEmpty(), { host = host.copy(jumpUser = it.ifBlank { null }) }, Modifier.weight(2f), singleLine = true, placeholder = { Text(tr("跳板用户名")) })
    }

    Text(tr("远端工作目录（命令在这里执行）"), style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(host.workDir, { host = host.copy(workDir = it) }, Modifier.fillMaxWidth(), singleLine = true)

    Text(tr("工作区目录（fs_* 工具、导出、同步都写这里）"), style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(
        host.workspaceDir,
        { host = host.copy(workspaceDir = it) },
        Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("~/pocketagent") },
    )

    Text(tr("Codex 可执行文件（可留空）"), style = MaterialTheme.typography.labelMedium)
    OutlinedTextField(
        host.codexExecutable, { host = host.copy(codexExecutable = it) },
        Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text(tr("留空自动检测；或填写 ~/bin/codex")) },
    )
    Text(tr("自动检测远端 Codex。模型、凭证与 DeepSeek 适配器使用远端自己的配置。"),
        style = MaterialTheme.typography.bodySmall)

    Text(tr("私钥"), style = MaterialTheme.typography.labelMedium)
    Text(
        when {
            keyFileName != null -> "已选择文件：$keyFileName（保存时导入）"
            !keyHint.isNullOrBlank() -> "已导入：$keyHint（重新选文件可替换）"
            else -> tr("还没有私钥。选一个 OpenSSH / PEM 私钥文件，保存后进 Keystore 加密。")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        host.passphrase.orEmpty(), { host = host.copy(passphrase = it) },
        Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(tr("私钥口令（可选）")) },
        supportingText = { Text(tr("加密私钥请填写口令；不更换密钥时留空保留已保存口令。")) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    keyError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(tr("选择私钥文件")) }

    val configError = host.normalized().configurationError()
    if (host.host.isNotBlank() && host.user.isNotBlank()) {
        configError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(host, keyPem.ifBlank { null }) }, enabled = configError == null) {
            Text(tr("保存"))
        }
        OutlinedButton(
            onClick = { onTest(host, keyPem.ifBlank { null }) },
            enabled = configError == null && !busy,
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(16.dp).padding(end = 6.dp))
            Text(tr("测试连接"))
        }
        TextButton(onClick = onCancel) { Text(tr("取消")) }
    }
}
