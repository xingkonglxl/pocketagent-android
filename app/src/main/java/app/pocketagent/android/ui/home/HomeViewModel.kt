package app.pocketagent.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pocketagent.android.AppContainer
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations = _conversations.asStateFlow()

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    val providers = _providers.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (convs, provs) = withContext(Dispatchers.IO) {
                container.conversationStore.load() to container.providerStore.load()
            }
            _conversations.value = convs
            _providers.value = provs
        }
    }

    fun createConversation(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val provider = _providers.value.firstOrNull()
            // 新对话默认本地工作区；远端选择属于某一条会话，不能串到下一会话。
            container.settings.remoteWorkspaceHostId = null
            container.settings.remoteWorkspaceDir = ""
            val conversation = Conversation(
                providerId = provider?.id,
                workspaceHostId = null,
                workspaceDir = null,
            )
            withContext(Dispatchers.IO) { container.conversationStore.upsert(conversation) }
            provider?.let { container.settings.lastProviderId = it.id }
            _conversations.value = listOf(conversation) + _conversations.value
            onReady(conversation.id)
        }
    }

    /**
     * 首页删对话：必须走 /sync 同一套删除逻辑（写墓碑 + 删 Mac 上的副本），
     * 否则下次同步会把它又拉回来。
     */
    fun delete(id: String) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                // 只清理用户在「历史上下文」里明确选中的同步目标；没有选就只删本机
                val hostId = container.settings.syncHostId
                runCatching { container.syncService.deleteConversations(listOf(id), hostId) }
                container.conversationStore.load()
            }
            _conversations.value = list
        }
    }
}
