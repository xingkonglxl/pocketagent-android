package app.pocketagent.android.data

import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class ProviderStore(
    private val dir: File,
    /** 内置模型目录；用来给没选过档位的来源挑一个和桌面 Codex 对齐的思考强度。 */
    private val catalog: List<CatalogModel> = emptyList(),
) {
    private val file = File(dir, "providers.json")
    private val serializer = ListSerializer(Provider.serializer())

    @Synchronized
    fun load(): List<Provider> {
        if (!file.exists()) return emptyList()
        return runCatching { AppJson.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyList())
            .map { it.sanitized() }
    }

    /**
     * 读取时做两件修正：
     * 1) 老版本把默认提示词原样写进了 providers.json，升级成新的默认提示词；
     * 2) 模型名/地址里混进换行、零宽字符或首尾空格时清掉——这类字符会让界面只显示半截。
     */
    private fun Provider.sanitized(): Provider {
        val fixedModel = model.clean()
        val fixedBase = baseUrl.clean()
        val fixedName = name.clean()
        val fixedKind = if (kind == ProviderKind.RESPONSES && fixedModel.startsWith("deepseek", true)) {
            ProviderKind.CHAT_COMPLETIONS
        } else {
            kind
        }
        val fixedPrompt = when (systemPrompt.trim()) {
            LEGACY_SYSTEM_PROMPT.trim(), PREVIOUS_SYSTEM_PROMPT.trim() -> DEFAULT_SYSTEM_PROMPT
            else -> systemPrompt
        }
        // 桌面 codex 用 model_reasoning_effort = "max"。App 以前不发 effort，服务端按模型默认档
        // （目录里 gpt-6-astra / deepseek-v4-flash 的默认档就是 low），同样的问题手机明显更浅。
        // 用户没亲手选过档位时，按模型目录挑最接近桌面的那一档；选过（含「默认」）就完全尊重。
        val fixedEffort = if (reasoningPinned) reasoningEffort else reasoningEffort ?: defaultEffortFor(fixedModel)
        // 旧默认 8 步会在研究类问题（如股票）上半途而废；桌面 Codex 没有这种硬上限。
        val fixedSteps = if (maxSteps == 8) 24 else maxSteps
        // Claude 旧默认 4096 会在长分析/长表格处被截断，而且旧界面调不了，统一抬到 8192
        val fixedOut = if (kind == ProviderKind.ANTHROPIC && maxOutputTokens == 4096) 8192 else maxOutputTokens
        return if (
            fixedModel == model && fixedBase == baseUrl && fixedName == name && fixedKind == kind &&
            fixedPrompt == systemPrompt && fixedEffort == reasoningEffort &&
            fixedSteps == maxSteps && fixedOut == maxOutputTokens
        ) {
            this
        } else {
            copy(
                model = fixedModel,
                baseUrl = fixedBase,
                name = fixedName,
                kind = fixedKind,
                systemPrompt = fixedPrompt,
                reasoningEffort = fixedEffort,
                maxSteps = fixedSteps,
                maxOutputTokens = fixedOut,
            )
        }
    }

    /** 目录里声明的档位从低到高排列；优先挑最高的一档，把 low 之类的默认档顶掉。 */
    private fun defaultEffortFor(model: String): String? {
        val levels = ModelCatalog.resolve(catalog, model)?.reasoningLevels.orEmpty()
            .filter { it != "none" }
        if (levels.isNotEmpty()) {
            return when {
                "max" in levels -> "max"
                "xhigh" in levels -> "xhigh"
                "high" in levels -> "high"
                "medium" in levels -> "medium"
                else -> levels.last()
            }
        }
        return if (model.startsWith("deepseek-v4")) "max" else null
    }

    /** 去掉控制符、零宽字符和 bidi 隔离符（未闭合的 U+2066/U+202A 会把后面半截文字吞掉）。 */
    private fun String.clean(): String = filterNot {
        it.isISOControl() ||
            Character.getType(it) == Character.FORMAT.toInt() ||
            it == '\u2028' || it == '\u2029'
    }.trim()

    @Synchronized
    fun save(providers: List<Provider>) {
        writeFileAtomically(file, AppJson.encodeToString(serializer, providers))
    }

    @Synchronized
    fun upsert(provider: Provider): List<Provider> {
        val list = load().toMutableList()
        val i = list.indexOfFirst { it.id == provider.id }
        if (i >= 0) list[i] = provider else list.add(provider)
        save(list)
        return list
    }

    @Synchronized
    fun delete(id: String): List<Provider> {
        val list = load().filterNot { it.id == id }
        save(list)
        return list
    }
}
