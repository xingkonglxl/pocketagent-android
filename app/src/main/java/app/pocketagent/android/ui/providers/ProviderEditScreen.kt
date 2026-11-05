package app.pocketagent.android.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.ProviderPresets
import app.pocketagent.android.ui.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    initial: Provider,
    isNew: Boolean,
    existingKeyMask: String?,
    onBack: () -> Unit,
    onSave: (Provider, String?) -> Unit,
) {
    var provider by remember { mutableStateOf(initial) }
    var apiKey by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    val nameOk = provider.name.isNotBlank()
    // 只允许 https（本机/内网 http 例外），否则 Key 会在明文链路上裸奔
    val urlOk = app.pocketagent.android.net.isSecureBaseUrl(provider.baseUrl)
    val modelOk = provider.model.isNotBlank()
    val keyOk = if (isNew) {
        apiKey.isNotBlank()
    } else {
        apiKey.isNotBlank() || !existingKeyMask.isNullOrBlank()
    }
    val valid = nameOk && urlOk && modelOk && keyOk

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) tr("添加来源") else tr("编辑来源")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(provider, if (isNew) apiKey else apiKey.ifBlank { null }) },
                        enabled = valid,
                    ) { Text(tr("保存")) }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = provider.kind == ProviderKind.CHAT_COMPLETIONS && provider.baseUrl.contains("deepseek"),
                    onClick = { provider = ProviderPresets.deepSeek().copy(id = provider.id) },
                    label = { Text("DeepSeek") },
                )
                FilterChip(
                    selected = provider.kind == ProviderKind.RESPONSES,
                    onClick = { provider = ProviderPresets.openAi().copy(id = provider.id) },
                    label = { Text("Codex / OpenAI") },
                )
                FilterChip(
                    selected = provider.kind == ProviderKind.ANTHROPIC &&
                        !ProviderPresets.isRelay(provider.baseUrl),
                    onClick = { provider = ProviderPresets.claudeOfficial().copy(id = provider.id) },
                    label = { Text("Claude") },
                )
                FilterChip(
                    selected = false,
                    onClick = { provider = provider.copy(kind = ProviderKind.CHAT_COMPLETIONS) },
                    label = { Text(tr("自定义")) },
                )
            }

            // 中转站预设只在内部构建（-PrelayBaseUrl=...）里出现，发布版没有这段
            if (ProviderPresets.relayEnabled()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ProviderPresets.isRelay(provider.baseUrl) &&
                            provider.kind == ProviderKind.CHAT_COMPLETIONS,
                        onClick = { provider = ProviderPresets.relayChat().copy(id = provider.id) },
                        label = { Text(tr("中转站 · DeepSeek")) },
                    )
                    FilterChip(
                        selected = ProviderPresets.isRelay(provider.baseUrl) &&
                            provider.kind == ProviderKind.RESPONSES,
                        onClick = { provider = ProviderPresets.relayResponses().copy(id = provider.id) },
                        label = { Text(tr("中转站 · Codex")) },
                    )
                    FilterChip(
                        selected = ProviderPresets.isRelay(provider.baseUrl) &&
                            provider.kind == ProviderKind.ANTHROPIC,
                        onClick = { provider = ProviderPresets.relayClaude().copy(id = provider.id) },
                        label = { Text(tr("中转站 · Claude")) },
                    )
                }
            }

            OutlinedTextField(
                value = provider.name,
                onValueChange = { provider = provider.copy(name = it) },
                label = { Text(tr("名称")) },
                placeholder = { Text(tr("例如 DeepSeek")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("field_name"),
            )
            OutlinedTextField(
                value = provider.baseUrl,
                onValueChange = { provider = provider.copy(baseUrl = it) },
                label = { Text(tr("接口地址")) },
                placeholder = { Text("https://api.deepseek.com") },
                singleLine = true,
                isError = provider.baseUrl.isNotBlank() && !urlOk,
                supportingText = if (provider.baseUrl.isNotBlank() && !urlOk) {
                    { Text(tr("要用 https://（本机/内网可用 http）")) }
                } else null,
                modifier = Modifier.fillMaxWidth().testTag("field_base"),
            )
            OutlinedTextField(
                value = provider.model,
                onValueChange = { provider = provider.copy(model = it) },
                label = { Text(tr("模型")) },
                placeholder = { Text("deepseek-chat / gpt-5-codex") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("field_model"),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = {
                    Text(if (isNew) "sk-..." else "留空表示不修改（当前 ${existingKeyMask ?: "未配置"}）")
                },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showKey) tr("隐藏") else tr("显示"),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("field_key"),
            )

            OutlinedTextField(
                value = provider.systemPrompt,
                onValueChange = { provider = provider.copy(systemPrompt = it) },
                label = { Text(tr("系统提示")) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            SwitchRow(
                title = tr("启用工具（harness）"),
                subtitle = tr("允许模型读写应用内文件、抓网页"),
                checked = provider.toolsEnabled,
                onCheckedChange = { provider = provider.copy(toolsEnabled = it) },
            )
            SwitchRow(
                title = tr("自动批准工具"),
                subtitle = tr("关闭时每次动手都会先弹确认"),
                checked = provider.autoApprove,
                onCheckedChange = { provider = provider.copy(autoApprove = it) },
                enabled = provider.toolsEnabled,
            )
            OutlinedTextField(
                value = provider.maxSteps.toString(),
                onValueChange = { text ->
                    text.filter { it.isDigit() }.toIntOrNull()?.let {
                        provider = provider.copy(maxSteps = it.coerceIn(1, 64))
                    }
                },
                label = { Text(tr("最大工具轮数")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = provider.maxOutputTokens.toString(),
                onValueChange = { text ->
                    text.filter { it.isDigit() }.toIntOrNull()?.let {
                        provider = provider.copy(maxOutputTokens = it.coerceIn(1024, 64_000))
                    }
                },
                label = { Text(tr("最大输出 tokens")) },
                supportingText = { Text(tr("Claude 必填；太小会把长分析/长表格截断")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                tr("输出详略（Responses；默认跟随服务端，和桌面一致）"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    null to tr("默认"),
                    "low" to tr("低"),
                    "medium" to tr("中"),
                    "high" to tr("高"),
                ).forEach { (level, label) ->
                    FilterChip(
                        selected = provider.verbosity == level,
                        onClick = { provider = provider.copy(verbosity = level) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { onSave(provider, if (isNew) apiKey else apiKey.ifBlank { null }) },
                enabled = valid,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("save"),
            ) { Text(if (isNew) tr("保存并启用") else tr("保存修改")) }
            Text(
                tr("Key 会加密后存进本机系统密钥库，不上传任何服务器；卸载应用后无法恢复。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
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
