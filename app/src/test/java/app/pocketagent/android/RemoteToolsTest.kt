package app.pocketagent.android

import app.pocketagent.android.remote.RemoteOperations
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.RemoteShell
import app.pocketagent.android.remote.SshHost
import app.pocketagent.android.tools.RemoteExecTool
import app.pocketagent.android.tools.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteToolsTest {

    private class FakeOperations(
        private val result: RemoteResult = RemoteResult(0),
        private val shellOrNull: RemoteShell? = null,
    ) : RemoteOperations {
        var command: String? = null
        var timeoutMs: Long? = null
        var hostId: String? = null

        override fun hosts(): List<SshHost> = emptyList()
        override fun host(id: String?): SshHost? = null
        override suspend fun shell(id: String?): RemoteShell =
            shellOrNull ?: error("这个测试不应建连接")
        override suspend fun exec(command: String, timeoutMs: Long, hostId: String?): RemoteResult {
            this.command = command
            this.timeoutMs = timeoutMs
            this.hostId = hostId
            return result
        }
    }

    private class RecordingShell : RemoteShell {
        val commands = mutableListOf<String>()
        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult {
            commands += command
            return RemoteResult(0)
        }

        override suspend fun close() = Unit
    }

    @Test
    fun `ssh exec 截断时把完整输出存到远端临时文件`() = runBlocking {
        val stdout = "HEAD\n" + "x".repeat(31_000) + "\nTAIL"
        val shell = RecordingShell()
        val outcome = RemoteExecTool(FakeOperations(RemoteResult(1, stdout, ""), shell))
            .execute("""{"command":"make"}""") as ToolOutcome.Error

        val path = Regex("`(/tmp/pocketagent-exec-[^`]+)`").find(outcome.message)?.groupValues?.get(1)
        assertTrue("结果里应给出远端日志路径：${outcome.message.take(400)}", path != null)
        val write = shell.commands.firstOrNull { it.contains("base64 -d") }
        assertTrue("应向远端写日志文件", write != null)
        assertTrue(write!!.contains(path!!))

        val payload = Regex("printf %s '([^']+)'").find(write)?.groupValues?.get(1)
        assertTrue("日志内容应通过 base64 传输", payload != null)
        val decoded = String(java.util.Base64.getDecoder().decode(payload!!), Charsets.UTF_8)
        assertEquals(stdout, decoded)
    }

    @Test
    fun `ssh exec 截断时保留尾部报错并提示如何取全文`() = runBlocking {
        val stdout = "HEAD\n" + "x".repeat(31_000) + "\nBUILD FAILED: exact compiler error"
        val outcome = RemoteExecTool(FakeOperations(RemoteResult(1, stdout, "")))
            .execute("""{"command":"make"}""") as ToolOutcome.Error

        assertTrue(outcome.message.contains("exit=1"))
        assertTrue(outcome.message.contains("BUILD FAILED: exact compiler error"))
        assertTrue(outcome.message.contains("stdout 已截断"))
        assertTrue(outcome.message.contains("ssh_read"))
    }

    @Test
    fun `ssh exec 超时结果说明远端进程可能仍在跑`() = runBlocking {
        val outcome = RemoteExecTool(FakeOperations(RemoteResult.timeout(5_000, "partial")))
            .execute("""{"command":"sleep 60","timeout_ms":5000,"host":"dev"}""") as ToolOutcome.Error

        assertTrue(outcome.message.contains("超时"))
        assertTrue(outcome.message.contains("timeout_ms=5000"))
        assertTrue(outcome.message.contains("nohup"))
        assertTrue(outcome.message.contains("partial"))
    }

    @Test
    fun `ssh exec 严格校验 timeout 和必填参数`() = runBlocking {
        val ops = FakeOperations()
        val tool = RemoteExecTool(ops)

        val tooShort = tool.execute("""{"command":"pwd","timeout_ms":999}""") as ToolOutcome.Error
        assertTrue(tooShort.message.contains("timeout_ms 必须在"))
        val blank = tool.execute("""{"command":"  "}""") as ToolOutcome.Error
        assertTrue(blank.message.contains("不能为空"))
        val malformed = tool.execute("""{"command":""") as ToolOutcome.Error
        assertTrue(malformed.message.contains("不是合法 JSON"))
        assertNull(ops.command)
    }

    @Test
    fun `ssh exec 透传合法参数与默认 timeout`() = runBlocking {
        val ops = FakeOperations(RemoteResult(0, "ok"))
        val outcome = RemoteExecTool(ops).execute("""{"command":"pwd","host":" dev "}""")
        assertTrue(outcome is ToolOutcome.Ok)
        assertEquals("pwd", ops.command)
        assertEquals(120_000L, ops.timeoutMs ?: -1L)
        assertEquals("dev", ops.hostId)
    }
}
