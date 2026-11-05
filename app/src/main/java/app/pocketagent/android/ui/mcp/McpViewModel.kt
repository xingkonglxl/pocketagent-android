package app.pocketagent.android.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pocketagent.android.AppContainer
import app.pocketagent.android.mcp.McpClient
import app.pocketagent.android.mcp.McpServerConfig
import app.pocketagent.android.security.maskSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class McpUiState(
    val servers: List<McpServerConfig> = emptyList(),
    val tokenMasks: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
    val toolSummary: String? = null,
)

class McpViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(McpUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (servers, masks) = withContext(Dispatchers.IO) {
                val list = container.mcpStore.load()
                list to list.mapNotNull { server ->
                    container.secretStore.get(server.tokenId)?.let { server.id to maskSecret(it) }
                }.toMap()
            }
            _state.update {
                it.copy(
                    servers = servers,
                    tokenMasks = masks,
                    toolSummary = container.mcpTools.errors().takeIf { errors -> errors.isNotEmpty() }
                        ?.joinToString("；"),
                )
            }
        }
    }

    fun save(server: McpServerConfig, token: String?, clearToken: Boolean = false) {
        val url = server.url.trim()
        if (!app.pocketagent.android.net.isSecureBaseUrl(url)) {
            _state.update { it.copy(message = "地址必须使用 HTTPS；HTTP 仅允许本机或内网数字地址，URL 不能包含用户名或密码") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            withContext(Dispatchers.IO) {
                container.mcpStore.upsert(server.copy(name = server.name.trim(), url = server.url.trim()))
                when {
                    clearToken -> container.secretStore.remove(server.tokenId)
                    !token.isNullOrBlank() -> container.secretStore.put(server.tokenId, token.trim())
                }
                container.mcpTools.invalidate()
            }
            val summary = withContext(Dispatchers.IO) {
                runCatching { container.mcpTools.ensureFresh(force = true) }.getOrNull()
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = summary?.message() ?: "已保存 ${server.name.ifBlank { server.url }}",
                    toolSummary = summary?.message(),
                )
            }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.mcpStore.delete(id)
                container.secretStore.remove("mcp_token_$id")
                container.mcpTools.invalidate()
                runCatching { container.mcpTools.ensureFresh(force = true) }
            }
            _state.update { it.copy(message = "已删除") }
            refresh()
        }
    }

    fun setEnabled(server: McpServerConfig, enabled: Boolean) {
        save(server.copy(enabled = enabled), token = null)
    }

    /** 只测连通性和工具数量，不写入配置。 */
    fun test(server: McpServerConfig, token: String?) {
        val url = server.url.trim()
        if (!app.pocketagent.android.net.isSecureBaseUrl(url)) {
            _state.update { it.copy(message = "地址必须使用 HTTPS；HTTP 仅允许本机或内网数字地址，URL 不能包含用户名或密码") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = "正在连接 $url …") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val effective = token?.takeIf { it.isNotBlank() }
                        ?: container.secretStore.get(server.tokenId)
                    val client = McpClient(url, effective)
                    withTimeout(20_000) {
                        val name = client.initialize()
                        val tools = client.listTools()
                        buildString {
                            append("连接成功")
                            if (name.isNotBlank()) append("：$name")
                            append("，发现 ${tools.size} 个工具")
                            if (tools.isNotEmpty()) {
                                append("（").append(tools.take(5).joinToString(", ") { it.name })
                                if (tools.size > 5) append(" …")
                                append("）")
                            }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = result.fold(
                        onSuccess = { text -> text },
                        onFailure = { error -> "连接失败：${error.message ?: error.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    fun refreshTools() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val summary = withContext(Dispatchers.IO) {
                runCatching { container.mcpTools.ensureFresh(force = true) }.getOrNull()
            }
            _state.update { it.copy(busy = false, toolSummary = summary?.message(), message = summary?.message()) }
        }
    }
}
