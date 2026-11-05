package app.pocketagent.android.remote

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.security.KeyPair

/** 把 [SshHost] 里的 PEM 私钥变成 sshj 认的 [KeyProvider]。做成接口是为了测试能替身。 */
fun interface SshKeyLoader {
    fun load(host: SshHost): KeyProvider

    companion object {
        /** 默认实现：直接吃 PEM 文本，不落盘。 */
        val Pem: SshKeyLoader = SshKeyLoader { loadPrivateKey(it.privateKeyPem, it.passphrase) }
    }
}

/**
 * PEM 字符串 → [KeyProvider]，全程在内存里，不写临时文件。
 *
 * 支持：
 * - `-----BEGIN OPENSSH PRIVATE KEY-----`（ssh-keygen 默认，ed25519/rsa/ecdsa）
 * - `-----BEGIN RSA PRIVATE KEY-----` / `-----BEGIN EC PRIVATE KEY-----`（PKCS#1）
 * - `-----BEGIN PRIVATE KEY-----`（PKCS#8）
 * - PuTTY 的 `.ppk`
 *
 * 带口令的私钥通过 [passphrase] 解；解不开会在这里提前抛错，而不是等到认证阶段。
 */
fun loadPrivateKey(pem: String, passphrase: String? = null): KeyProvider {
    val text = pem.trim()
    require(text.isNotEmpty()) {
        "SSH 私钥为空：请先在设置里填入 PEM 私钥（存 Keystore，不写 JSON）"
    }
    val format = runCatching { KeyProviderUtil.detectKeyFileFormat(text, false) }
        .getOrElse { throw IllegalArgumentException("无法识别的 SSH 私钥格式：${it.message}", it) }
    val provider: FileKeyProvider = when (format) {
        KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
        KeyFormat.OpenSSH -> OpenSSHKeyFile()
        KeyFormat.PKCS8 -> PKCS8KeyFile()
        KeyFormat.PuTTY -> PuTTYKeyFile()
        KeyFormat.Unknown -> throw IllegalArgumentException("无法识别的 SSH 私钥格式（需要 PEM 或 PuTTY 私钥）")
    }
    val finder = passphrase?.takeIf { it.isNotEmpty() }?.let { PasswordUtils.createOneOff(it.toCharArray()) }
    provider.init(text, null, finder)
    // 立刻解析一次：私钥格式坏 / 口令错，在建立连接前就能给出明确报错。
    runCatching { provider.type }.getOrElse {
        throw IllegalArgumentException("SSH 私钥解析失败（口令是否填错？）：${it.message}", it)
    }
    return provider
}

/** 已有 [KeyPair]（例如测试或安全模块生成）时，包一层给 sshj 用。 */
fun keyProviderOf(keyPair: KeyPair): KeyProvider = KeyPairWrapper(keyPair)
