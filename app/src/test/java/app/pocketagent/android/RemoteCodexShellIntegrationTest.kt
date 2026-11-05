package app.pocketagent.android

import app.pocketagent.android.agent.TurnSteering
import app.pocketagent.android.remote.RemoteDuplexShell
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.tools.RemoteCodexSession
import app.pocketagent.android.tools.remoteCodexSessionCommand
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Run the production FIFO/filter wrapper, rather than a fake that ignores shell syntax. */
class RemoteCodexShellIntegrationTest {
    @Test
    fun `production wrapper preserves RPC replies filters only notifications and cleans private FIFO`() {
        for (includeReasoning in listOf(false, true)) {
            val directory = Files.createTempDirectory("pocketagent shell '").toFile()
            try {
                val executable = File(directory, "codex stub")
                executable.writeText("""
                    #!/bin/sh
                    [ "${'$'}1" = app-server ] || exit 64
                    IFS= read -r request || exit 65
                    case "${'$'}request" in *initialize*) ;; *) exit 66 ;; esac
                    printf '%s\n' '{"id":1,"result":{"type":"commandExecution","keep":"rpc reply"}}'
                    printf '%s\n' '{"method":"item/completed","params":{"item":{"type":"commandExecution","output":"discard command output"}}}'
                    printf '%s\n' '{"method":"item/completed","params":{"item":{"type":"reasoning","summary":["reasoning summary"]}}}'
                    printf '%s\n' '{"method":"item/completed","params":{"item":{"type":"agentMessage","text":"keep assistant"}}}'
                """.trimIndent() + "\n")
                assertTrue(executable.setExecutable(true))
                val command = remoteCodexSessionCommand(directory.path, includeReasoning, executable.path)
                val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                    environment()["TMPDIR"] = directory.path
                    environment()["CODEX_HOME"] = directory.path
                }.start()
                process.outputStream.use { it.write("{\"id\":1,\"method\":\"initialize\"}\n".toByteArray()) }
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    fail("Production shell wrapper did not finish")
                }
                val output = process.inputStream.bufferedReader().readText()
                val error = process.errorStream.bufferedReader().readText()
                assertEquals(error, 0, process.exitValue())
                assertTrue(output.contains("rpc reply"))
                assertTrue(output.contains("keep assistant"))
                assertFalse(output.contains("discard command output"))
                assertEquals(includeReasoning, output.contains("reasoning summary"))
                assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("pocketagent-rpc.") })
            } finally { directory.deleteRecursively() }
        }
        // Optional integration artifact for an SSH protocol smoke using the identical production code.
        System.getenv("POCKETAGENT_PROTOCOL_EXPORT")?.let {
            File(it).writeText(remoteCodexSessionCommand("/tmp", false, "") + "\n")
        }
    }

    @Test
    fun `initialization disconnect reports exit stderr and redacts credentials`() = runBlocking {
        val shell = object : RemoteDuplexShell {
            override suspend fun exec(command: String, timeoutMs: Long) = RemoteResult(0)
            override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long) = RemoteResult(0)
            override suspend fun execStreaming(command: String, input: ByteArray, timeoutMs: Long, onStdoutLine: (String) -> Unit) = RemoteResult(0)
            override suspend fun execDuplex(command: String, timeoutMs: Long, input: ReceiveChannel<ByteArray>, onStdoutLine: (String) -> Unit) =
                RemoteResult(126, stderr = "launcher failed: invalid launch configuration; Authorization: Bearer sk-exampleSecret123456789 api_key=another-secret")
            override suspend fun close() = Unit
        }
        val result = RemoteCodexSession(
            shell, "/tmp", "never sent", null, null, null, false, emptyList(), 5_000, "", TurnSteering(), {}, {}, { false },
        ).run()
        assertFalse(result.ok)
        val detail = result.errors.joinToString("\n")
        assertTrue(detail, detail.contains("exit=126"))
        assertTrue(detail, detail.contains("invalid launch configuration"))
        assertTrue(detail, detail.contains("[REDACTED]"))
        assertFalse(detail, detail.contains("exampleSecret"))
        assertFalse(detail, detail.contains("another-secret"))
    }
}
