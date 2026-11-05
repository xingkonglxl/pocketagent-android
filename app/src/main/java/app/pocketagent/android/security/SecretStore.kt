package app.pocketagent.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretStore {
    fun put(id: String, secret: String)
    fun get(id: String): String?
    fun remove(id: String)
    fun has(id: String): Boolean = !get(id).isNullOrEmpty()
}

class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<String, String>()
    override fun put(id: String, secret: String) { map[id] = secret }
    override fun get(id: String): String? = map[id]
    override fun remove(id: String) { map.remove(id) }
}

/** AES-GCM 信封：iv(12B) + 密文+tag。抽出来是为了能在 JVM 上单测。 */
object SecretEnvelope {
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain)
        return cipher.iv + ct
    }

    fun open(key: SecretKey, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "密文长度不合法" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}

/**
 * Key 存进 Android Keystore 保护的 AES-GCM：主密钥不可导出，
 * 落盘的只有密文；换手机或卸载应用后密文不可用。
 */
class KeystoreSecretStore(
    context: Context,
    private val keyProvider: () -> SecretKey = { androidKeystoreKey() },
) : SecretStore {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val masterKey: SecretKey by lazy { keyProvider() }

    override fun put(id: String, secret: String) {
        val blob = SecretEnvelope.seal(masterKey, secret.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(id, Base64.getEncoder().encodeToString(blob)).apply()
    }

    override fun get(id: String): String? {
        val encoded = prefs.getString(id, null) ?: return null
        return runCatching {
            String(SecretEnvelope.open(masterKey, Base64.getDecoder().decode(encoded)), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun remove(id: String) {
        prefs.edit().remove(id).apply()
    }

    /** 会话落盘加密用同一把 Keystore 主密钥 */
    fun storeCipher(): app.pocketagent.android.data.StoreCipher =
        app.pocketagent.android.data.StoreCipher(masterKey)

    companion object {
        private const val ALIAS = "pocketagent.secret.v1"

        /** 主密钥放在 Android Keystore 里：私钥材料不可导出，只能在本机使用。 */
        fun androidKeystoreKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            return generator.generateKey()
        }
    }
}

/** UI 上只露出尾号，避免整串 key 出现在屏幕上。 */
fun maskSecret(secret: String?): String {
    if (secret.isNullOrEmpty()) return "未配置"
    // 只显示固定掩码：之前回显后 4 位，截图分享时会带出真实 Key 片段。
    // 需要区分多个来源时看名称/模型即可，不需要看 Key 尾巴。
    return "••••（已保存）"
}
