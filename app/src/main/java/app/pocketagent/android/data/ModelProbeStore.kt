package app.pocketagent.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

@Serializable
data class ModelProbe(
    val model: String,
    val ok: Boolean,
    val error: String = "",
    val at: Long = System.currentTimeMillis(),
)

/**
 * 每个模型最近一次实测的结果（`store/model_probe.json`）。
 * /model 列表里据此把不可用的模型标出来，避免选中了才发现 503。
 */
class ModelProbeStore(private val dir: File) {
    private val file = File(dir, "model_probe.json")
    private val serializer = MapSerializer(String.serializer(), ModelProbe.serializer())

    @Synchronized
    fun load(): Map<String, ModelProbe> {
        if (!file.exists()) return emptyMap()
        return runCatching { AppJson.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyMap())
    }

    @Synchronized
    fun save(results: Map<String, ModelProbe>) {
        writeFileAtomically(file, AppJson.encodeToString(serializer, results))
    }

    @Synchronized
    fun put(results: Collection<ModelProbe>): Map<String, ModelProbe> {
        val merged = load().toMutableMap()
        results.forEach { merged[it.model] = it }
        save(merged)
        return merged
    }
}
