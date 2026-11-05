package app.pocketagent.android.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `增删改查`() {
        val dir = tmp.newFolder()
        val store = RemoteConfigStore(dir)
        assertTrue(store.load().isEmpty())

        val h200 = SshHost(
            id = "remote-host",
            name = "remote-host",
            host = "192.0.2.10",
            user = "dev",
            workDir = "~/Projects",
        )
        val mac = SshHost(
            id = "mac",
            name = "Mac",
            host = "192.168.1.10",
            port = 2222,
            user = "alice",
        )

        store.upsert(h200)
        store.upsert(mac)
        assertEquals(listOf(h200, mac), store.load())
        assertEquals(mac, store.get("mac"))

        // 改：同 id 覆盖，不新增
        store.upsert(h200.copy(workDir = "/data/kpfs"))
        val afterUpdate = store.load()
        assertEquals(2, afterUpdate.size)
        assertEquals("/data/kpfs", afterUpdate.first { it.id == "remote-host" }.workDir)

        // 缺省值要能往返
        val loadedMac = store.get("mac")!!
        assertEquals(2222, loadedMac.port)
        assertEquals("~", loadedMac.workDir)
        assertEquals(22, loadedMac.jumpPort)
        assertTrue(loadedMac.enabled)

        // 删
        store.delete("remote-host")
        assertEquals(listOf(mac), store.load())
        assertNull(store.get("remote-host"))
    }

    @Test
    fun `跳板机字段能往返`() {
        val store = RemoteConfigStore(tmp.newFolder())
        val host = SshHost(
            name = "remote-host",
            host = "192.0.2.30",
            user = "dev",
            jumpHost = "198.51.100.20",
            jumpUser = "team",
            jumpPort = 2222,
        )
        store.upsert(host)

        val loaded = store.load().single()
        assertEquals("remote-host", loaded.name)
        assertEquals("198.51.100.20", loaded.jumpHost)
        assertEquals("team", loaded.jumpUser)
        assertEquals(2222, loaded.jumpPort)
        assertEquals(22, loaded.port)
        assertTrue(loaded.viaJump)

        store.upsert(host.copy(name = "remote-host renamed"))
        assertEquals("remote-host renamed", store.load().single().name)

        store.delete(host.id)
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `私钥和口令不落盘`() {
        val dir = tmp.newFolder()
        val store = RemoteConfigStore(dir)
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----"

        store.upsert(
            SshHost(id = "remote-host", name = "remote-host", host = "192.0.2.10", user = "dev")
                .withKey(pem, "correct horse"),
        )

        val json = store.path.readText()
        assertFalse("私钥不能写进 JSON", json.contains("PRIVATE KEY"))
        assertFalse("base64 后的私钥也不能出现", json.contains("b3BlbnNzaC1rZXktdjEAAAAA"))
        assertFalse("口令不能写进 JSON", json.contains("correct horse"))

        val loaded = store.load().single()
        assertEquals("192.0.2.10", loaded.host)
        assertFalse(loaded.hasKey)
        assertNull(loaded.passphrase)

        // 内存里注入的 key 不受影响，可以继续用
        assertTrue(loaded.withKey(pem, "correct horse").hasKey)
    }

    @Test
    fun `坏 JSON 当空列表`() {
        val dir = tmp.newFolder()
        RemoteConfigStore(dir).path.writeText("{not json")
        assertTrue(RemoteConfigStore(dir).load().isEmpty())
    }

    @Test
    fun `save 覆盖整个列表`() {
        val dir = tmp.newFolder()
        val store = RemoteConfigStore(dir)
        val a = SshHost(id = "a", host = "a.local", user = "u")
        val b = SshHost(id = "b", host = "b.local", user = "u")
        store.save(listOf(a, b))
        assertEquals(listOf(a, b), store.load())
        store.save(listOf(b))
        assertEquals(listOf(b), store.load())
    }

    @Test
    fun `shell quoting survives single quotes`() {
        assertEquals("'a'\\''b'", shellQuote("a'b"))
        assertEquals("'/tmp/x y'", shellQuote("/tmp/x y"))
    }

    @Test
    fun `remote result summary includes exit code and streams`() {
        val ok = RemoteResult(0, "hello\n", "")
        assertTrue(ok.ok)
        assertTrue(ok.summary().contains("exit=0"))
        assertTrue(ok.summary().contains("hello"))

        val bad = RemoteResult(2, "", "boom")
        assertFalse(bad.ok)
        assertTrue(bad.summary().contains("stderr: boom"))
    }

    @Test
    fun `remote result summary keeps head and tail instead of silently clipping`() {
        val stdout = "BEGIN\n" + "x".repeat(10_000) + "\nLAST ERROR"
        val summary = RemoteResult(1, stdout, "").summary(limit = 200)
        assertTrue(summary.contains("BEGIN"))
        assertTrue(summary.contains("LAST ERROR"))
        assertTrue(summary.contains("已截断"))
        assertTrue(summary.contains("省略"))
    }
}
