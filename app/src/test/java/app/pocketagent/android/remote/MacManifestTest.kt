package app.pocketagent.android.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class MacManifestTest {
    private class Ops(var result: RemoteResult) : RemoteOperations {
        val commands = mutableListOf<String>()
        override fun hosts(): List<SshHost> = emptyList()
        override fun host(id: String?): SshHost? = null
        override suspend fun shell(id: String?): RemoteShell = error("unexpected shell")
        override suspend fun exec(command: String, timeoutMs: Long, hostId: String?): RemoteResult {
            commands += command
            return result
        }
    }

    private fun contextLine(id: String) =
        "$id\tsynthetic title\t2026-01-01T00:00:00Z\t1\t2\trev-$id"

    @Test
    fun `unchanged manifest is one token line and carries no conversations`() = runBlocking {
        val ops = Ops(RemoteResult(0, "pocketagent-manifest-v1\tcached-token\tunchanged\n"))
        val manifest = MacSyncBackend(ops).manifest("target", "cached-token")
        assertTrue(manifest.unchanged)
        assertEquals("cached-token", manifest.token)
        assertTrue(manifest.contexts.isEmpty())
        assertEquals(
            "~/.local/bin/pocketagent-export-context manifest 'cached-token'",
            ops.commands.single(),
        )
    }

    @Test
    fun `changed manifest parses only metadata and preserves the token`() = runBlocking {
        val ops = Ops(RemoteResult(
            0,
            "pocketagent-manifest-v1\tnew-token\tchanged\n" + contextLine("one") + "\n",
        ))
        val manifest = MacSyncBackend(ops).manifest("target", "old-token")
        assertEquals("new-token", manifest.token)
        assertEquals(false, manifest.unchanged)
        assertEquals("one", manifest.contexts.single().id)
        assertEquals("rev-one", manifest.contexts.single().rev)
    }

    @Test
    fun `single item manifest uses one dumpids request`() = runBlocking {
        val ops = Ops(RemoteResult(0, contextLine("one") + "\n" + contextLine("other") + "\n"))
        val contexts = MacSyncBackend(ops).dumpItems("target", setOf("one"))
        assertEquals(listOf("one"), contexts.map { it.id })
        assertTrue(ops.commands.single().contains(" dumpids 'one'"))
    }

    @Test
    fun `unknown manifest protocol fails closed`() = runBlocking {
        val ops = Ops(RemoteResult(0, "legacy-dump-without-header\n"))
        val failure = runCatching { MacSyncBackend(ops).manifest("target", "token") }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("协议不兼容"))
    }

    @Test
    fun `bundled sync helper is installed once with an executable private file`() = runBlocking {
        val script = "#!/usr/bin/env python3\nprint('synthetic helper')\n"
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(script.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val uploaded = mutableListOf<String>()
        val commands = mutableListOf<String>()
        var installed = false
        val shell = object : RemoteInputShell {
            override suspend fun exec(command: String, timeoutMs: Long): RemoteResult {
                commands += command
                if (command.startsWith("chmod 700")) installed = true
                return RemoteResult(0)
            }
            override suspend fun execWithInput(
                command: String, input: ByteArray, timeoutMs: Long,
            ): RemoteResult {
                commands += command
                uploaded += if (command.contains("gzip -dc")) {
                    GZIPInputStream(ByteArrayInputStream(input)).bufferedReader().readText()
                } else {
                    input.toString(Charsets.UTF_8)
                }
                return RemoteResult(0)
            }
            override suspend fun close() {}
        }
        val ops = object : RemoteOperations {
            override fun hosts(): List<SshHost> = emptyList()
            override fun host(id: String?): SshHost? = null
            override suspend fun shell(id: String?): RemoteShell = shell
            override suspend fun exec(command: String, timeoutMs: Long, hostId: String?): RemoteResult {
                commands += command
                return when {
                    command.contains("sha256sum") && installed -> RemoteResult(0, "$hash  helper\n")
                    command.contains("sha256sum") -> RemoteResult(0, "")
                    command.contains("manifest") ->
                        RemoteResult(0, "pocketagent-manifest-v1\tcached\tunchanged\n")
                    else -> RemoteResult(0)
                }
            }
        }
        val backend = MacSyncBackend(ops, helperScript = script)
        assertTrue(backend.manifest("target", "cached").unchanged)
        assertEquals(listOf(script), uploaded)
        assertTrue(commands.any { it.startsWith("chmod 700") })
        val before = commands.size
        backend.dumpItems("target", setOf("one"))
        assertEquals(before + 1, commands.size)
    }
}
