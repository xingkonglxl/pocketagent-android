package app.pocketagent.android.remote

import app.pocketagent.android.data.AppJson
import app.pocketagent.android.data.writeFileAtomically
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * `ssh_hosts.json` 的读写，风格对齐 `data/ProviderStore.kt`。
 *
 * 落盘的只有连接信息：私钥/口令是 `@Transient`，[save] 前还会再 [SshHost.withoutSecret] 兜一次底。
 * 私钥请放 `SecretStore`（id 建议 `ssh_key_<host.id>`），取出后 [SshHost.withKey] 再连接。
 */
class RemoteConfigStore(private val dir: File) {
    private val file = File(dir, "ssh_hosts.json")
    private val serializer = ListSerializer(SshHost.serializer())

    val path: File get() = file

    @Synchronized
    fun load(): List<SshHost> {
        if (!file.exists()) return emptyList()
        return runCatching { AppJson.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    @Synchronized
    fun save(hosts: List<SshHost>) {
        writeFileAtomically(file, AppJson.encodeToString(serializer, hosts.map { it.withoutSecret() }))
    }

    @Synchronized
    fun upsert(host: SshHost): List<SshHost> {
        val list = load().toMutableList()
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host.withoutSecret() else list.add(host.withoutSecret())
        save(list)
        return list
    }

    @Synchronized
    fun delete(id: String): List<SshHost> {
        val list = load().filterNot { it.id == id }
        save(list)
        return list
    }

    @Synchronized
    fun get(id: String): SshHost? = load().firstOrNull { it.id == id }
}
