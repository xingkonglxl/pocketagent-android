package app.pocketagent.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pocketagent.android.data.ThemeMode
import app.pocketagent.android.ui.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: ThemeMode,
    language: String = "system",
    onLanguage: (String) -> Unit = {},
    appLock: Boolean,
    secureScreen: Boolean,
    biometricReady: Boolean,
    workspacePath: String,
    version: String,
    onTheme: (ThemeMode) -> Unit,
    onAppLock: (Boolean) -> Unit,
    onSecureScreen: (Boolean) -> Unit,
    onClearWorkspace: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenRemote: () -> Unit = {},
    onOpenMcp: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenTerms: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    /** 公开包（-PshareBuild=true）关掉 SSH 远程执行：类目敏感，个人号基本过不了审 */
    showRemote: Boolean = true,
    /** 公开包同时关掉与电脑的上下文同步，纯 AI 客户端更容易过审 */
    showSync: Boolean = true,
    customModelsState: CustomModelsUiState = CustomModelsUiState(),
    onAddCustomModel: (String) -> Unit = {},
    onRemoveCustomModel: (String) -> Unit = {},
    onCleanupCustomModels: () -> Unit = {},
    onHideModel: (String) -> Unit = {},
    onRestoreAllModels: () -> Unit = {},
    onDismissCustomMessage: () -> Unit = {},
    onBack: () -> Unit,
) {
    var showAddModel by remember { mutableStateOf(false) }
    var showManageModels by remember { mutableStateOf(false) }
    var newModelId by remember { mutableStateOf("") }

    // 国产 ROM（荣耀/小米等）默认会限制后台，前台服务也可能被冻结。
    // 这里让用户一键跳到系统的电池优化白名单页，并在回到本页时刷新状态。
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryUnrestricted by remember {
        mutableStateOf(app.pocketagent.android.util.BatteryOptimization.isIgnoring(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryUnrestricted = app.pocketagent.android.util.BatteryOptimization.isIgnoring(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("设置")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 顶部品牌卡：和「设置」里的分组卡片一个风格
            Card(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("PocketAgent", style = MaterialTheme.typography.titleLarge)
                        Text(
                            tr("本地优先 · Key 只存本机 · 不经过中间服务器"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Section(tr("外观")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(theme == ThemeMode.DARK, { onTheme(ThemeMode.DARK) }, { Text(tr("深色")) })
                    FilterChip(theme == ThemeMode.LIGHT, { onTheme(ThemeMode.LIGHT) }, { Text(tr("浅色")) })
                    FilterChip(theme == ThemeMode.SYSTEM, { onTheme(ThemeMode.SYSTEM) }, { Text(tr("跟随系统")) })
                }
            }

            Section(tr("语言 / Language")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(language == "system", { onLanguage("system") }, { Text(tr("跟随系统")) })
                    FilterChip(language == "zh", { onLanguage("zh") }, { Text(tr("中文")) })
                    FilterChip(language == "en", { onLanguage("en") }, { Text("English") })
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Section(tr("安全")) {
                SwitchRow(
                    title = tr("应用锁"),
                    subtitle = if (biometricReady) tr("打开时用指纹/面容验证") else tr("这台设备没有可用的生物识别"),
                    checked = appLock,
                    enabled = biometricReady,
                    onCheckedChange = onAppLock,
                )
                SwitchRow(
                    title = tr("防截屏"),
                    subtitle = tr("禁止截图和录屏，最近任务里隐藏内容"),
                    checked = secureScreen,
                    onCheckedChange = onSecureScreen,
                )
                Text(
                    tr("Key 用 Android Keystore 里的 AES-GCM 主密钥加密，主密钥不可导出；应用不允许备份。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Section(tr("模型")) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProviders),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("模型来源"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            tr("添加或修改 DeepSeek、Codex 的地址、模型和 Key"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showAddModel = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("添加自定义模型"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            tr("先真发一次请求，测通才加入 /model"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showManageModels = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("管理模型（${customModelsState.all.size} 个可用）", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            tr("删除任意模型（内置只是隐藏，可恢复）/ 清理不可用"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (showRemote) {
                Section(tr("远程执行")) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenRemote),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("远程主机"), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                tr("SSH 到远程主机跑命令、读写远端文件（私钥存本机 Keystore）"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("›", style = MaterialTheme.typography.titleMedium)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Section(tr("工具扩展")) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenMcp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("MCP 服务器"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            tr("接入 HTTP MCP 服务器，模型可直接调用它的工具；访问令牌存本机 Keystore"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Section(tr("后台运行")) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { app.pocketagent.android.util.BatteryOptimization.openSettings(context) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("允许后台保持回复"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (batteryUnrestricted) tr("已允许后台运行，切出去或锁屏也能跑完这一轮")
                            else tr("系统可能随时冻结后台，导致回复中断；点这里把本应用加入电池优化白名单"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (batteryUnrestricted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleMedium)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (showSync) {
                Section(tr("上下文")) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSync),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("历史上下文"), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                tr("手机 ⇄ 远程主机双向同步所有会话；同步目标可在「历史上下文」里选择；冲突选保留哪边；勾选删除没用的"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("›", style = MaterialTheme.typography.titleMedium)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Section(tr("工作区")) {
                Text(
                    tr("模型读写的工作区（在输入框用 /workspace 切换）："),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(workspacePath, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onClearWorkspace) { Text(tr("清空工作区文件")) }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Section(tr("关于")) {
                Text("PocketAgent $version", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("本地优先的移动端 agent：模型走你自己的 key，数据留在手机上，不经过中间服务器。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LegalRow(tr("隐私政策"), onOpenPrivacy)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LegalRow(tr("用户协议"), onOpenTerms)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LegalRow(tr("开源许可"), onOpenLicenses)
            }
        }
    }

    if (showManageModels) {
        ManageModelsDialog(
            state = customModelsState,
            onRemove = onRemoveCustomModel,
            onHide = onHideModel,
            onRestoreAll = onRestoreAllModels,
            onCleanup = onCleanupCustomModels,
            onClose = { showManageModels = false },
        )
    }

    if (showAddModel) {
        AddModelDialog(
            state = customModelsState,
            newId = newModelId,
            onNewId = { newModelId = it },
            onAdd = {
                onAddCustomModel(it)
                newModelId = ""
            },
            onClose = { showAddModel = false },
        )
    }

}

@Composable
private fun LegalRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", style = MaterialTheme.typography.titleMedium)
    }
}

private fun ignoresBatteryOptimizations(context: Context): Boolean = runCatching {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}.getOrDefault(false)

/** 打不开白名单列表时退回应用详情页（荣耀等 ROM 的入口位置不一样）。 */
private fun openBatterySettings(context: Context) {
    val opened = runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }
}

@Composable
private fun AddModelDialog(
    state: CustomModelsUiState,
    newId: String,
    onNewId: (String) -> Unit,
    onAdd: (String) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("添加自定义模型")) },
        text = {
            Column {
                Text(
                    tr("填中转站上真实的模型 id（如 gpt-7-preview）。点「测试并添加」会先真发一次请求，通过才加入 /model。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = newId,
                    onValueChange = onNewId,
                    singleLine = true,
                    placeholder = { Text(tr("模型 id")) },
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
                state.message?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(newId) }, enabled = !state.busy && newId.isNotBlank()) {
                Text(if (state.busy) tr("测试中…") else tr("测试并添加"))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("关闭")) } },
    )
}

@Composable
private fun ManageModelsDialog(
    state: CustomModelsUiState,
    onRemove: (String) -> Unit,
    onHide: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onCleanup: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("管理模型")) },
        text = {
            Column {
                Text(
                    tr("删除会把它从 /model 里去掉；内置模型只是隐藏，随时能「恢复全部」。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 4.dp)) {
                    items(state.all) { id ->
                        val isCustom = id in state.models
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (state.results[id]) {
                                    true -> "✅ $id"
                                    false -> "❌ $id"
                                    null -> id
                                } + if (isCustom) tr("（自定义）") else "",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = {
                                if (isCustom) onRemove(id) else onHide(id)
                            }) { Text(tr("删除")) }
                        }
                    }
                }
                if (state.hidden.isNotEmpty()) {
                    Text(
                        "已隐藏 ${state.hidden.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.message?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (state.hidden.isNotEmpty()) {
                    TextButton(onClick = onRestoreAll) { Text(tr("恢复全部")) }
                }
                TextButton(onClick = onCleanup, enabled = !state.busy) {
                    Text(if (state.busy) tr("测试中…") else tr("清理不可用"))
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("关闭")) } },
    )
}

@Composable
private fun CustomModelsDialog(
    state: CustomModelsUiState,
    newId: String,
    onNewId: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCleanup: () -> Unit,
    onDismissMessage: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("自定义模型")) },
        text = {
            Column {
                Text(
                    tr("填中转站上真实的模型 id（如 gpt-7-preview）。「测试并添加」会先真发一次请求，通过才加入，避免污染 /model。") +
                        tr("\n添加成功后，每个模型右侧都有「删除」；也可以点「清理不可用」批量删。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.models.isEmpty()) {
                    Text(
                        tr("还没有自定义模型。添加成功后，每个模型右边会出现「删除」。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                state.models.forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (state.results[id]) {
                                true -> "✅ $id"
                                false -> "❌ $id"
                                null -> id
                            },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { onRemove(id) }) { Text(tr("删除")) }
                    }
                }
                OutlinedTextField(
                    value = newId,
                    onValueChange = onNewId,
                    singleLine = true,
                    placeholder = { Text(tr("模型 id")) },
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                )
                state.message?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.models.isNotEmpty()) {
                        TextButton(onClick = onCleanup, enabled = !state.busy) { Text(tr("清理不可用")) }
                    }
                    if (state.message != null) {
                        TextButton(onClick = onDismissMessage) { Text(tr("知道了")) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(newId) }, enabled = !state.busy && newId.isNotBlank()) {
                Text(if (state.busy) tr("测试中…") else tr("测试并添加"))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("关闭")) } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
