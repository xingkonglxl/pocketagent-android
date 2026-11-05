package app.pocketagent.android.net

import app.pocketagent.android.data.ProviderKind

/** 按来源类型挑协议实现，AgentRunner 与摘要器共用。 */
object LlmClients {
    fun of(kind: ProviderKind): LlmClient = when (kind) {
        ProviderKind.CHAT_COMPLETIONS -> ChatCompletionsClient()
        ProviderKind.RESPONSES -> ResponsesClient()
        ProviderKind.ANTHROPIC -> AnthropicClient()
    }
}
