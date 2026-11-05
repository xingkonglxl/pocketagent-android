package app.pocketagent.android

import app.pocketagent.android.sync.SyncProtocol
import app.pocketagent.android.sync.SyncBackend
import app.pocketagent.android.sync.SyncService
import app.pocketagent.android.sync.RemoteContext
import app.pocketagent.android.sync.PhoneContext
import app.pocketagent.android.data.ConversationStore
import app.pocketagent.android.data.SyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/** 仅在显式提供本机导出文件时核验实际 Python → Kotlin 协议，不输出会话内容。 */
class SyncExportIntegrationTest {
    @Test fun `entire exported archive imports and the next sync is unchanged`() = runBlocking {
        val path = System.getenv("POCKETAGENT_TEST_EXPORT_FILE")
        assumeNotNull(path)
        // 模拟远端按需下载：内存只保留清单和文件偏移，不额外常驻整份压缩档案。
        // 真实 SSH 后端也不会把全部历史压缩快照同时缓存到手机。
        val source = File(path!!)
        val offsets = linkedMapOf<String, Pair<Long, Int>>()
        val manifest = mutableListOf<RemoteContext>()
        var offset = 0L
        source.useLines { lines -> lines.forEach { line ->
            val length = line.toByteArray(Charsets.UTF_8).size
            if (line.isNotBlank()) {
                var headerEnd = -1
                repeat(6) { headerEnd = line.indexOf('\t', headerEnd + 1) }
                check(headerEnd >= 0)
                val context = SyncProtocol.parseDump(line.substring(0, headerEnd)).single()
                manifest += context
                offsets[context.id] = offset to length
            }
            offset += length + 1 // 导出协议每组以一个 LF 结束
        } }
        fun encoded(id: String): String {
            val (start, length) = offsets.getValue(id)
            return RandomAccessFile(source, "r").use { input ->
                input.seek(start)
                val bytes = ByteArray(length)
                input.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        }
        var fetches = 0
        val backend = object : SyncBackend {
            override val label = "integration"
            override suspend fun dump(hostId: String?) = manifest
            override suspend fun fetch(hostId: String?, id: String) = fetchContext(hostId, id).body
            override suspend fun fetchContext(hostId: String?, id: String): RemoteContext {
                fetches++
                return SyncProtocol.parseDump(encoded(id)).single()
            }
            override suspend fun fetchContexts(hostId: String?, ids: Set<String>?): List<RemoteContext> =
                (ids ?: offsets.keys).map { fetchContext(hostId, it) }
            override suspend fun visitContexts(hostId: String?, ids: Set<String>, consume: (RemoteContext) -> Unit) {
                ids.forEach { id ->
                    fetches++
                    SyncProtocol.parseDumpSequence(encoded(id), decodeMessages = true).forEach(consume)
                }
            }
            override suspend fun push(hostId: String?, ctx: PhoneContext): String = error("unexpected upload")
            override suspend fun deleteRemote(hostId: String?, id: String) = error("unexpected delete")
        }
        val directory = Files.createTempDirectory("pocketagent-sync-archive-").toFile()
        try {
            val store = ConversationStore(directory)
            val state = SyncStore(directory)
            val service = SyncService(state, store, backend)
            val first = service.sync()
            assertEquals(manifest.size, first.pulled)
            // 原子重开磁盘快照，确保确实重读保存的 JSON，而非只核验内存中的结果。
            val disk = File(directory, "conversations.json")
            val reopened = File(directory, "reopened.json")
            disk.copyTo(reopened)
            Files.move(reopened.toPath(), disk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val imported = store.load()
            assertEquals(manifest.size, imported.size)
            assertEquals(manifest.sumOf { it.count }, imported.sumOf { it.messages.size })
            val fetchedBefore = fetches
            val next = SyncService(SyncStore(directory), ConversationStore(directory), backend).sync()
            assertEquals(0, next.pulled)
            assertEquals(0, next.pushed)
            assertEquals(0, next.conflicts)
            assertEquals(fetchedBefore, fetches)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `exported snapshots parse every message and survive a second round trip`() {
        val path = System.getenv("POCKETAGENT_TEST_EXPORT_FILE")
        assumeNotNull(path)
        var count = 0
        File(path!!).useLines { lines ->
            lines.forEachIndexed { index, line ->
                val ctx = SyncProtocol.parseDump(line).single()
                val conv = SyncProtocol.toConversation(ctx)
                assertEquals("snapshot $index message count", ctx.count, conv.messages.size)
                assertTrue("snapshot $index is not empty", conv.messages.isNotEmpty())
                val again = SyncProtocol.parseMessages(SyncProtocol.render(conv))
                assertEquals("snapshot $index content version", SyncProtocol.revOf(conv.messages), SyncProtocol.revOf(again))
                assertTrue("snapshot $index full texts match", conv.messages.map { it.text } == again.map { it.text })
                count++
            }
        }
        assertTrue(count > 0)
    }
}
