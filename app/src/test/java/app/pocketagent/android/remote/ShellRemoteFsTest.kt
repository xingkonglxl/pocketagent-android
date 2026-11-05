package app.pocketagent.android.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** 不连网络：用一个假 shell 检查 RemoteFs 到底发了什么命令、怎么解析结果。 */
class ShellRemoteFsTest {

    private class FakeShell(vararg replies: RemoteResult) : RemoteShell {
        val commands = mutableListOf<String>()
        val timeouts = mutableListOf<Long>()
        private val queue = ArrayDeque(replies.toList())

        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult {
            commands += command
            timeouts += timeoutMs
            return queue.removeFirstOrNull() ?: error("没有预置返回：$command")
        }

        override suspend fun close() = Unit
    }

    @Test
    fun `read 命中 head 命令并返回原文`() = runBlocking {
        val shell = FakeShell(RemoteResult(0, "hello\n"))
        val fs = ShellRemoteFs(shell)
        assertEquals("hello\n", fs.read("~/a.txt", maxBytes = 100))
        assertEquals("head -c 101 -- \"\$HOME\"/'a.txt'", shell.commands.single())
    }

    @Test
    fun `read 超长截断并提示`() = runBlocking {
        val shell = FakeShell(RemoteResult(0, "0123456789"))
        val fs = ShellRemoteFs(shell)
        val text = fs.read("big.txt", maxBytes = 4)
        assertTrue(text.startsWith("0123"))
        assertTrue(text.contains("已截断"))
        assertTrue(text.contains("next_offset=4"))
    }

    @Test
    fun `read 从 offset 续读并给出下一位置`() = runBlocking {
        val shell = FakeShell(RemoteResult(0, "45678"))
        val fs = ShellRemoteFs(shell)
        val text = fs.read("build.log", maxBytes = 4, offsetBytes = 4)
        assertTrue(text.startsWith("4567"))
        assertTrue(text.contains("offset=4..8"))
        assertTrue(text.contains("next_offset=8"))
        assertTrue(shell.commands.single().contains("tail -c +5"))
    }

    @Test
    fun `read 到 EOF 后返回可理解提示`() = runBlocking {
        val shell = FakeShell(RemoteResult(0, ""))
        val fs = ShellRemoteFs(shell)
        val text = fs.read("build.log", maxBytes = 100, offsetBytes = 900)
        assertTrue(text.contains("offset=900"))
        assertTrue(text.contains("文件末尾"))
    }

    @Test
    fun `write 用 base64 传输内容`() = runBlocking {
        val shell = FakeShell(RemoteResult(0))
        val fs = ShellRemoteFs(shell)
        fs.write("/tmp/x y.txt", "中文 'quoted'\n")
        val cmd = shell.commands.single()
        val b64 = Base64.getEncoder().encodeToString("中文 'quoted'\n".toByteArray())
        assertTrue(cmd.contains("/tmp/x y.txt"))
        assertTrue(cmd.contains("base64 -d"))
        assertTrue(cmd.contains(b64))
        assertTrue("要自动建父目录", cmd.startsWith("mkdir -p -- "))
    }

    @Test
    fun `list 按行切分`() = runBlocking {
        val shell = FakeShell(RemoteResult(0, "a.txt\n.hidden\nsub\n"))
        val fs = ShellRemoteFs(shell)
        assertEquals(listOf("a.txt", ".hidden", "sub"), fs.list("."))
        assertEquals("ls -1A -- '.'", shell.commands.single())
    }

    @Test
    fun `非零退出码抛 RemoteCommandException`() = runBlocking {
        val shell = FakeShell(
            RemoteResult(1, "", "cat: nope: No such file or directory"),
            RemoteResult(RemoteResult.TIMEOUT_EXIT_CODE, "", "命令超时（60000 ms）", timedOut = true),
        )
        val fs = ShellRemoteFs(shell)

        try {
            fs.read("nope")
            fail("应该抛异常")
        } catch (e: RemoteCommandException) {
            assertEquals(1, e.result.exitCode)
            assertTrue(e.message!!.contains("No such file"))
        }

        try {
            fs.list(".")
            fail("超时也要抛")
        } catch (e: RemoteCommandException) {
            assertTrue(e.result.timedOut)
        }
    }

    @Test
    fun `timeout 会传给 exec`() = runBlocking {
        val shell = FakeShell(RemoteResult(0, "ok"))
        val fs = ShellRemoteFs(shell, timeoutMs = 1_234)
        fs.list(".")
        assertTrue(shell.commands.single().isNotEmpty())
        assertEquals(1_234L, shell.timeouts.single())
    }
}
