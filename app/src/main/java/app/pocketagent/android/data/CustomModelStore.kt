package app.pocketagent.android.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * 用户自己加的模型 id（`store/custom_models.json`）。
 * 中转站上新模型时不用等发版，/model 里「添加自定义模型」直接加。
 */
class CustomModelStore(private val dir: File) {
    private val file = File(dir, "custom_models.json")
    private val serializer = ListSerializer(String.serializer())

    @Synchronized
    fun load(): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching { AppJson.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyList())
            .distinct()
    }

    @Synchronized
    fun add(id: String): List<String> {
        val clean = id.trim()
        if (clean.isEmpty()) return load()
        val list = (load() + clean).distinct()
        writeFileAtomically(file, AppJson.encodeToString(serializer, list))
        return list
    }

    @Synchronized
    fun remove(id: String): List<String> {
        val list = load().filterNot { it == id }
        writeFileAtomically(file, AppJson.encodeToString(serializer, list))
        return list
    }
}
