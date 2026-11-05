package app.pocketagent.android.remote

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** 显式提供测试主机和凭证路径时运行；只操作远端新建的临时目录。 */
class SshSyncIntegrationTest {
    @Test fun `SSHj uploads and downloads large session without truncation`() = runBlocking {
        val address = System.getenv("POCKETAGENT_TEST_SSH_HOST")
        val keyFile = System.getenv("POCKETAGENT_TEST_SSH_KEY")
        val knownHosts = System.getenv("POCKETAGENT_TEST_SSH_KNOWN_HOSTS")
        assumeNotNull(address, keyFile, knownHosts)
        val host = SshHost(
            host = address!!,
            user = System.getenv("POCKETAGENT_TEST_SSH_USER") ?: System.getProperty("user.name"),
            port = System.getenv("POCKETAGENT_TEST_SSH_PORT")?.toInt() ?: 22,
            privateKeyPem = File(keyFile!!).readText(),
        )
        val shell = SshRemoteShell(host, hostKeyVerifier = OpenSSHKnownHosts(File(knownHosts!!)))
        var directory: String? = null
        try {
            directory = shell.exec("mktemp -d /tmp/pocketagent-sync-test.XXXXXX", 15_000)
                .requireOk("新建测试目录").stdout.trim()
            check(directory.startsWith("/tmp/pocketagent-sync-test."))
            val path = "$directory/session.json"
            val content = "中文😀 text 'quoted' \$literal\n".repeat(70_000)
            ShellRemoteFs(shell).writeAtomic(path, content)
            val read = shell.exec("cat -- ${shellPath(path)}", 60_000).requireOk("回读测试文件")
            assertArrayEquals(content.toByteArray(), read.stdout.toByteArray())
            assertTrue(shell.exec("test \"\$(ls -A ${shellPath(directory)} | wc -l | tr -d ' ')\" = 1", 15_000).ok)
        } finally {
            directory?.takeIf { it.startsWith("/tmp/pocketagent-sync-test.") }?.let {
                runCatching { shell.exec("rm -rf -- ${shellPath(it)}", 15_000) }
            }
            shell.close()
        }
    }
}
