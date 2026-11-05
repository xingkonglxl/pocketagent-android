package app.pocketagent.android.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** 用户从 /model 列表里删掉的模型（内置的只隐藏，不动 assets）。 */
class HiddenModelStore(private val dir: File) {
    private val file = File(dir, "hidden_models.json")
    private val serializer = ListSerializer(String.serializer())

    @Synchronized
    fun load(): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching { AppJson.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyList())
            .distinct()
    }

    @Synchronized
    fun hide(id: String): List<String> {
        val list = (load() + id).distinct()
        writeFileAtomically(file, AppJson.encodeToString(serializer, list))
        return list
    }

    @Synchronized
    fun unhide(id: String): List<String> {
        val list = load().filterNot { it == id }
        writeFileAtomically(file, AppJson.encodeToString(serializer, list))
        return list
    }

    @Synchronized
    fun clear(): List<String> {
        writeFileAtomically(file, AppJson.encodeToString(serializer, emptyList<String>()))
        return emptyList()
    }
}
