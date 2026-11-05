package app.pocketagent.android.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** 真正执行生成的 shell 命令，验证原子提交、特殊字符以及中断恢复。 */
class AtomicSyncWriteTest {
    @get:Rule val tmp = TemporaryFolder()

    private class ProcessShell(val beforeExec: (String, Int) -> Unit = { _, _ -> }) : RemoteShell {
        var calls = 0
        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult {
            beforeExec(command, ++calls)
            val process = ProcessBuilder("/bin/sh", "-c", command).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            return RemoteResult(process.waitFor(), stdout, stderr)
        }
        override suspend fun close() = Unit
    }

    private class InputProcessShell(val truncate: Boolean = false) : RemoteInputShell {
        var inputBytes = 0
        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult = error("stdin expected")
        override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long): RemoteResult {
            inputBytes = input.size
            val process = ProcessBuilder("/bin/sh", "-c", command).start()
            process.outputStream.use { it.write(if (truncate) input.copyOf(input.size / 2) else input) }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            return RemoteResult(process.waitFor(), stdout, stderr)
        }
        override suspend fun close() = Unit
    }

    @Test fun `compressed stream preserves every byte with small wire payload`() = runBlocking {
        val parent = tmp.newFolder("stream ' 中文")
        val target = File(parent, "context.json")
        val content = "中文😀 'quoted' \$literal\n".repeat(60_000)
        val shell = InputProcessShell()
        ShellRemoteFs(shell).writeAtomic(target.path, content)
        assertArrayEquals(content.toByteArray(), target.readBytes())
        assertTrue(shell.inputBytes < content.toByteArray().size / 10)
        assertEquals(listOf(target.name), parent.list()!!.toList())
    }

    @Test fun `truncated compressed upload does not replace original`() = runBlocking {
        val parent = tmp.newFolder()
        val target = File(parent, "context.json")
        target.writeText("complete original")
        val result = runCatching {
            ShellRemoteFs(InputProcessShell(truncate = true)).writeAtomic(target.path, "新内容".repeat(80_000))
        }
        assertTrue(result.isFailure)
        assertEquals("complete original", target.readText())
        assertEquals(listOf(target.name), parent.list()!!.toList())
    }

    @Test fun `large multilingual document is replaced only when complete and stays private`() = runBlocking {
        val parent = tmp.newFolder("documents ' with spaces")
        val target = File(parent, "会话.json")
        target.writeText("old complete document")
        val content = "中文😀 'quoted' \$literal\n".repeat(60_000)
        val shell = ProcessShell { command, _ ->
            assertTrue(command.toByteArray().size < 128 * 1024)
            assertEquals("old complete document", target.readText())
        }
        ShellRemoteFs(shell).writeAtomic(target.path, content)
        assertArrayEquals(content.toByteArray(), target.readBytes())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(target.toPath()))
        assertEquals(listOf(target.name), parent.list()!!.toList())
    }

    @Test fun `failure halfway keeps original intact and removes temporary data`() = runBlocking {
        val parent = tmp.newFolder()
        val target = File(parent, "session.json")
        target.writeText("complete original")
        val shell = ProcessShell { _, call -> if (call == 4) throw RemoteConnectionException("simulated disconnect") }
        val result = runCatching { ShellRemoteFs(shell).writeAtomic(target.path, "新内容".repeat(80_000)) }
        assertTrue(result.isFailure)
        assertEquals("complete original", target.readText())
        assertEquals(listOf(target.name), parent.list()!!.toList())
    }
}
