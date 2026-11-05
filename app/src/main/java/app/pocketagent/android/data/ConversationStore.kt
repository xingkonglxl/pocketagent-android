package app.pocketagent.android.data

import app.pocketagent.android.agent.ApprovalRecord
import app.pocketagent.android.agent.decodeReplyText
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalSerializationApi::class)
class ConversationStore(
    private val dir: File,
    /**
     * 默认不丢会话。旧版本默认 300 组，超过后会在保存时静默删掉最老的会话；
     * 同步一次拉回大量会话时，base 还会把这些被删掉的会话记成“已同步”，导致永久丢失。
     * 需要限制数量的调用方仍可显式传入。
     */
    private val maxConversations: Int = Int.MAX_VALUE,
    private val cipher: StoreCipher? = null,
) {
    private val file = File(dir, "conversations.json")
    private val backupFile = File(dir, "conversations.json.bak")
    private val serializer = ListSerializer(Conversation.serializer())

    private data class FileVersion(val size: Long, val modified: FileTime, val key: String?)
    private data class CacheKey(val path: String, val limit: Int)
    private data class Cached(val version: FileVersion, val items: WeakReference<List<Conversation>>)

    companion object {
        // 首页、同步页和续聊复用同一份未变化的历史；弱引用让退出页面后的正文可被回收。
        private val snapshots = ConcurrentHashMap<CacheKey, Cached>()
        private val DESKTOP_INTERNAL_PREFIXES = listOf(
            "The following is the Codex agent history",
            "<codex_internal_context",
            "<environment_context>",
            "# AGENTS.md",
            "<INSTRUCTIONS>",
        )
        private val CODEX_PROGRESS_KEYS = setOf(
            "constraints", "critical_references", "current_progress", "next_steps",
        )
    }

    private fun version(file: File): FileVersion? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).let {
            FileVersion(it.size(), it.lastModifiedTime(), it.fileKey()?.toString())
        }
    } catch (_: IOException) { null }

    private fun cacheKey(file: File) = CacheKey(file.canonicalPath, maxConversations)

    private fun remember(file: File, items: List<Conversation>) {
        version(file)?.let { snapshots[cacheKey(file)] = Cached(it, WeakReference(items)) }
    }

    private fun encrypted(file: File): Boolean = file.exists() && file.inputStream().use {
        val head = ByteArray(5)
        it.read(head) == head.size && head.contentEquals("PAEN1".toByteArray(Charsets.US_ASCII))
    }

    private fun decode(file: File): List<Conversation>? = try {
        if (!file.exists()) null
        // 旧密文必须用当前调用方的密钥解密，不能从其它实例的缓存绕过密钥校验。
        else if (encrypted(file)) readStore(file, cipher)?.let { ordered(AppJson.decodeFromString(serializer, it)) }
        else {
            val key = cacheKey(file)
            val current = version(file)
            val cached = snapshots[key]?.takeIf { it.version == current }?.items?.get()
            cached ?: file.inputStream().buffered().use {
                ordered(AppJson.decodeFromStream(serializer, it))
            }.also { items ->
                // 读取期间被其它写入替换时不缓存，下一次必须重新读最新文件。
                if (current != null && version(file) == current) snapshots[key] = Cached(current, WeakReference(items))
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun ordered(items: List<Conversation>) = items.map {
        it.withoutDesktopInternals().normalizedTitle()
    }
        .sortedByDescending { it.updatedAt }.take(maxConversations)

    /**
     * 旧版本同步把 Codex 的审批提示和环境注入当成普通消息落盘。
     * 这些记录既不该显示，也不该再次进入模型上下文；读取旧数据时一并剔除。
     */
    private fun Conversation.withoutDesktopInternals(): Conversation {
        val kept = messages.filterNot { message ->
            val text = message.text.trimStart()
            if (message.role == Role.USER) {
                DESKTOP_INTERNAL_PREFIXES.any(text::startsWith)
            } else {
                message.role == Role.ASSISTANT &&
                    message.toolCalls.isEmpty() &&
                    (
                        ApprovalRecord.parse(decodeReplyText(message.text).body) != null ||
                            isCodexProgressRecord(decodeReplyText(message.text).body)
                        )
            }
        }
        return if (kept.size == messages.size) this else copy(messages = kept)
    }

    private fun isCodexProgressRecord(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.contains("\"current_progress\"")) return false
        val keys = runCatching { Json.parseToJsonElement(trimmed).jsonObject.keys }.getOrNull()
            ?: return false
        return keys.all { it in CODEX_PROGRESS_KEYS }
    }

    /** 不创建整份历史的 JSON 字符串/UTF-8 副本，几百组会话也只逐段读写。 */
    private fun atomicWrite(target: File, write: (java.io.OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(target.name, ".tmp", target.parentFile)
        temporary.setReadable(false, false)
        temporary.setWritable(false, false)
        temporary.setReadable(true, true)
        temporary.setWritable(true, true)
        try {
            temporary.outputStream().use { output ->
                output.buffered().use(write)
            }
            if (!temporary.renameTo(target)) throw IOException("会话文件原子替换失败，原历史已保留")
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun load(): List<Conversation> {
        val primary = decode(file)
        if (primary != null) {
            // 历史遗留的坏标题（`/model`、「新对话」）在读取时按内容重新推导
            return primary
        }

        // 主文件缺失/损坏时回退到上一次成功保存的副本。不能把损坏直接当成“没有会话”，
        // 否则下一次 upsert 会只用一条新会话覆盖整个历史。
        val backup = decode(backupFile)
        return backup.orEmpty()
    }

    @Synchronized
    fun save(conversations: List<Conversation>) {
        val trimmed = ordered(conversations)
        require(cipher != null || !encrypted(file)) { "磁盘上是加密数据但这次拿不到密钥，拒绝覆盖" }
        runCatching { backupCurrent() }
        atomicWrite(file) { AppJson.encodeToStream(serializer, trimmed, it) }
        remember(file, trimmed)
    }

    /** 只备份能成功解析的旧文件，避免把一次损坏覆盖到最后的可用副本上。 */
    private fun backupCurrent() {
        if (!file.exists() || file.length() == 0L) return
        if (decode(file) == null) return
        atomicWrite(backupFile) { output -> file.inputStream().use { it.copyTo(output) } }
    }

    @Synchronized
    fun upsert(conversation: Conversation): List<Conversation> {
        val list = load().toMutableList()
        val i = list.indexOfFirst { it.id == conversation.id }
        if (i >= 0) list[i] = conversation else list.add(0, conversation)
        save(list)
        return ordered(list)
    }

    /** 批量写入（同步一次拉很多组时用，避免每组都重写整个文件）。 */
    @Synchronized
    fun upsertAll(items: List<Conversation>): List<Conversation> {
        if (items.isEmpty()) return load()
        val list = load().toMutableList()
        items.forEach { conversation ->
            val i = list.indexOfFirst { it.id == conversation.id }
            if (i >= 0) list[i] = conversation else list.add(0, conversation)
        }
        save(list)
        return ordered(list)
    }

    @Synchronized
    fun delete(id: String): List<Conversation> {
        save(load().filterNot { it.id == id })
        return load()
    }
}
