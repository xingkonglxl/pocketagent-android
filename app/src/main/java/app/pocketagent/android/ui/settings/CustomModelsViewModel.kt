package app.pocketagent.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pocketagent.android.AppContainer
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.LlmClients
import app.pocketagent.android.net.LlmConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.pocketagent.android.ui.i18n.L10n

data class CustomModelsUiState(
    /** 用户自己加的模型 */
    val models: List<String> = emptyList(),
    /** /model 里当前可见的全部模型（内置 + 自定义，去掉已隐藏） */
    val all: List<String> = emptyList(),
    /** 被删掉（隐藏）的模型 */
    val hidden: List<String> = emptyList(),
    val results: Map<String, Boolean> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * 自定义模型管理：**先在真实接口上测通才允许加入**，避免污染 /model；
 * 也支持逐个删除和一键清理所有不可用的模型。
 */
class CustomModelsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(CustomModelsUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val custom = container.customModelStore.load()
        val hidden = container.hiddenModelStore.load()
        val all = (container.modelCatalog.map { it.id } + custom)
            .distinct()
            .filterNot { it in hidden }
        _state.update { it.copy(models = custom, all = all, hidden = hidden) }
    }

    /** 从 /model 里删掉（内置的只隐藏，随时能恢复） */
    fun hide(id: String) {
        if (id in _state.value.models) container.customModelStore.remove(id)
        container.hiddenModelStore.hide(id)
        _state.update { it.copy(message = "已从 /model 移除 $id") }
        refresh()
    }

    /** 恢复全部被隐藏的模型 */
    fun restoreAll() {
        container.hiddenModelStore.clear()
        _state.update { it.copy(message = L10n.t("已恢复全部模型")) }
        refresh()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** 按模型家族选协议 + 用当前有 Key 的来源去探活；null 表示通过 */
    private suspend fun probe(id: String): String? {
        val provider = container.providerStore.load()
            .firstOrNull { !container.secretStore.get(it.id).isNullOrBlank() }
            ?: return L10n.t("还没有配置 Key 的来源")
        val key = container.secretStore.get(provider.id)!!.trim()
        val kind = when {
            id.startsWith("claude", true) -> ProviderKind.ANTHROPIC
            id.startsWith("gpt", true) || id.startsWith("codex", true) -> ProviderKind.RESPONSES
            else -> ProviderKind.CHAT_COMPLETIONS
        }
        val base = if (kind == ProviderKind.ANTHROPIC) provider.baseUrl.removeSuffix("/v1") else provider.baseUrl
        val config: Provider = provider.copy(kind = kind, baseUrl = base, model = id, toolsEnabled = false)
        return runCatching { LlmClients.of(kind).probe(LlmConfig(config, key)) }
            .getOrElse { e -> e.message ?: L10n.t("请求失败") }
    }

    /** 测试通过才入库；已经有（内置或已加）就不重复加 */
    fun add(id: String) {
        val clean = id.trim().filter { it.isLetterOrDigit() || it in "-._:/+" }
        if (clean.isEmpty() || _state.value.busy) return
        val isBuiltin = container.modelCatalog.any { it.id.equals(clean, ignoreCase = true) }
        val isCustom = _state.value.models.any { it.equals(clean, ignoreCase = true) }
        if (isBuiltin) {
            _state.update { it.copy(message = "「$clean」是内置模型，直接 /model 里选就行，不用重复添加") }
            return
        }
        if (isCustom) {
            _state.update { it.copy(message = "「$clean」已经在列表里了") }
            return
        }
        _state.update { it.copy(busy = true, message = "正在测试 $clean …") }
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { probe(clean) }
            if (err == null) {
                container.customModelStore.add(clean)
                _state.update {
                    it.copy(
                        busy = false,
                        models = container.customModelStore.load(),
                        results = it.results + (clean to true),
                        message = "✅ $clean 测试通过，已加入",
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        results = it.results + (clean to false),
                        message = "❌ $clean 测试失败，未添加：$err",
                    )
                }
            }
        }
    }

    fun remove(id: String) {
        container.customModelStore.remove(id)
        _state.update { it.copy(models = container.customModelStore.load(), message = "已删除 $id") }
    }

    /** 一键清理：把 /model 里所有模型测一遍，失败的删掉 */
    fun cleanup() {
        if (_state.value.busy) return
        val list = _state.value.all
        if (list.isEmpty()) {
            _state.update { it.copy(message = L10n.t("没有自定义模型")) }
            return
        }
        _state.update { it.copy(busy = true, message = "正在逐个测试 ${list.size} 个模型…") }
        viewModelScope.launch {
            val results = mutableMapOf<String, Boolean>()
            val dead = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                list.forEach { id ->
                    val err = probe(id)
                    results[id] = err == null
                    if (err != null) dead += id
                }
            }
            dead.forEach { id ->
                if (id in container.customModelStore.load()) container.customModelStore.remove(id)
                container.hiddenModelStore.hide(id)
            }
            _state.update {
                it.copy(
                    busy = false,
                    models = container.customModelStore.load(),
                    all = (container.modelCatalog.map { it.id } + container.customModelStore.load())
                        .distinct().filterNot { it in container.hiddenModelStore.load() },
                    hidden = container.hiddenModelStore.load(),
                    results = results,
                    message = if (dead.isEmpty()) "全部 ${list.size} 个都可用，没有清理"
                    else "已清理 ${dead.size} 个不可用模型：" + dead.joinToString("、"),
                )
            }
        }
    }
}
