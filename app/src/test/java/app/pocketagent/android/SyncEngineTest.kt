package app.pocketagent.android

import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.ConversationStore
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.SyncBase
import app.pocketagent.android.data.SyncState
import app.pocketagent.android.data.SyncStore
import app.pocketagent.android.sync.PhoneContext
import app.pocketagent.android.sync.RemoteContext
import app.pocketagent.android.sync.SyncBackend
import app.pocketagent.android.sync.SyncPlanner
import app.pocketagent.android.sync.SyncProtocol
import app.pocketagent.android.sync.SyncService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun conv(id: String, text: String, origin: String = "phone") = Conversation(
        id = id,
        title = text.take(10),
        messages = listOf(
            ChatMessage(role = Role.USER, text = text),
            ChatMessage(role = Role.ASSISTANT, text = "收到"),
        ),
        origin = origin,
    )

    private fun remote(
        c: Conversation,
        rev: String = SyncProtocol.revOf(c.messages),
        title: String = c.title,
    ) = RemoteContext(
        id = c.id,
        title = title,
        updatedAt = c.updatedAt,
        rev = rev,
        body = SyncProtocol.render(c),
    )

    // ---------------------------------------------------------------- planner

    @Test
    fun `remote only thread is pulled`() {
        val r = remote(conv("m1", "mac 上的会话"))
        val plan = SyncPlanner.plan(emptyList(), listOf(r), SyncState())
        assertEquals(listOf(r), plan.pulls)
        assertTrue(plan.pushes.isEmpty())
    }

    @Test
    fun `local only thread is pushed`() {
        val c = conv("p1", "手机上的会话")
        val plan = SyncPlanner.plan(listOf(c), emptyList(), SyncState())
        assertEquals(listOf(c), plan.pushes)
    }

    @Test
    fun `only local change pushes`() {
        val c = conv("x", "原始")
        val r = remote(c, rev = "r1")
        val state = SyncState(base = mapOf("x" to SyncBase(SyncProtocol.revOf(c.messages), "r1")))
        val edited = c.copy(messages = c.messages + ChatMessage(role = Role.USER, text = "加了点东西"))
        val plan = SyncPlanner.plan(listOf(edited), listOf(r), state)
        assertEquals(1, plan.pushes.size)
        assertTrue(plan.pulls.isEmpty())
        assertTrue(plan.conflicts.isEmpty())
    }

    @Test
    fun `only remote change pulls without touching local`() {
        val c = conv("x", "原始")
        val state = SyncState(base = mapOf("x" to SyncBase(SyncProtocol.revOf(c.messages), "r1")))
        val r = remote(c).copy(rev = "r2")
        val plan = SyncPlanner.plan(listOf(c), listOf(r), state)
        assertEquals(1, plan.pulls.size)
        assertTrue(plan.pushes.isEmpty())
    }

    @Test
    fun `both sides changed is a conflict`() {
        val c = conv("x", "原始")
        val state = SyncState(base = mapOf("x" to SyncBase(SyncProtocol.revOf(c.messages), "r1")))
        val edited = c.copy(messages = c.messages + ChatMessage(role = Role.USER, text = "本机加的"))
        val r = remote(c).copy(rev = "r2")
        val plan = SyncPlanner.plan(listOf(edited), listOf(r), state)
        assertEquals(1, plan.conflicts.size)
        assertTrue(plan.pulls.isEmpty())
        assertTrue(plan.pushes.isEmpty())
    }

    @Test
    fun `same content on first meeting is not a conflict`() {
        val c = conv("x", "原始")
        val r = remote(c, rev = "different-rev")
        val plan = SyncPlanner.plan(listOf(c), listOf(r), SyncState())
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `deleted thread is never pulled back`() {
        val c = conv("x", "删掉的")
        val r = remote(c)
        val state = SyncState(deleted = mapOf("x" to 1L))
        val plan = SyncPlanner.plan(emptyList(), listOf(r), state)
        assertTrue(plan.pulls.isEmpty())
    }

    // ---------------------------------------------------------------- service

    private class FakeBackend(var remote: List<RemoteContext> = emptyList()) : SyncBackend {
        val pushed = mutableListOf<PhoneContext>()
        val deleted = mutableListOf<String>()
        var fetches = 0
        var afterPushCalls = 0
        var afterPushIds = emptySet<String>()
        var dumpCalls = 0
        var dumpItemsCalls = 0
        var manifestCalls = 0
        var fastManifest = false
        var manifestToken = "manifest-1"
        var bulkOverride: Map<String, String>? = null
        var fetchError: Exception? = null
        var emptyFetchIds = emptySet<String>()
        var failPushId: String? = null
        var snapshotOverride: RemoteContext? = null
        override val label = "Mac"

        override suspend fun dump(hostId: String?): List<RemoteContext> {
            dumpCalls++
            return remote.map { it.copy(body = "") }
        }

        override suspend fun dumpItems(hostId: String?, ids: Set<String>): List<RemoteContext> {
            dumpItemsCalls++
            return remote.filter { it.id in ids }.map { it.copy(body = "") }
        }

        override suspend fun manifest(hostId: String?, knownToken: String): app.pocketagent.android.sync.RemoteManifest {
            manifestCalls++
            if (fastManifest && knownToken == manifestToken) {
                return app.pocketagent.android.sync.RemoteManifest(token = manifestToken, unchanged = true)
            }
            return app.pocketagent.android.sync.RemoteManifest(
                token = manifestToken,
                contexts = remote.map { it.copy(body = "") },
            )
        }

        override suspend fun fetch(hostId: String?, id: String): String {
            fetches++
            fetchError?.let { throw it }
            if (id in emptyFetchIds) return ""
            return remote.firstOrNull { it.id == id }?.body.orEmpty()
        }

        override suspend fun fetchContext(hostId: String?, id: String): RemoteContext =
            snapshotOverride?.takeIf { it.id == id } ?: super.fetchContext(hostId, id)

        override suspend fun fetchAll(hostId: String?): Map<String, String> =
            bulkOverride ?: remote.associate { it.id to it.body }

        override suspend fun push(hostId: String?, ctx: PhoneContext): String {
            if (ctx.id == failPushId) throw java.io.IOException("simulated upload failure")
            pushed += ctx
            remote = remote.filterNot { it.id == ctx.id } + RemoteContext(
                id = ctx.id,
                title = ctx.title,
                updatedAt = ctx.updatedAt,
                count = ctx.count,
                rev = ctx.rev,
                body = ctx.body,
            )
            return ctx.rev
        }

        override suspend fun deleteRemote(hostId: String?, id: String) {
            deleted += id
            remote = remote.filterNot { it.id == id }
        }

        override suspend fun afterPush(hostId: String?, ids: Set<String>) {
            afterPushCalls++
            afterPushIds += ids
        }
    }

    @Test
    fun `single item sync reads and pushes only the selected conversation`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        store.upsertAll(listOf(conv("a", "phone a"), conv("b", "phone b")))
        val backend = FakeBackend()

        val outcome = SyncService(syncStore, store, backend).syncOne(null, "a")

        assertEquals(1, outcome.pushed)
        assertEquals(listOf("a"), backend.pushed.map { it.id })
        assertEquals(1, backend.dumpItemsCalls)
        assertEquals(0, backend.dumpCalls)
        assertEquals(setOf("a"), backend.afterPushIds)
    }

    @Test
    fun `unchanged manifest skips remote list and pushes only changed local item`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val remoteA = conv("a", "remote a", origin = "mac")
        val remoteB = conv("b", "remote b", origin = "mac")
        val backend = FakeBackend(listOf(remote(remoteA), remote(remoteB)))
        val service = SyncService(syncStore, store, backend)
        assertEquals(2, service.sync().pulled)
        backend.fastManifest = true
        val changed = store.load().first { it.id == "a" }.copy(
            messages = store.load().first { it.id == "a" }.messages +
                ChatMessage(role = Role.USER, text = "phone addition"),
        )
        store.upsert(changed)
        backend.dumpCalls = 0
        backend.dumpItemsCalls = 0

        val outcome = service.sync()

        assertEquals(1, outcome.pushed)
        assertEquals(0, outcome.pulled)
        assertEquals(listOf("a"), backend.pushed.map { it.id })
        assertEquals(0, backend.dumpCalls)
        assertEquals(0, backend.dumpItemsCalls)
        assertEquals("", SyncStore(dir).load().remoteManifestToken)
    }

    @Test
    fun `unchanged manifest preserves pending conflict and never auto pushes it`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir).apply { upsert(conv("x", "phone version")) }
        val remoteVersion = conv("x", "remote version", origin = "mac")
        val backend = FakeBackend(listOf(remote(remoteVersion)))
        val service = SyncService(SyncStore(dir), store, backend)
        assertEquals(1, service.sync().conflicts)
        backend.fastManifest = true
        backend.pushed.clear()

        val outcome = service.sync()

        assertEquals(1, outcome.conflicts)
        assertEquals(0, outcome.pushed)
        assertTrue(backend.pushed.isEmpty())
        assertEquals(1, service.conflicts().size)
    }

    @Test
    fun `single item sync pulls only the selected remote change`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val remoteA = conv("a", "remote a", origin = "mac")
        val remoteB = conv("b", "remote b", origin = "mac")
        val backend = FakeBackend(listOf(remote(remoteA), remote(remoteB)))
        val service = SyncService(syncStore, store, backend)
        assertEquals(2, service.sync().pulled)
        val updatedA = remoteA.copy(
            messages = remoteA.messages + ChatMessage(role = Role.USER, text = "mac addition"),
        )
        backend.remote = listOf(remote(updatedA), remote(remoteB))

        val outcome = service.syncOne(null, "a")

        assertEquals(1, outcome.pulled)
        assertEquals(0, outcome.pushed)
        assertEquals("mac addition", store.load().first { it.id == "a" }.messages.last().text)
        assertEquals("remote b", store.load().first { it.id == "b" }.messages.first().text)
        assertEquals(1, backend.dumpItemsCalls)
    }

    @Test
    fun `sync pulls mac threads as separate conversations and pushes phone ones`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()

        val phone = conv("p1", "手机会话")
        store.upsert(phone)
        backend.remote = listOf(remote(conv("m1", "mac 会话", origin = "mac")))

        val service = SyncService(syncStore, store, backend)
        val outcome = service.sync()

        assertEquals(1, outcome.pulled)
        assertEquals(1, outcome.pushed)

        val all = store.load()
        assertEquals(setOf("p1", "m1"), all.map { it.id }.toSet())
        assertEquals("mac", all.first { it.id == "m1" }.origin)
        assertEquals("mac 会话", all.first { it.id == "m1" }.messages.first().text)
        assertTrue(backend.pushed.any { it.id == "p1" })
        // 推上去的会话要告诉用户电脑上怎么接着聊
        assertTrue(outcome.summary.contains("codex resume p1"))
        // 推完立刻补 Codex 原生记录，推完就能在电脑上 resume
        assertEquals(1, backend.afterPushCalls)
    }

    @Test
    fun `second sync is a no-op`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()
        store.upsert(conv("p1", "手机会话"))
        backend.remote = listOf(remote(conv("m1", "mac 会话")))

        val service = SyncService(syncStore, store, backend)
        service.sync()
        val second = service.sync()
        assertEquals(0, second.pulled)
        assertEquals(0, second.pushed)
        assertEquals(0, second.conflicts)
    }

    @Test
    fun `remote update preserves native session binding on the phone conversation`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()
        val local = conv("m1", "mac context", origin = "mac").copy(
            providerId = "provider-1",
            remoteThreadId = "thread-m1",
            remoteHostId = "host-mac",
        )
        store.upsert(local)
        backend.remote = listOf(remote(local))

        val service = SyncService(syncStore, store, backend)
        service.sync()
        backend.remote = listOf(
            remote(
                local.copy(
                    messages = local.messages +
                        ChatMessage(role = Role.USER, text = "mac continuation"),
                ),
            ),
        )
        assertEquals(1, service.sync().pulled)

        val refreshed = store.load().single { it.id == "m1" }
        assertEquals("thread-m1", refreshed.remoteThreadId)
        assertEquals("host-mac", refreshed.remoteHostId)
        assertEquals("provider-1", refreshed.providerId)
    }

    @Test
    fun `native preparation and post-turn rebase keep remote harness conflict-free`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val service = SyncService(SyncStore(dir), store, FakeBackend())
        val local = conv("phone-thread", "phone context")
        store.upsert(local)

        assertEquals("phone-thread", service.ensureNativeThread("host-mac", local))
        val backend = FakeBackend()
        backend.remote = listOf(
            remote(
                local.copy(
                    messages = local.messages + listOf(
                        ChatMessage(role = Role.USER, text = "continue remotely"),
                        ChatMessage(role = Role.ASSISTANT, text = "remote answer"),
                    ),
                ),
                rev = "remote-after-run",
            ),
        )
        val afterRun = service.let { SyncService(SyncStore(dir), store, backend) }
        val conversation = store.load().single().copy(
            messages = local.messages + listOf(
                ChatMessage(role = Role.USER, text = "continue remotely"),
                ChatMessage(role = Role.ASSISTANT, text = "remote answer"),
            ),
        )
        store.upsert(conversation)
        assertTrue(afterRun.rebaseAfterRemoteTurn("host-mac", conversation))

        val outcome = afterRun.sync("host-mac")
        assertEquals(0, outcome.conflicts)
        assertEquals(0, outcome.pulled)
        assertEquals(0, outcome.pushed)
    }

    @Test
    fun `incomplete remote body is skipped instead of stored as a truncated conversation`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()
        val c = conv("m1", "远端正文")
        backend.remote = listOf(remote(c).copy(count = c.messages.size + 1))

        val service = SyncService(syncStore, store, backend)
        val outcome = service.sync()

        assertEquals(0, outcome.pulled)
        assertTrue(store.load().isEmpty())
        assertTrue(outcome.summary.contains("正文不完整"))
        assertFalse(syncStore.load().base.containsKey("m1"))

        // 不记 base，下一次仍会重试，不会把残文当“已同步”
        service.sync()
        assertTrue(backend.fetches >= 2)
    }

    @Test
    fun `bulk fetch gaps fall back to individual fetch`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()
        val conversations = (0 until 21).map { i -> conv("m$i", "远端会话 $i") }
        backend.remote = conversations.map(::remote)
        backend.bulkOverride = backend.remote.take(10).associate { it.id to it.body }

        val outcome = SyncService(syncStore, store, backend).sync()

        assertEquals(21, outcome.pulled)
        assertEquals(21, store.load().size)
        assertTrue(backend.fetches >= 11)
    }

    @Test fun `pull saves version from body snapshot when desktop changes after listing`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val state = SyncStore(dir)
        val old = conv("x", "old")
        val fresh = old.copy(messages = old.messages + ChatMessage(role = Role.USER, text = "new"))
        val backend = FakeBackend(listOf(remote(old).copy(count = 2, rev = "old-rev")))
        backend.snapshotOverride = remote(fresh).copy(count = 3, rev = "fresh-rev")
        val service = SyncService(state, store, backend)
        assertEquals(1, service.sync().pulled)
        assertEquals(3, store.load().single().messages.size)
        assertEquals("fresh-rev", state.load().base["x"]?.remoteRev)
        backend.remote = listOf(backend.snapshotOverride!!)
        assertEquals(0, service.sync().pulled)
        assertTrue(service.conflicts().isEmpty())
    }

    @Test fun `cancelling a later download batch preserves completed conversations and versions`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val state = SyncStore(dir)
        val fake = FakeBackend((0 until 24).map { remote(conv("m$it", "complete $it")) })
        var batchCalls = 0
        val backend = object : SyncBackend by fake {
            override suspend fun visitContexts(hostId: String?, ids: Set<String>, consume: (RemoteContext) -> Unit) {
                if (++batchCalls == 2) throw kotlinx.coroutines.CancellationException("stopped")
                fake.visitContexts(hostId, ids, consume)
            }
        }
        val cancelled = runCatching { SyncService(state, store, backend).sync() }
        assertTrue(cancelled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(12, ConversationStore(dir).load().size)
        assertEquals(12, SyncStore(dir).load().base.size)
        val resumed = SyncService(SyncStore(dir), ConversationStore(dir), fake).sync()
        assertEquals(12, resumed.pulled)
        assertEquals(24, store.load().size)
    }

    @Test fun `interrupted streamed batch retries only missing snapshots`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val state = SyncStore(dir)
        val fake = FakeBackend((0 until 21).map { remote(conv("m$it", "complete $it")) })
        var streamed = 0
        val backend = object : SyncBackend by fake {
            override suspend fun visitContexts(hostId: String?, ids: Set<String>, consume: (RemoteContext) -> Unit) {
                consume(fake.remote.first { it.id == ids.first() })
                streamed++
                throw java.io.IOException("simulated partial download")
            }
        }
        val outcome = SyncService(state, store, backend).sync()
        assertEquals(21, outcome.pulled)
        assertEquals(21, store.load().size)
        assertEquals(21, state.load().base.size)
        assertEquals(21 - streamed, fake.fetches)
    }

    @Test fun `conflict listing does not download bodies before user chooses`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val state = SyncStore(dir)
        store.upsert(conv("x", "phone"))
        val backend = FakeBackend(listOf(remote(conv("x", "desktop"))))
        val service = SyncService(state, store, backend)
        assertEquals(1, service.sync().conflicts)
        assertEquals(0, backend.fetches)
        service.resolve("x", SyncService.Choice.REMOTE)
        assertEquals(1, backend.fetches)
        assertEquals("desktop", store.load().single().messages.first().text)
        assertTrue(service.conflicts().isEmpty())
    }

    @Test fun `stream decoded snapshots support individual pulls and all conflict choices`() = runBlocking {
        val incoming = conv("x", "desktop content😀")
        fun backend() = FakeBackend(listOf(remote(incoming).copy(count = incoming.messages.size))).apply {
            snapshotOverride = remote(incoming).copy(body = "", count = incoming.messages.size, parsedMessages = incoming.messages)
        }
        val pullDir = tmp.newFolder()
        val pullStore = ConversationStore(pullDir)
        val pull = SyncService(SyncStore(pullDir), pullStore, backend())
        assertEquals(1, pull.sync().pulled)
        assertEquals(incoming.messages.map { it.text }, pullStore.load().single().messages.map { it.text })
        assertEquals(0, pull.sync().pulled)
        SyncService.Choice.entries.forEach { choice ->
            val dir = tmp.newFolder()
            val store = ConversationStore(dir).apply { upsert(conv("x", "phone content")) }
            val service = SyncService(SyncStore(dir), store, backend())
            assertEquals(1, service.sync().conflicts)
            service.resolve("x", choice)
            assertTrue(service.conflicts().isEmpty())
            assertEquals(0, service.sync().conflicts)
            val texts = store.load().map { it.messages.first().text }.toSet()
            when (choice) {
                SyncService.Choice.LOCAL -> assertEquals(setOf("phone content"), texts)
                SyncService.Choice.REMOTE -> assertEquals(setOf("desktop content😀"), texts)
                SyncService.Choice.BOTH -> assertEquals(setOf("phone content", "desktop content😀"), texts)
            }
        }
    }

    @Test fun `ambiguous legacy snapshot is repaired only from a matching complete content hash`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val state = SyncStore(dir)
        val original = conv("x", "正文中有标题\n## assistant\n它仍是同一条消息")
        val updated = original.copy(messages = original.messages + ChatMessage(role = Role.USER, text = "phone-only addition"))
        store.upsert(updated)
        val oldBody = "# Codex 会话同步\n\n## user\n" + original.messages[0].text + "\n\n## assistant\n收到\n"
        val backend = FakeBackend(listOf(remote(original).copy(count = 2, body = oldBody)))
        val service = SyncService(state, store, backend)
        service.sync()
        service.resolve("x", SyncService.Choice.REMOTE)
        assertEquals(original.messages.map { it.text }, store.load().single().messages.map { it.text })
        assertEquals(1, backend.pushed.size)
        assertTrue(service.conflicts().isEmpty())
        assertEquals(0, service.sync().conflicts)
    }

    @Test fun `ambiguous legacy snapshot with different content hash is never replaced by phone`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir).apply { upsert(conv("x", "phone version")) }
        val state = SyncStore(dir)
        val backend = FakeBackend(listOf(remote(conv("x", "remote version")).copy(count = 2, body = "## user\nonly one message")))
        val service = SyncService(state, store, backend)
        service.sync()
        assertTrue(runCatching { service.resolve("x", SyncService.Choice.REMOTE) }.isFailure)
        assertEquals("phone version", store.load().single().messages.first().text)
        assertTrue(backend.pushed.isEmpty())
        assertEquals(1, service.conflicts().size)
    }

    @Test fun `repair replaces stale messages decoded from a legacy snapshot stream`() = runBlocking {
        val original = conv("x", "正文中的标题\n## assistant\n仍然属于同一条消息")
        val oldBody = "# Codex 会话同步\n\n## user\n" + original.messages[0].text + "\n\n## assistant\n收到\n"
        val decoded = SyncProtocol.parseMessages(oldBody)
        assertEquals(3, decoded.size)
        val legacy = remote(original).copy(count = 2, body = "", parsedMessages = decoded)
        val backend = FakeBackend(listOf(legacy)).apply { snapshotOverride = legacy }
        val dir = tmp.newFolder()
        val store = ConversationStore(dir).apply {
            upsert(original.copy(messages = original.messages + ChatMessage(role = Role.USER, text = "phone-only addition")))
        }
        val service = SyncService(SyncStore(dir), store, backend)
        assertEquals(1, service.sync().conflicts)
        service.resolve("x", SyncService.Choice.REMOTE)
        assertEquals(original.messages.map { it.text }, store.load().single().messages.map { it.text })
        assertEquals(1, backend.pushed.size)
        assertTrue(service.conflicts().isEmpty())
        assertEquals(0, service.sync().conflicts)
    }

    @Test
    fun `keep both resolves the conflict for good`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()

        val c = conv("x", "原始")
        store.upsert(c)
        backend.remote = listOf(remote(c))
        val service = SyncService(syncStore, store, backend)
        service.sync()

        store.upsert(c.copy(messages = c.messages + ChatMessage(role = Role.USER, text = "本机改动")))
        backend.remote = listOf(remote(c.copy(title = "远端改动", messages = c.messages + ChatMessage(role = Role.USER, text = "远端新增"))))
        assertEquals(1, service.sync().conflicts)

        val conflict = service.conflicts().single()
        service.resolve(conflict.id, SyncService.Choice.BOTH)
        assertTrue(service.conflicts().isEmpty())

        // 副本必须是合法 UUID：脚本按 UUID 落 Codex 原生记录，也按正常 id 参与同步
        val ids = store.load().map { it.id }
        assertTrue("应该保留两组，实际 $ids", ids.size >= 2)
        ids.filter { it != "x" }.forEach {
            runCatching { java.util.UUID.fromString(it) }
                .onFailure { throw AssertionError("副本 id 不是 UUID：$it") }
        }

        // 再同步一次：同一组不能再冒出冲突，也不能重复推拉
        val again = service.sync()
        assertEquals(0, again.conflicts)
        assertEquals(0, again.pulled)
        assertEquals(0, again.pushed)
    }

    @Test
    fun `deleted conversation stays deleted when syncing another host`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val defaultStore = SyncStore(dir)
        val backendA = FakeBackend()
        val backendB = FakeBackend()
        val c = conv("m1", "mac 会话", origin = "mac")
        backendA.remote = listOf(remote(c))
        backendB.remote = listOf(remote(c))

        val a = SyncService(defaultStore, store, backendA, storeForHost = { SyncStore(dir, scope = it) })
        a.sync("hostA")
        assertEquals(1, store.load().size)
        a.deleteConversations(listOf("m1"), "hostA")
        assertTrue("删除后本机应该没有这组", store.load().isEmpty())

        // 换一台同步目标：墓碑是全局的，不能又拉回来
        val b = SyncService(defaultStore, store, backendB, storeForHost = { SyncStore(dir, scope = it) })
        val outcome = b.sync("hostB")
        assertEquals(0, outcome.pulled)
        assertTrue("换主机同步也不应该复活", store.load().isEmpty())
    }

    @Test
    fun `conflict can keep local`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()

        val c = conv("x", "原始")
        store.upsert(c)
        val remoteC = remote(c)
        backend.remote = listOf(remoteC)

        val service = SyncService(syncStore, store, backend)
        service.sync() // 建立 base

        val edited = c.copy(messages = c.messages + ChatMessage(role = Role.USER, text = "本机改动"))
        store.upsert(edited)
        backend.remote = listOf(remoteC.copy(rev = "remote-2", body = SyncProtocol.render(c.copy(title = "远端改动"))))

        val outcome = service.sync()
        assertEquals(1, outcome.conflicts)
        val conflict = service.conflicts().single()

        service.resolve(conflict.id, SyncService.Choice.LOCAL)
        assertTrue(service.conflicts().isEmpty())
        assertTrue(backend.pushed.any { it.id == "x" && it.body.contains("本机改动") })
        assertEquals("本机改动", store.load().first { it.id == "x" }.messages.last().text)
    }

    @Test
    fun `conflict can keep remote`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()

        val c = conv("x", "原始")
        store.upsert(c)
        val remoteC = remote(c)
        backend.remote = listOf(remoteC)

        val service = SyncService(syncStore, store, backend)
        service.sync()

        store.upsert(c.copy(messages = c.messages + ChatMessage(role = Role.USER, text = "本机改动")))
        backend.remote = listOf(
            remote(c.copy(messages = listOf(ChatMessage(role = Role.USER, text = "远端改动"))), rev = "remote-2", title = "远端版本"),
        )
        service.sync()

        val conflict = service.conflicts().single()
        service.resolve(conflict.id, SyncService.Choice.REMOTE)

        val local = store.load().first { it.id == "x" }
        assertEquals("远端版本", local.title)
        assertTrue(local.origin == "mac")
    }

    @Test
    fun `conflict can keep both`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()

        val c = conv("x", "原始")
        store.upsert(c)
        val remoteC = remote(c)
        backend.remote = listOf(remoteC)

        val service = SyncService(syncStore, store, backend)
        service.sync()

        store.upsert(c.copy(messages = c.messages + ChatMessage(role = Role.USER, text = "本机改动")))
        backend.remote = listOf(
            remote(c.copy(messages = listOf(ChatMessage(role = Role.USER, text = "远端改动"))), rev = "remote-2", title = "远端版本"),
        )
        service.sync()

        val conflict = service.conflicts().single()
        service.resolve(conflict.id, SyncService.Choice.BOTH)

        val all = store.load()
        assertEquals(2, all.size)
        assertTrue(all.any { it.title == "远端版本" && it.origin == "mac" })
        assertTrue(all.any { it.id == "x" && it.messages.any { m -> m.text == "本机改动" } })
    }

    @Test
    fun `delete removes locally and remembers it`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val syncStore = SyncStore(dir)
        val backend = FakeBackend()
        val c = conv("p1", "要删的会话")
        store.upsert(c)
        val service = SyncService(syncStore, store, backend)
        service.sync()

        val removed = service.deleteConversations(listOf("p1"))
        assertEquals(1, removed)
        assertTrue(store.load().isEmpty())
        assertTrue(syncStore.load().deleted.containsKey("p1"))

        backend.remote = listOf(remote(c))
        val outcome = service.sync()
        assertEquals(0, outcome.pulled)
        assertTrue(store.load().isEmpty())
        assertFalse(backend.pushed.any { it.id == "p1" && it.body.contains("要删的会话") && backend.pushed.size > 1 })
    }

    @Test fun `failed or empty remote read keeps conflict and original local content across restart`() = runBlocking {
        for (empty in listOf(false, true)) {
            val dir = tmp.newFolder()
            val local = conv("x", "local original")
            val store = ConversationStore(dir).apply { upsert(local) }
            val syncStore = SyncStore(dir)
            val backend = FakeBackend(listOf(remote(conv("x", "remote version"))))
            val service = SyncService(syncStore, store, backend)
            service.sync()
            assertEquals(1, service.conflicts().size)
            if (empty) backend.emptyFetchIds = setOf("x") else backend.fetchError = java.io.IOException("offline")
            val result = runCatching { service.resolve("x", SyncService.Choice.REMOTE) }
            assertTrue("读取失败必须返回失败", result.isFailure)
            assertEquals("local original", store.load().single().messages.first().text)
            val restarted = SyncService(SyncStore(dir), ConversationStore(dir), backend)
            assertEquals(1, restarted.conflicts().size)
            backend.fetchError = null
            backend.emptyFetchIds = emptySet()
            restarted.resolve("x", SyncService.Choice.REMOTE)
            assertEquals("remote version", store.load().single().messages.first().text)
            assertTrue(SyncStore(dir).load().conflicts.isEmpty())
            assertEquals(0, restarted.sync().conflicts)
            assertTrue(restarted.conflicts().isEmpty())
        }
    }

    @Test fun `incomplete body cannot resolve a conflict as success`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir).apply { upsert(conv("x", "local")) }
        val backend = FakeBackend(listOf(remote(conv("x", "remote")).copy(count = 100)))
        val service = SyncService(SyncStore(dir), store, backend)
        service.sync()
        assertTrue(runCatching { service.resolve("x", SyncService.Choice.REMOTE) }.isFailure)
        assertEquals("local", store.load().single().messages.first().text)
        assertEquals(1, service.conflicts().size)
    }

    @Test fun `partial upload failure saves earlier pulls and pushes as completed progress`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir).apply { upsertAll(listOf(conv("a", "phone a"), conv("b", "phone b"))) }
        val syncStore = SyncStore(dir)
        val backend = FakeBackend(listOf(remote(conv("r", "remote")))).apply { failPushId = "b" }
        val service = SyncService(syncStore, store, backend)
        assertTrue(runCatching { service.sync() }.isFailure)
        assertEquals(setOf("a", "r"), SyncStore(dir).load().base.keys)
        backend.failPushId = null
        val outcome = service.sync()
        assertEquals(0, outcome.pulled)
        assertEquals(1, outcome.pushed)
        assertEquals(listOf("a", "b"), backend.pushed.map { it.id })
    }

    @Test fun `old conflict is removed when both sides already align`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir).apply { upsert(conv("x", "local")) }
        val backend = FakeBackend(listOf(remote(conv("x", "remote"))))
        val syncStore = SyncStore(dir)
        val service = SyncService(syncStore, store, backend)
        service.sync()
        assertEquals(1, service.conflicts().size)
        store.upsert(conv("x", "remote"))
        assertEquals(0, service.sync().conflicts)
        assertTrue(SyncStore(dir).load().conflicts.isEmpty())
        // 首次相同内容也必须建立 base，之后只有本机改动应正常上传。
        store.upsert(conv("x", "local edited again"))
        assertEquals(1, service.sync().pushed)
        assertTrue(service.conflicts().isEmpty())
    }

    @Test fun `missing item in remote window does not discard or overwrite a pending conflict`() = runBlocking {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir).apply { upsert(conv("x", "local")) }
        val backend = FakeBackend(listOf(remote(conv("x", "remote"))))
        val service = SyncService(SyncStore(dir), store, backend)
        service.sync()
        backend.remote = emptyList()
        service.sync()
        assertEquals(1, service.conflicts().size)
        assertTrue(backend.pushed.isEmpty())
        assertTrue(runCatching { service.resolve("x", SyncService.Choice.REMOTE) }.isFailure)
        assertEquals(1, service.conflicts().size)
    }
}
