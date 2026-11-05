package app.pocketagent.android.data

import android.content.Context
import kotlinx.serialization.Serializable

@Serializable
data class CatalogModel(
    val id: String,
    val displayName: String = id,
    val family: String = "other",
    /** none / low / medium / high / xhigh / max / ultra，空表示该模型不支持调节 */
    val reasoningLevels: List<String> = emptyList(),
    val defaultReasoning: String? = null,
    /** 模型上下文窗口（token），决定一次对话最多能带多少历史；0 表示未知 */
    val contextWindow: Int = 0,
    val inRelay: Boolean = false,
    val endpoints: List<String> = emptyList(),
    val visible: Boolean = true,
) {
    val supportsReasoning: Boolean get() = reasoningLevels.isNotEmpty()
}

@Serializable
private data class CatalogFile(val models: List<CatalogModel> = emptyList())

/**
 * 内置模型目录：来自中转站 /v1/models 与本地 codex 的 unified-model-catalog.json
 * （见 app/src/main/assets/model_catalog.json）。用来让 /model 能列出「有哪些模型、各自能调什么档位」。
 */
object ModelCatalog {

    private const val ASSET = "model_catalog.json"

    @Volatile
    private var cached: List<CatalogModel>? = null

    fun all(context: Context): List<CatalogModel> {
        cached?.let { return it }
        val parsed = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.mapCatching { json ->
            AppJson.decodeFromString(CatalogFile.serializer(), json).models
        }.getOrNull().orEmpty()
        val list = parsed.ifEmpty { fallback }
        cached = list
        return list
    }

    fun find(context: Context, id: String): CatalogModel? =
        resolve(all(context), id)

    /**
     * 在目录里找模型。顺序固定为：
     * 1. 原始 id 精确匹配；
     * 2. 去掉明确的网关前缀后，再做精确匹配；
     * 3. 去掉日期、版本号等尾缀后，只接受目录中唯一的同版本模型。
     *
     * 不做 contains/模糊匹配。多个目录条目共享同一版本根时返回 null，
     * 避免把 gpt-5 之类的通用名猜成某个具体型号。
     */
    fun resolve(models: List<CatalogModel>, id: String): CatalogModel? {
        val requested = requestedIds(id)
        if (requested.isEmpty()) return null

        requested.forEach { candidate ->
            exactModel(models, candidate)?.let { return it }
        }
        requested.flatMap(::withVersionStems).distinct().forEach { candidate ->
            exactModel(models, candidate)?.let { return it }
        }

        val requestedVersions = requested.map(::versionParts)
        return models.filter { model ->
            val catalogVersion = versionParts(model.id)
            requestedVersions.any { it.isCompatibleWith(catalogVersion) }
        }.singleOrNull()
    }

    /** 模型上下文窗口（token）；未知模型或目录值为非法值时返回 0，由调用方套用兜底值。 */
    fun contextTokensFor(models: List<CatalogModel>, id: String): Int {
        val model = resolve(models, id)
        if (model != null) return model.contextWindow.takeIf { it > 0 } ?: 0
        return idCandidates(id)
            .asSequence()
            .map(::versionStem)
            .mapNotNull { KNOWN_CONTEXT_WINDOWS[it] }
            .firstOrNull()
            ?: 0
    }

    private fun exactModel(models: List<CatalogModel>, id: String): CatalogModel? =
        models.firstOrNull { it.id == id }
            ?: models.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /**
     * 网关常见写法是 openrouter/openai/<model>、azure:<model>、us.anthropic.<model>。
     * 只在分隔符后紧跟已知模型族时剥离；my-gpt 这类连字符前缀不会被误认成网关。
     */
    private fun idCandidates(id: String): List<String> =
        requestedIds(id).flatMap(::withVersionStems).distinct()

    private fun withVersionStems(value: String): List<String> {
        val result = mutableListOf<String>()
        var current = value.trim()
        while (current.isNotEmpty() && current !in result) {
            result += current
            current = stripVersionSuffix(current) ?: break
        }
        return result
    }

    private fun requestedIds(id: String): List<String> {
        val raw = id.trim()
        if (raw.isEmpty()) return emptyList()
        return listOfNotNull(raw, stripGatewayPrefix(raw)).distinct()
    }

    private fun stripGatewayPrefix(id: String): String? {
        for (index in 1 until id.length) {
            if (id[index] !in GATEWAY_SEPARATORS) continue
            val tail = id.substring(index + 1)
            if (KNOWN_MODEL_FAMILY_PREFIXES.any { tail.startsWith(it, ignoreCase = true) }) {
                return tail
            }
        }
        return null
    }

    private fun stripVersionSuffix(value: String): String? {
        val match = versionSuffixMatch(value) ?: return null
        return value.substring(0, match.range.first)
    }

    private fun versionSuffixMatch(value: String): MatchResult? =
        VERSION_SUFFIXES.firstNotNullOfOrNull { suffix ->
            suffix.find(value)?.takeIf {
                it.range.first > 0 && it.range.last == value.lastIndex
            }
        }

    private data class VersionParts(
        val stem: String,
        val suffixes: List<String>,
    ) {
        fun isCompatibleWith(other: VersionParts): Boolean {
            if (stem != other.stem) return false
            if (suffixes.isEmpty() || other.suffixes.isEmpty()) return true
            val shorter = if (suffixes.size <= other.suffixes.size) suffixes else other.suffixes
            val longer = if (suffixes.size <= other.suffixes.size) other.suffixes else suffixes
            return longer.take(shorter.size) == shorter
        }
    }

    private fun versionParts(id: String): VersionParts {
        var value = id.trim()
        val suffixes = mutableListOf<String>()
        while (true) {
            val match = versionSuffixMatch(value) ?: break
            suffixes.add(0, match.value.lowercase())
            value = value.substring(0, match.range.first)
        }
        return VersionParts(stem = value.lowercase(), suffixes = suffixes)
    }

    private fun versionStem(id: String): String = versionParts(id).stem

    /**
     * 自建中转/网关按模型族选协议：claude 只支持 /v1/messages（走 /responses 会 500），
     * 其余（gpt / deepseek / gemini 的 openai 端点）走 /responses。官方来源保持用户选的协议。
     *
     * 判断“是不是自建网关”只看主机名：官方 API 域名之外的都按网关处理，
     * 这样不在代码里写死任何公司/个人域名。
     */
    fun kindFor(baseUrl: String, current: ProviderKind, modelId: String): ProviderKind {
        val host = runCatching { java.net.URI(baseUrl).host.orEmpty() }.getOrDefault("")
        val official = OFFICIAL_HOSTS.any { officialHost ->
            host.equals(officialHost, ignoreCase = true) || host.endsWith(".$officialHost", ignoreCase = true)
        }
        if (host.isBlank() || official) return current
        return when {
            modelId.startsWith("claude", ignoreCase = true) -> ProviderKind.ANTHROPIC
            modelId.startsWith("deepseek", ignoreCase = true) -> ProviderKind.CHAT_COMPLETIONS
            // GPT/Codex 走 Responses；DeepSeek 走 Chat Completions，
            // 与 Mac 的 DeepSeek adapter 保持同一条工具协议。
            RESPONSES_FAMILIES.any { modelId.startsWith(it, ignoreCase = true) } -> ProviderKind.RESPONSES
            else -> current
        }
    }

    private val OFFICIAL_HOSTS = listOf(
        "api.openai.com",
        "api.anthropic.com",
        "api.deepseek.com",
        "generativelanguage.googleapis.com",
        "open.bigmodel.cn",
        "dashscope.aliyuncs.com",
    )

    private val RESPONSES_FAMILIES = listOf("gpt-5", "gpt-6")

    private val GATEWAY_SEPARATORS = setOf('/', ':', '.', '@')

    private val KNOWN_MODEL_FAMILY_PREFIXES = listOf(
        "gpt-",
        "codex-",
        "claude-",
        "deepseek-",
        "gemini-",
    )

    /** 只包含发布方明确稳定的快照/版本尾缀，preview、mini 等能力变体不在这里剥离。 */
    private val VERSION_SUFFIXES = listOf(
        Regex("(?i)@latest$"),
        Regex("(?i)-latest$"),
        Regex("(?i):\\d{1,4}$"),
        Regex("(?i)-v\\d+(?:[.-]\\d+)?$"),
        Regex("(?i)-\\d{8}$"),
        Regex("(?i)@\\d{8}$"),
        Regex("(?i)-\\d{4}-\\d{2}-\\d{2}$"),
    )

    /**
     * 旧型号可能已从当前目录删除，但公开上下文窗口仍是稳定事实。
     * GPT-5-Codex 的 400k 见 https://developers.openai.com/api/docs/models/gpt-5-codex
     * 这里只补 context，不把旧型号继承成目录中其它型号的思考档位或端点能力。
     */
    private val KNOWN_CONTEXT_WINDOWS = mapOf(
        "gpt-5-codex" to 400_000,
        "claude-sonnet-4-5" to 200_000,
    )

    /** 模型没登记时，给一组保守的默认档位。 */
    fun levelsFor(context: Context, id: String): List<String> =
        find(context, id)?.reasoningLevels?.ifEmpty { defaultLevels } ?: defaultLevels

    val defaultLevels = listOf("none", "low", "medium", "high")

    val levelLabels = mapOf(
        "none" to "关",
        "minimal" to "极低",
        "low" to "低",
        "medium" to "中",
        "high" to "高",
        "xhigh" to "很高",
        "max" to "最高",
        "ultra" to "极限",
    )

    fun label(level: String): String = levelLabels[level] ?: level

    private val fallback = listOf(
        CatalogModel(
            id = "deepseek-v4-flash",
            displayName = "DeepSeek V4.1 Flash",
            family = "deepseek",
            reasoningLevels = listOf("low", "high", "max"),
            defaultReasoning = "low",
            contextWindow = 1_000_000,
            inRelay = true,
        ),
        CatalogModel(
            id = "gpt-5-codex",
            displayName = "GPT-5 Codex",
            family = "gpt",
            reasoningLevels = listOf("low", "medium", "high", "xhigh"),
            defaultReasoning = "medium",
            contextWindow = 400_000,
            inRelay = true,
        ),
        CatalogModel(
            id = "claude-sonnet-4-5",
            displayName = "Claude Sonnet 4.5",
            family = "claude",
            reasoningLevels = listOf("none", "low", "medium", "high"),
            defaultReasoning = "medium",
            contextWindow = 200_000,
            inRelay = true,
        ),
    )
}
