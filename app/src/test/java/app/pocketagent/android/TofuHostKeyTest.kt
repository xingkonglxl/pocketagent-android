package app.pocketagent.android

import app.pocketagent.android.remote.TofuHostKeyVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.PublicKey

class TofuHostKeyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKey(private val blob: ByteArray) : PublicKey {
        override fun getAlgorithm() = "ssh-ed25519"
        override fun getFormat() = "X.509"
        override fun getEncoded() = blob
    }

    @Test
    fun `first connection is pinned and repeated connects pass`() {
        val file = File(tmp.newFolder(), "known_hosts")
        val verifier = TofuHostKeyVerifier(file)
        val key = FakeKey(byteArrayOf(1, 2, 3))

        assertTrue("第一次连接应放行并记住", verifier.verify("remote-host", 22, key))
        assertTrue("同一把钥匙再连应通过", TofuHostKeyVerifier(file).verify("remote-host", 22, key))
        assertTrue(file.readText().contains("remote-host:22"))
    }

    @Test
    fun `changed host key is rejected`() {
        val file = File(tmp.newFolder(), "known_hosts")
        val first = TofuHostKeyVerifier(file)
        assertTrue(first.verify("mac", 2222, FakeKey(byteArrayOf(1, 2, 3))))

        val second = TofuHostKeyVerifier(file)
        assertFalse("指纹变了必须拒绝（可能是中间人）", second.verify("mac", 2222, FakeKey(byteArrayOf(9, 9, 9))))
        assertTrue("同主机的另一端口单独记", second.verify("mac", 22, FakeKey(byteArrayOf(9, 9, 9))))
    }
    @Test
    fun `corrupt or empty trust store rejects without replacing pins`() {
        for (content in listOf("corrupt", "", "host:22\tnot-a-fingerprint\n")) {
            val file = File(tmp.newFolder(), "known_hosts").apply { writeText(content) }
            assertFalse(TofuHostKeyVerifier(file).verify("host", 22, FakeKey(byteArrayOf(1))))
            assertTrue(file.readText() == content)
        }
    }

    @Test
    fun `concurrent verifier instances preserve every pinned host`() {
        val file = File(tmp.newFolder(), "known_hosts")
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val threads = (1..16).map { n -> Thread {
            results += TofuHostKeyVerifier(file).verify("host-$n", 22, FakeKey(byteArrayOf(n.toByte())))
        }.apply { start() } }
        threads.forEach { it.join() }
        assertTrue(results.size == 16 && results.all { it })
        assertTrue(file.readLines().size == 16)
    }

}
