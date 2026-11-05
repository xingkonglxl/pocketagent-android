package app.pocketagent.android

import app.pocketagent.android.tools.ProcessSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ProcessSessionManagerTest {
    private fun manager() = ProcessSessionManager(File("."), maxSessions = 3)

    @Test
    fun `start captures output and exit code`() {
        val manager = manager()
        try {
            val started = manager.start("printf 'hello\\n'", 5_000, 4_000)
            assertTrue(started.sessionId.startsWith("proc_"))
            assertEquals("hello\n", started.output)
            val snapshot = if (started.running) {
                manager.write(started.sessionId, "", 2_000, 4_000)
            } else {
                started
            }
            assertFalse(snapshot.running)
            assertEquals(0, snapshot.exitCode)
        } finally {
            manager.closeAll()
        }
    }

    @Test
    fun `write sends stdin and polls incremental output`() {
        val manager = manager()
        try {
            val snapshot = manager.start("cat", 200, 4_000)
            assertTrue(snapshot.running)
            val written = manager.write(snapshot.sessionId, "ping\n", 2_000, 4_000)
            assertTrue(written.output.contains("ping"))
            manager.kill(snapshot.sessionId)
        } finally {
            manager.closeAll()
        }
    }

    @Test
    fun `unknown session is rejected`() {
        val manager = manager()
        try {
            try {
                manager.write("missing", "", 10, 100)
                fail("missing session should fail")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        } finally {
            manager.closeAll()
        }
    }

    @Test
    fun `output is truncated but can be polled`() {
        val manager = manager()
        try {
            val snapshot = manager.start("printf 'abcdef'", 5_000, 3)
            assertEquals("abc", snapshot.output)
            assertTrue(snapshot.truncated)
            val rest = manager.write(snapshot.sessionId, "", 1_000, 10)
            assertEquals("def", rest.output)
        } finally {
            manager.closeAll()
        }
    }
}
