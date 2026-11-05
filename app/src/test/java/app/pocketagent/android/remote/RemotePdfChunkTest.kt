package app.pocketagent.android.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RemotePdfChunkTest {

    private class FakeShell(vararg replies: RemoteResult) : RemoteShell {
        val commands = mutableListOf<String>()
        private val queue = ArrayDeque(replies.toList())

        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult {
            commands += command
            return queue.removeFirstOrNull() ?: error("没有预置返回：$command")
        }

        override suspend fun close() = Unit
    }

    @Test
    fun `remote binary is fetched in bounded chunks`() = runBlocking {
        val shell = FakeShell(
            RemoteResult(0, "7\n"),
            RemoteResult(0, Base64.getEncoder().encodeToString("abcd".toByteArray())),
            RemoteResult(0, Base64.getEncoder().encodeToString("efg".toByteArray())),
        )

        val bytes = app.pocketagent.android.tools.readRemoteBytesChunked(
            shell = shell,
            path = "~/docs/report.pdf",
            maxBytes = 100,
            chunkBytes = 4,
        )

        assertEquals("abcdefg", bytes.toString(Charsets.UTF_8))
        assertEquals(3, shell.commands.size)
        assertTrue(shell.commands[1].contains("tail -c +1"))
        assertTrue(shell.commands[1].contains("head -c 4"))
        assertTrue(shell.commands[2].contains("tail -c +5"))
        assertTrue(shell.commands[2].contains("head -c 3"))
    }

    @Test
    fun `remote size over the limit fails before downloading`() = runBlocking {
        val shell = FakeShell(RemoteResult(0, "999\n"))

        val error = runCatching {
            app.pocketagent.android.tools.readRemoteBytesChunked(
                shell = shell,
                path = "big.pdf",
                maxBytes = 10,
                chunkBytes = 4,
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("远端文件太大"))
        assertEquals(1, shell.commands.size)
    }
}
