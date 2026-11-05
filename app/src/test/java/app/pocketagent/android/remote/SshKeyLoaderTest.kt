package app.pocketagent.android.remote

import net.schmizz.sshj.common.KeyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 只在本机验证私钥解析，不连网络。真实的 `ssh-keygen` 私钥（OpenSSH v1 + bcrypt 口令）
 * 和 JCA 生成的 PKCS#8 都过一遍，确认 sshj 走的是内存字符串而不是临时文件。
 */
class SshKeyLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `PKCS8 PEM 直接加载`() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"

        val provider = loadPrivateKey(pem)
        assertEquals(KeyType.RSA, provider.type)
        val loaded = provider.public as RSAPublicKey
        assertEquals((kp.public as RSAPublicKey).modulus, loaded.modulus)
        assertEquals(kp.public.encoded.toList(), loaded.encoded.toList())
    }

    @Test
    fun `ssh-keygen 的 ed25519 私钥带口令也能加载`() {
        val keygen = File("/usr/bin/ssh-keygen")
        assumeTrue("没有 ssh-keygen 就跳过", keygen.canExecute())

        val dir = tmp.newFolder()
        val key = File(dir, "id_ed25519")
        val proc = ProcessBuilder(
            keygen.absolutePath, "-q", "-t", "ed25519", "-N", "s3cret pass", "-C", "pocketagent-test",
            "-f", key.absolutePath,
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        check(proc.waitFor(30, TimeUnit.SECONDS)) { "ssh-keygen 卡住了" }
        assertEquals("ssh-keygen 失败：$log", 0, proc.exitValue())

        val pem = key.readText()
        assertTrue("应该是 OpenSSH 新格式", pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))

        val provider = loadPrivateKey(pem, "s3cret pass")
        assertEquals(KeyType.ED25519, provider.type)
        assertNotNull(provider.public)

        // 口令错要给可读的报错，而不是拖到认证阶段
        val wrong = runCatching { loadPrivateKey(pem, "nope").type }
        assertTrue("应抛 IllegalArgumentException", wrong.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `空私钥给出中文提示`() {
        val e = runCatching { loadPrivateKey("   ") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("私钥为空"))
    }
}
