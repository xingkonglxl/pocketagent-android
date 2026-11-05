package app.pocketagent.android.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pocketagent.android.AppContainer
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ChatCompletionsClient
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.ResponsesClient
import app.pocketagent.android.security.maskSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.pocketagent.android.ui.i18n.L10n

data class ProvidersUiState(
    val providers: List<Provider> = emptyList(),
    val keyMasks: Map<String, String> = emptyMap(),
    val busyId: String? = null,
    val message: String? = null,
)

class ProvidersViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ProvidersUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (providers, masks) = withContext(Dispatchers.IO) {
                val list = container.providerStore.load()
                list to list.associate { it.id to maskSecret(container.secretStore.get(it.id)) }
            }
            _state.value = _state.value.copy(providers = providers, keyMasks = masks)
        }
    }

    /** apiKey 为 null 表示沿用原来的 key。 */
    fun save(provider: Provider, apiKey: String?) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.providerStore.upsert(provider)
                    if (apiKey != null) {
                        if (apiKey.isBlank()) container.secretStore.remove(provider.id)
                        else container.secretStore.put(provider.id, apiKey.trim())
                    }
                }
            }
            refresh()
            _state.update {
                it.copy(
                    message = result.fold(
                        onSuccess = {
                            "已保存 ${provider.name}（Key ${maskSecret(container.secretStore.get(provider.id))}）"
                        },
                        onFailure = { e -> "保存失败：${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.providerStore.delete(id)
                container.secretStore.remove(id)
            }
            refresh()
        }
    }

    fun probe(id: String) {
        val provider = _state.value.providers.firstOrNull { it.id == id } ?: return
        val key = container.secretStore.get(id)
        if (key.isNullOrBlank()) {
            _state.update { it.copy(message = "${provider.name}：还没填 Key") }
            return
        }
        _state.update { it.copy(busyId = id, message = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                app.pocketagent.android.net.LlmClients.of(provider.kind).probe(LlmConfig(provider, key))
            }
            _state.update {
                it.copy(
                    busyId = null,
                    message = result?.let { m -> "${provider.name}：$m" } ?: "${provider.name}：连接正常",
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * 用现有来源的 Key 一键补齐「中转站 · Codex」「中转站 · Claude」两个来源：
     * 三家共用同一把 key，只需要在 /model 里切换，不用每次重新填。
     */
    fun addRelayVariants() {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                val existing = container.providerStore.load()
                val base = existing.firstOrNull {
                    !container.secretStore.get(it.id).isNullOrBlank()
                } ?: return@withContext L10n.t("先添加一个来源并填好 Key，再一键补全")
                val key = container.secretStore.get(base.id)!!.trim()
                val wanted = listOf(
                    app.pocketagent.android.data.ProviderPresets.relayResponses().copy(baseUrl = base.baseUrl.trimEnd('/')),
                    app.pocketagent.android.data.ProviderPresets.relayClaude().copy(baseUrl = base.baseUrl.trimEnd('/').removeSuffix("/v1")),
                )
                val added = wanted.filter { p -> existing.none { it.kind == p.kind } }
                added.forEach { p ->
                    container.providerStore.upsert(p)
                    container.secretStore.put(p.id, key)
                }
                if (added.isEmpty()) {
                    "Codex / Claude 来源已经都有了（共 ${existing.size} 个）"
                } else {
                    "已用 ${base.name} 的 Key 补上：" + added.joinToString("、") { it.name }
                }
            }
            refresh()
            _state.update { it.copy(message = message) }
        }
    }
}
