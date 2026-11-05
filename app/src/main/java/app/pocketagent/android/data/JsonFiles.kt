package app.pocketagent.android.data

import kotlinx.serialization.json.Json
import java.io.File

val AppJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 写文件走临时文件 + rename，避免进程被杀时留下半个 JSON。 */
fun writeFileAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(text)
    // rename 在 Linux/Android 上是原子的，会直接覆盖旧文件。
    // 不要先 delete 再 rename：中间那一瞬别的线程读到「文件不存在」，
    // 会把整个会话列表当成空的（之前同步后列表闪空的根因）。
    if (tmp.renameTo(target)) return
    if (target.exists()) target.delete()
    if (tmp.renameTo(target)) return
    target.writeText(text)
    tmp.delete()
}

/**
 * 会话/同步状态落盘加密：AES-GCM，密钥在 Android Keystore 里不可导出。
 * 老文件是明文，读的时候自动兼容；下次写入就变成密文（带 PAEN1 头）。
 */
class StoreCipher(private val key: javax.crypto.SecretKey) {

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        return MAGIC + cipher.iv + cipher.doFinal(plain)
    }

    /** 不是密文（老格式）返回 null，让调用方按明文读。 */
    fun decrypt(data: ByteArray): ByteArray? {
        if (data.size < MAGIC.size + 12 + 16 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val iv = data.copyOfRange(MAGIC.size, MAGIC.size + 12)
        val body = data.copyOfRange(MAGIC.size + 12, data.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    companion object {
        private val MAGIC = "PAEN1".toByteArray(Charsets.US_ASCII)
    }
}

fun readStore(file: File, cipher: StoreCipher?): String? {
    if (!file.exists()) return null
    val bytes = file.readBytes()
    if (cipher != null) {
        val plain = runCatching { cipher.decrypt(bytes) }.getOrNull()
        if (plain != null) return String(plain, Charsets.UTF_8)
    }
    return String(bytes, Charsets.UTF_8)
}

fun writeStore(file: File, text: String, cipher: StoreCipher?) {
    // 保险：磁盘上是密文、这次却没有密钥，宁可这次不写，也不能用明文/空内容盖掉历史
    if (cipher == null && file.exists()) {
        val head = file.readBytes().take(5).toByteArray()
        require(!head.contentEquals("PAEN1".toByteArray(Charsets.US_ASCII))) {
            "磁盘上是加密数据但这次拿不到密钥，拒绝覆盖"
        }
    }
    file.parentFile?.mkdirs()
    // 落盘一律明文：加密读失败会把历史当空、再写回空，之前已经踩过一次。
    // 密文只在读的时候兼容（老版本写的），下次保存自动变回明文。
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (tmp.renameTo(file)) return
    if (file.exists()) file.delete()
    if (tmp.renameTo(file)) return
    file.writeText(text)
    tmp.delete()
}
