package app.pocketagent.android.mcp

import app.pocketagent.android.data.AppJson
import app.pocketagent.android.data.writeFileAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * 一个用户配置的 MCP 服务器。访问令牌不进这个文件，按 [tokenId] 存到 Keystore。
 */
@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
) {
    val tokenId: String get() = "mcp_token_$id"
}

/** `mcp_servers.json` 的读写，风格和 `RemoteConfigStore` 一致。 */
class McpServerStore(private val dir: File) {
    private val file = File(dir, "mcp_servers.json")
    private val serializer = ListSerializer(McpServerConfig.serializer())

    val path: File get() = file

    @Synchronized
    fun load(): List<McpServerConfig> {
        if (!file.exists()) return emptyList()
        return runCatching { AppJson.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    @Synchronized
    fun save(servers: List<McpServerConfig>) {
        writeFileAtomically(file, AppJson.encodeToString(serializer, servers))
    }

    @Synchronized
    fun upsert(server: McpServerConfig): List<McpServerConfig> {
        val list = load().toMutableList()
        val index = list.indexOfFirst { it.id == server.id }
        if (index >= 0) list[index] = server else list.add(server)
        save(list)
        return list
    }

    @Synchronized
    fun delete(id: String): List<McpServerConfig> {
        val list = load().filterNot { it.id == id }
        save(list)
        return list
    }

    @Synchronized
    fun get(id: String): McpServerConfig? = load().firstOrNull { it.id == id }
}
