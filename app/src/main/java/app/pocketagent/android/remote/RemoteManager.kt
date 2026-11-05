package app.pocketagent.android.remote

import app.pocketagent.android.security.SecretStore
import java.io.File

/**
 * 管理「当前选中的远程主机」这一条 SSH 连接：
 * - 主机配置在 ssh_hosts.json（不含私钥）
 * - 私钥在 SecretStore（Keystore 加密），key 名 `ssh_key_<hostId>`
 * - 连接复用，切换主机或连接出错时重建
 */
class RemoteManager(
    private val store: RemoteConfigStore,
    private val secrets: SecretStore,
    /** known_hosts：首次连接记住主机公钥，之后不一致就拒绝（防中间人） */
    private val knownHostsFile: File = File(System.getProperty("java.io.tmpdir") ?: ".", "pocketagent_known_hosts"),
    private val connect: (SshHost) -> RemoteShell = {
        SshRemoteShell(it, hostKeyVerifier = TofuHostKeyVerifier(knownHostsFile))
    },
) : RemoteOperations {
    /** 测试连接等自己建 shell 的地方也用同一套主机校验 */
    fun hostKeyVerifier(): net.schmizz.sshj.transport.verification.HostKeyVerifier =
        TofuHostKeyVerifier(knownHostsFile)

    private var cachedId: String? = null
    private var cachedShell: RemoteShell? = null

    override fun hosts(): List<SshHost> = store.load()

    override fun host(id: String?): SshHost? {
        val list = store.load()
        if (id.isNullOrBlank()) return list.firstOrNull { it.enabled }

        val wanted = id.trim()
        list.firstOrNull { it.id.equals(wanted, ignoreCase = true) }?.let { return it }
        list.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it }

        // 兼容简短名字，但只有唯一匹配才采用；含糊匹配返回 null，避免命令发到错误机器。
        return list.asSequence()
            .filter { it.enabled }
            .filter {
                it.id.contains(wanted, ignoreCase = true) ||
                    it.name.contains(wanted, ignoreCase = true)
            }
            .take(2)
            .toList()
            .singleOrNull()
    }

    override suspend fun shell(id: String?): RemoteShell {
        val host = host(id) ?: throw RemoteConnectionException(hostLookupError(id))
        if (!host.enabled) {
            throw RemoteConnectionException("远程主机「${host.label()}」已禁用；先在设置里启用，或指定另一台主机")
        }
        val key = resolveKey(host.id)
        if (key.isNullOrBlank()) {
            throw RemoteConnectionException("${host.label()} 还没有导入私钥；先在远程设置里导入对应 PEM/PPK")
        }
        synchronized(this) {
            if (cachedId == host.id && cachedShell != null) return cachedShell!!
        }
        close()
        val shell = connect(host.withKey(key, secrets.get(passphraseName(host.id))))
        synchronized(this) {
            cachedId = host.id
            cachedShell = shell
        }
        return shell
    }

    override suspend fun exec(command: String, timeoutMs: Long, hostId: String?): RemoteResult =
        try {
            shell(hostId).exec(command, timeoutMs)
        } catch (e: RemoteConnectionException) {
            // 连接层失败（对端断开、隧道重连、Mac 睡眠醒来）时，这条 shell 已经不可信。
            // 以前这里直接往外抛、缓存保留，之后每条命令都会立刻报同一个
            // "Broken transport; encountered EOF"，同步 /「全部保留」全挂。
            close()
            throw e
        } catch (e: Exception) {
            // 连接可能已经断了，丢缓存，下一次重建
            close()
            throw e
        }

    suspend fun close() {
        val shell = synchronized(this) {
            val s = cachedShell
            cachedShell = null
            cachedId = null
            s
        }
        runCatching { shell?.close() }
    }

    fun keyName(hostId: String): String = "ssh_key_$hostId"

    fun passphraseName(hostId: String): String = "ssh_passphrase_$hostId"

    private fun hostLookupError(requested: String?): String {
        val all = store.load()
        if (all.isEmpty()) {
            return "还没有配置远程主机。先在设置里添加主机并导入私钥，或省略 host 使用默认启用主机。"
        }
        val enabled = all.filter { it.enabled }
        if (enabled.isEmpty()) return "所有远程主机都已禁用；先在设置里启用一台主机。"
        val choices = enabled.take(8).joinToString("、") { "${it.label()}（id=${it.id}）" }
        val suffix = if (enabled.size > 8) " 等 ${enabled.size} 台" else ""
        if (requested.isNullOrBlank()) return "没有启用的远程主机。可用：$choices$suffix"
        return "找不到唯一的远程主机「$requested」。可用：$choices$suffix。请改用完整 id 或名字。"
    }

    /** A host may only use the identity explicitly imported for it. */
    private fun resolveKey(hostId: String): String? =
        secrets.get(keyName(hostId))?.takeIf { it.isNotBlank() }
}
