package app.pocketagent.android

import app.pocketagent.android.data.ConversationStore
import app.pocketagent.android.data.SyncBase
import app.pocketagent.android.data.SyncConflict
import app.pocketagent.android.data.SyncState
import app.pocketagent.android.data.SyncStore
import app.pocketagent.android.sync.PhoneContext
import app.pocketagent.android.sync.RemoteContext
import app.pocketagent.android.sync.SyncBackend
import app.pocketagent.android.sync.SyncService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncTrashTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Backend : SyncBackend {
        override val label = "test host"
        val restored = mutableListOf<String>()
        val purged = mutableListOf<String>()
        var missing = emptySet<String>()
        var failing = emptySet<String>()
        var cancelled = false
        var remote = emptyList<RemoteContext>()
        override suspend fun dump(hostId: String?) = remote
        override suspend fun fetch(hostId: String?, id: String): String = error("must not fetch tombstoned history")
        override suspend fun push(hostId: String?, ctx: PhoneContext): String = error("must not push tombstoned history")
        override suspend fun deleteRemote(hostId: String?, id: String) {}
        override suspend fun restore(hostId: String?, id: String): Boolean {
            restored += id
            if (id in failing) error("synthetic restore failure")
            return id !in missing
        }
        override suspend fun purgeTrash(hostId: String?, id: String) {
            purged += id
            if (cancelled) throw CancellationException("synthetic cancellation")
            if (id in failing) error("synthetic deletion failure")
        }
    }

    @Test fun `only successful selected restorations clear deletion records`() = runBlocking {
        val dir = tmp.newFolder()
        val global = SyncStore(dir)
        val target = SyncStore(dir, scope = "target")
        val unrelated = SyncStore(dir, scope = "other")
        val ids = listOf("restored", "missing", "failed", "unselected")
        global.markDeleted(ids)
        target.markDeleted(ids)
        unrelated.markDeleted(listOf("unrelated"))
        val backend = Backend().apply { missing = setOf("missing"); failing = setOf("failed") }
        val service = SyncService(global, ConversationStore(dir), backend) { target }
        val result = service.restoreDeleted(listOf("restored", "missing", "failed", "restored"), "target")
        assertEquals(setOf("restored"), result.completed)
        assertEquals(setOf("missing"), result.missing)
        assertEquals(setOf("failed"), result.errors.keys)
        assertEquals(listOf("restored", "missing", "failed"), backend.restored)
        val remaining = setOf("missing", "failed", "unselected")
        assertEquals(remaining, global.load().deleted.keys)
        assertEquals(remaining, SyncStore(dir, scope = "target").load().deleted.keys)
        assertEquals(setOf("unrelated"), unrelated.load().deleted.keys)
    }

    @Test fun `permanent deletion retains tombstones across restart and subsequent sync`() = runBlocking {
        val dir = tmp.newFolder()
        val global = SyncStore(dir)
        val target = SyncStore(dir, scope = "target")
        val backend = Backend()
        val service = SyncService(global, ConversationStore(dir), backend) { target }
        target.markDeleted(listOf("selected", "unselected"))
        global.markDeleted(listOf("selected", "unselected"))
        val result = service.permanentlyDelete(listOf("selected", "selected"), "target")
        assertEquals(setOf("selected"), result.completed)
        assertEquals(listOf("selected"), backend.purged)
        assertEquals(setOf("selected", "unselected"), global.load().deleted.keys)
        backend.remote = listOf(RemoteContext(id = "selected", title = "synthetic", rev = "old"))
        val restarted = SyncService(SyncStore(dir), ConversationStore(dir), backend) { SyncStore(dir, scope = "target") }
        assertEquals(0, restarted.sync("target").pulled)
        assertEquals(0, restarted.sync("other").pulled)
        assertTrue(ConversationStore(dir).load().isEmpty())
    }

    @Test fun `partial purge failure remains retryable without clearing other tombstones`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SyncStore(dir)
        val backend = Backend().apply { failing = setOf("failed") }
        val service = SyncService(store, ConversationStore(dir), backend)
        store.markDeleted(listOf("unselected"))
        val result = service.permanentlyDelete(listOf("ok", "failed"))
        assertEquals(setOf("ok"), result.completed)
        assertEquals(setOf("failed"), result.errors.keys)
        assertEquals(setOf("ok", "failed", "unselected"), store.load().deleted.keys)
        backend.failing = emptySet()
        assertEquals(setOf("failed"), service.permanentlyDelete(listOf("failed")).completed)
        assertEquals(listOf("ok", "failed", "failed"), backend.purged)
    }

    @Test fun `purge drops only selected conflict and base and keeps deletion record`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SyncStore(dir)
        store.save(SyncState(
            base = mapOf("selected" to SyncBase("a", "b"), "other" to SyncBase("c", "d")),
            conflicts = listOf("selected", "other").map { SyncConflict(it, "synthetic", "a", "b") },
        ))
        SyncService(store, ConversationStore(dir), Backend()).permanentlyDelete(listOf("selected"))
        assertEquals(setOf("other"), store.load().base.keys)
        assertEquals(listOf("other"), store.load().conflicts.map { it.id })
        assertEquals(setOf("selected"), store.load().deleted.keys)
    }

    @Test fun `cancelled purge propagates cancellation while retaining all tombstones`() = runBlocking {
        val dir = tmp.newFolder()
        val store = SyncStore(dir)
        val backend = Backend().apply { cancelled = true }
        try {
            SyncService(store, ConversationStore(dir), backend).permanentlyDelete(listOf("first", "second"))
            fail("cancellation must propagate")
        } catch (_: CancellationException) {}
        assertEquals(setOf("first", "second"), store.load().deleted.keys)
        assertEquals(listOf("first"), backend.purged)
    }
}
