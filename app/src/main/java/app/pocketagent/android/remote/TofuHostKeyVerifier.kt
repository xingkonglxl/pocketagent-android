package app.pocketagent.android.remote

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * 首次连接记住主机公钥指纹（TOFU，和 ssh 的 known_hosts 一个思路）：
 * - 没记录过 → 记下来并放行（第一次连 remote-host 不会卡住）
 * - 记过且一致 → 放行
 * - 记过但不一致 → 拒绝（可能有人在中路冒充主机）
 *
 * 之前用的是 PromiscuousVerifier，等于 `StrictHostKeyChecking=no`：
 * 任何能劫持链路的人都能冒充 remote-host / Mac，读走命令输出、写入文件。
 */
class TofuHostKeyVerifier(
    private val file: File,
    private val onPinned: (host: String, fingerprint: String) -> Unit = { _, _ -> },
) : HostKeyVerifier {

    private val lock = locks.getOrPut(file.canonicalPath) { Any() }

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val fingerprint = fingerprintOf(key)
        return synchronized(lock) {
            runCatching {
                val next = load().toMutableMap()
                val known = next[id]
                if (known == null) {
                    next[id] = fingerprint
                    save(next)
                    runCatching { onPinned(id, fingerprint) }
                    true
                } else known == fingerprint
            }.getOrDefault(false) // A damaged/unreadable store must never reset trust.
        }
    }

    /** 让 sshj 不要提前按算法筛选，交给 [verify] 判断。 */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

    private fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val rows = file.readLines().filter { it.isNotBlank() }
        if (rows.isEmpty()) throw IOException("Empty SSH host-key store")
        val map = mutableMapOf<String, String>()
        rows.forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 2 || parts[0].isBlank() ||
                !parts[1].matches(Regex("SHA256:[A-Za-z0-9+/]{43}")) || map.containsKey(parts[0])) {
                throw IOException("Invalid SSH host-key store")
            }
            map[parts[0]] = parts[1]
        }
        return map
    }

    private fun save(map: Map<String, String>) {
        val destination = file.absoluteFile
        destination.parentFile!!.mkdirs()
        val tmp = File.createTempFile("known-hosts-", ".tmp", destination.parentFile)
        try {
            tmp.writeText(map.entries.joinToString("\n") { "${it.key}\t${it.value}" } + "\n")
            try {
                Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { tmp.delete() }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Any>()
        fun fingerprintOf(key: PublicKey): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
