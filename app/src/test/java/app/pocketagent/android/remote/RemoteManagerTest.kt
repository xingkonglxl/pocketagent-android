package app.pocketagent.android.remote

import app.pocketagent.android.security.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(hosts: List<SshHost>): RemoteManager {
        val store = RemoteConfigStore(tmp.newFolder())
        store.save(hosts)
        return RemoteManager(store, InMemorySecretStore())
    }

    private val alpha = SshHost(
        id = "alpha-id",
        name = "Alpha",
        host = "alpha.invalid",
        user = "tester",
    )
    private val alphaProd = SshHost(
        id = "alpha-prod-id",
        name = "Alpha Prod",
        host = "alpha-prod.invalid",
        user = "tester",
    )

    @Test
    fun `host 精确匹配优先且含糊匹配拒绝猜测`() {
        val remote = manager(listOf(alpha, alphaProd))
        assertEquals(alpha.id, remote.host("alpha")?.id)
        assertEquals(alphaProd.id, remote.host("alpha prod")?.id)
        assertNull("alpha 前缀同时匹配两台，不能猜", remote.host("alpha-"))
    }

    @Test
    fun `默认主机只选启用的主机`() {
        val disabled = alpha.copy(id = "off", name = "Off", enabled = false)
        val remote = manager(listOf(disabled, alphaProd))
        assertEquals(alphaProd.id, remote.host()?.id)

        val explicit = manager(listOf(alpha.copy(enabled = false)))
        assertEquals("alpha-id", explicit.host("alpha")?.id)
    }

    @Test
    fun `含糊主机名给出可选列表`() = runBlocking {
        val remote = manager(listOf(alpha, alphaProd))
        try {
            remote.shell("alpha-")
            fail("应该拒绝含糊匹配")
        } catch (e: RemoteConnectionException) {
            assertTrue(e.message!!.contains("找不到唯一的远程主机"))
            assertTrue(e.message!!.contains("alpha-id"))
            assertTrue(e.message!!.contains("alpha-prod-id"))
        }
    }

    @Test
    fun `禁用主机和缺私钥在连接前明确报错`() = runBlocking {
        val disabled = manager(listOf(alpha.copy(enabled = false)))
        try {
            disabled.shell("alpha-id")
            fail("禁用主机不应建立连接")
        } catch (e: RemoteConnectionException) {
            assertTrue(e.message!!.contains("已禁用"))
        }

        val noKey = manager(listOf(alpha))
        try {
            noKey.shell("alpha-id")
            fail("没有私钥不应建立连接")
        } catch (e: RemoteConnectionException) {
            assertTrue(e.message!!.contains("还没有导入私钥"))
        }
    }
    @Test
    fun `a host never borrows another host identity`() = runBlocking {
        val store = RemoteConfigStore(tmp.newFolder())
        store.save(listOf(alpha, alphaProd))
        val secrets = InMemorySecretStore()
        secrets.put("ssh_key_${alpha.id}", "alpha-fixture-key")
        val authenticated = mutableListOf<SshHost>()
        val remote = RemoteManager(store, secrets, connect = { host ->
            authenticated += host
            object : RemoteShell {
                override suspend fun exec(command: String, timeoutMs: Long) = RemoteResult(0)
                override suspend fun close() {}
            }
        })
        assertTrue(runCatching { remote.shell(alphaProd.id) }.isFailure)
        assertTrue(authenticated.isEmpty())
        remote.shell(alpha.id)
        assertEquals("alpha-fixture-key", authenticated.single().privateKeyPem)
        secrets.put("ssh_key_${alphaProd.id}", "beta-fixture-key")
        secrets.put("ssh_passphrase_${alphaProd.id}", "beta-fixture-passphrase")
        remote.shell(alphaProd.id)
        assertEquals("beta-fixture-key", authenticated.last().privateKeyPem)
        assertEquals("beta-fixture-passphrase", authenticated.last().passphrase)
    }

    @Test
    fun `remote form validation accepts custom paths but rejects invalid ports and URLs`() {
        assertNull(alpha.copy(codexExecutable = "~/my tools/codex").configurationError())
        assertTrue(alpha.copy(port = 0).configurationError() != null)
        assertTrue(alpha.copy(port = 65536).configurationError() != null)
        assertTrue(alpha.copy(host = "https://example.com").configurationError() != null)
        assertTrue(alpha.copy(codexExecutable = "codex; command").configurationError() != null)
        assertEquals("host.invalid", alpha.copy(host = " host.invalid ").normalized().host)
    }

}
