package app.pocketagent.android.remote

import app.pocketagent.android.tools.RemoteWorkspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkspaceInstructionTest {
    private class FakeShell(private val output: String) : RemoteShell {
        var command = ""

        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult {
            this.command = command
            return RemoteResult(0, output)
        }

        override suspend fun close() {}
    }

    private class FakeFs : RemoteFs {
        override suspend fun read(path: String, maxBytes: Int, offsetBytes: Long): String = ""
        override suspend fun write(path: String, content: String) = Unit
        override suspend fun list(path: String): List<String> = emptyList()
    }

    @Test
    fun `remote workspace reads only AGENTS instruction files`() = runBlocking {
        val shell = FakeShell("remote rule\n")
        val workspace = RemoteWorkspace(
            shell = shell,
            fs = FakeFs(),
            baseDir = "/Users/me/project",
            hostLabel = "Mac",
        )

        assertEquals("remote rule", workspace.readInstruction("/Users/me/AGENTS.md")?.trim())
        assertTrue(shell.command.contains("cat --"))
        assertTrue(shell.command.contains("/Users/me/AGENTS.md"))

        shell.command = ""
        assertNull(workspace.readInstruction("/Users/me/.env"))
        assertEquals("", shell.command)
    }
}
