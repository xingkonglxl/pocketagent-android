package app.pocketagent.android

import app.pocketagent.android.tools.ExecCommandTool
import app.pocketagent.android.tools.ProcessSessionManager
import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.WriteStdinTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExecToolsTest {
    @Test
    fun `exec command returns a session id`() = runBlocking {
        val manager = ProcessSessionManager(File("."), maxSessions = 2)
        try {
            val result = ExecCommandTool(manager).execute(
                """{"command":"printf 'ok\\n'","yield_time_ms":5000}""",
            )
            assertTrue(result is ToolOutcome.Ok)
            val text = (result as ToolOutcome.Ok).text
            assertTrue(text.contains("session_id=proc_"))
            assertTrue(text.contains("ok"))
            val sessionId = text.lineSequence()
                .first { it.startsWith("session_id=") }
                .substringAfter("session_id=")
            val polled = WriteStdinTool(manager).execute(
                """{"session_id":"$sessionId","yield_time_ms":2000}""",
            )
            assertTrue(polled is ToolOutcome.Ok)
            assertTrue((polled as ToolOutcome.Ok).text.contains("status=exited"))
        } finally {
            manager.closeAll()
        }
    }

    @Test
    fun `write stdin rejects an unknown session`() = runBlocking {
        val manager = ProcessSessionManager(File("."), maxSessions = 2)
        try {
            val result = WriteStdinTool(manager).execute("""{"session_id":"missing"}""")
            assertTrue(result is ToolOutcome.Error)
        } finally {
            manager.closeAll()
        }
    }
}
