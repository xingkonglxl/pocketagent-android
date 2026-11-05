package app.pocketagent.android.remote

import app.pocketagent.android.data.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.*
import org.junit.Test

class MacTrashBatchTest {
    private class Shell : RemoteInputShell {
        val requests = mutableListOf<List<String>>()
        var fail = emptySet<String>()
        var malformed = false
        var cancel = false
        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult = error("must use stdin")
        override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long): RemoteResult {
            assertEquals("~/.local/bin/pocketagent-export-context purgebatch --stdin", command)
            val ids = AppJson.decodeFromString(ListSerializer(String.serializer()), input.toString(Charsets.UTF_8))
            requests += ids
            if (cancel) throw CancellationException("synthetic cancellation")
            if (malformed) return RemoteResult(0, """{"protocol":"pocketagent-trash-batch-v1","results":[]}""")
            val rows = ids.joinToString(",") { id ->
                """{"id":"$id","deletedFiles":1${if (id in fail) ",\"error\":\"synthetic failure\"" else ""}}"""
            }
            return RemoteResult(0, """{"protocol":"pocketagent-trash-batch-v1","results":[$rows]}""")
        }
        override suspend fun close() {}
    }

    private fun backend(shell: Shell) = MacSyncBackend(object : RemoteOperations {
        override fun hosts() = emptyList<SshHost>()
        override fun host(id: String?): SshHost? = null
        override suspend fun shell(id: String?) = shell
        override suspend fun exec(command: String, timeoutMs: Long, hostId: String?): RemoteResult = error("must use stdin")
    })

    @Test fun `hundreds of selections use one SSH request and retain per group errors`() = runBlocking {
        val shell = Shell().apply { fail = setOf("id-2") }
        val ids = (1..381).map { "id-$it" }
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = backend(shell).purgeTrashBatch("target", ids + ids.first()) { done, total -> progress += done to total }
        assertEquals(1, shell.requests.size)
        assertEquals(ids, shell.requests.single())
        assertEquals(ids.toSet() - "id-2", result.completed)
        assertEquals(setOf("id-2"), result.errors.keys)
        assertEquals(listOf(381 to 381), progress)
    }

    @Test fun `large selection is bounded and reports progress per batch`() = runBlocking {
        val shell = Shell()
        val ids = (1..513).map { "id-$it" }
        val progress = mutableListOf<Pair<Int, Int>>()
        assertEquals(ids.toSet(), backend(shell).purgeTrashBatch("target", ids) { d, t -> progress += d to t }.completed)
        assertEquals(listOf(512, 1), shell.requests.map { it.size })
        assertEquals(listOf(512 to 513, 513 to 513), progress)
    }

    @Test fun `incomplete receipt cannot report success`() = runBlocking {
        val shell = Shell().apply { malformed = true }
        val result = backend(shell).purgeTrashBatch("target", listOf("one", "two")) { _, _ -> }
        assertTrue(result.completed.isEmpty())
        assertEquals(setOf("one", "two"), result.errors.keys)
        assertEquals(1, shell.requests.size)
    }

    @Test fun `batch cancellation is propagated`() = runBlocking {
        val shell = Shell().apply { cancel = true }
        try {
            backend(shell).purgeTrashBatch("target", listOf("one")) { _, _ -> }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {}
    }
}
