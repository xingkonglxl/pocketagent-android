package app.pocketagent.android.agent

import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.LlmClients
import app.pocketagent.android.net.LlmConfig

/**
 * 给一个模型 id 发一次最小请求：null = 可用，否则返回错误原因。
 * 按名字自动选协议（claude→messages，gpt/codex→responses，其它→chat），
 * 「加自定义模型」和「/test models」共用同一套判断。
 */
object ModelProbe {

    fun kindOf(modelId: String): ProviderKind = when {
        modelId.startsWith("claude", true) -> ProviderKind.ANTHROPIC
        modelId.startsWith("gpt", true) || modelId.startsWith("codex", true) -> ProviderKind.RESPONSES
        else -> ProviderKind.CHAT_COMPLETIONS
    }

    suspend fun probe(provider: Provider, key: String, modelId: String): String? {
        if (key.isBlank()) return "还没有可用的 Key"
        val kind = kindOf(modelId)
        val base = if (kind == ProviderKind.ANTHROPIC) provider.baseUrl.removeSuffix("/v1") else provider.baseUrl
        val config = provider.copy(kind = kind, baseUrl = base, model = modelId, toolsEnabled = false)
        return runCatching { LlmClients.of(kind).probe(LlmConfig(config, key)) }
            .getOrElse { e -> e.message ?: "请求失败" }
    }
}
