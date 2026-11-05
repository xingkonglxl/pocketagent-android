package app.pocketagent.android.sync

import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ToolCallRecord
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 远端（Mac）上的一组会话。body 是 markdown，格式见 [SyncProtocol.render]。 */
@Serializable
data class RemoteContext(
    val id: String,
    val title: String = "",
    val startedAt: String = "",
    val updatedAt: Long = 0L,
    val count: Int = 0,
    val rev: String = "",
    val body: String = "",
    /** 批量下载时直接从 gzip 流解析，避免先构造整组 markdown 再复制成消息。 */
    @kotlinx.serialization.Transient val parsedMessages: List<ChatMessage>? = null,
)

/** 手机推到 Mac 的会话文件（~/.pocketagent/sync/from-phone/<id>.json）。 */
@Serializable
data class PhoneContext(
    val id: String,
    val title: String = "",
    val startedAt: String = "",
    val updatedAt: Long = 0L,
    val count: Int = 0,
    val rev: String = "",
    val body: String = "",
    val device: String = "android",
)

object SyncProtocol {

    const val MAGIC_TITLE = "# Codex 会话同步"
    private const val ROLE_MARKER = "<!-- pocketagent-role:"
    private const val USER_ROLE_LINE = "## user <!-- pocketagent-role:user -->"
    private const val ASSISTANT_ROLE_LINE = "## assistant <!-- pocketagent-role:assistant -->"
    private const val MESSAGE_END = "<!-- pocketagent-message-end -->"
    private const val META_PREFIX = "<!-- pocketagent-message-v1:"
    private const val META_SUFFIX = " -->"
    private const val FRAMED_MAGIC = "<!-- pocketagent-framed-v2 -->"
    private const val LENGTH_PREFIX = "<!-- pocketagent-text-bytes:"

    /**
     * 正文里放一段不可见的压缩元数据，保留工具调用/结果和思考过程。
     * Mac 端脚本按原样转发 body，因此手机推上去再拉回来仍能无损还原。
     */
    @Serializable
    private data class MessageMeta(
        val id: String = "",
        val reasoning: String = "",
        /** 思考模型的原样 reasoning item；同步丢了它，回到手机上再调工具会被中转站拒绝 */
        val reasoningItem: String? = null,
        val toolCalls: List<ToolCallRecord> = emptyList(),
        val createdAt: Long = 0L,
        val model: String? = null,
        val tokensIn: Int = 0,
        val tokensOut: Int = 0,
        val stopped: Boolean = false,
    )

    private fun isRenderable(m: ChatMessage): Boolean =
        (m.role == Role.USER || m.role == Role.ASSISTANT) &&
            (m.text.isNotEmpty() || m.reasoning.isNotEmpty() || m.toolCalls.isNotEmpty())

    /** 与 String.toByteArray(UTF_8) 一致，但长正文只用固定大小的编码缓冲区。 */
    private fun utf8(value: String, digest: MessageDigest? = null): Long {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val input = CharBuffer.wrap(value)
        val output = ByteBuffer.allocate(8192)
        var size = 0L
        while (true) {
            val result = encoder.encode(input, output, true)
            size += output.position()
            digest?.update(output.array(), 0, output.position())
            output.clear()
            if (result.isUnderflow) break
            if (result.isError) result.throwException()
        }
        return size
    }

    private fun MessageDigest.part(value: String) {
        update(utf8(value).toString().toByteArray(Charsets.US_ASCII))
        update(':'.code.toByte())
        utf8(value, this)
        update('\n'.code.toByte())
    }

    private fun hex(digest: MessageDigest): String =
        buildString { digest.digest().forEach { append("%02x".format(it)) } }.take(16)

    private fun legacyRevOf(messages: List<ChatMessage>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        var first = true
        messages.forEach { message ->
            if (message.role != Role.USER && message.role != Role.ASSISTANT) return@forEach
            if (!first) digest.update(1.toByte())
            first = false
            digest.update(message.role.name.toByteArray(Charsets.US_ASCII))
            digest.update(2.toByte())
            utf8(message.text, digest)
        }
        return hex(digest)
    }

    /**
     * 内容版本号。纯文本会话沿用旧算法，升级后不会把所有历史会话误判成本机改动；
     * 一旦出现思考过程/工具调用，文本、状态和完整工具结果都参与，避免结果变化不触发同步。
     */
    fun revOf(messages: List<ChatMessage>): String {
        val relevant = messages.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
        if (relevant.none { it.reasoning.isNotEmpty() || it.toolCalls.isNotEmpty() || it.stopped }) {
            return legacyRevOf(relevant)
        }
        val digest = MessageDigest.getInstance("SHA-1")
        relevant.filter(::isRenderable).forEach { m ->
            digest.part(m.role.name)
            digest.part(m.text)
            digest.part(m.reasoning)
            digest.part(if (m.stopped) "1" else "0")
            digest.part(m.toolCalls.size.toString())
            m.toolCalls.forEach { call ->
                digest.part(call.id)
                digest.part(call.name)
                digest.part(call.arguments)
                digest.part(call.status.name)
                digest.part(call.result.orEmpty())
                digest.part(call.durationMs.toString())
            }
        }
        return hex(digest)
    }

    fun revOfText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        utf8(text, digest)
        return hex(digest)
    }

    /** 把一组会话渲染成 markdown（手机推给 Mac，和 Mac 导出的格式一致）。 */
    fun render(conversation: Conversation, source: String = "android"): String {
        val header = buildString {
            appendLine(MAGIC_TITLE)
            appendLine(FRAMED_MAGIC)
            appendLine("- 会话：${conversation.id}")
            appendLine("- 标题：${conversation.title}")
            appendLine("- 开始：${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date(conversation.createdAt))}")
            appendLine("- 来源：$source")
            appendLine()
        }
        val frames = conversation.messages.filter(::isRenderable).map { m ->
            buildString {
                // 行尾标记只给解析器看，Markdown 渲染时仍是一个正常的二级标题。
                appendLine(if (m.role == Role.USER) USER_ROLE_LINE else ASSISTANT_ROLE_LINE)
                encodeMeta(m)?.let { appendLine(it) }
                appendLine("$LENGTH_PREFIX${utf8(m.text)}$META_SUFFIX")
            } to m.text
        }
        // 精确分配，避免十几 MB 的正文在 StringBuilder 扩容时暂时持有多份大数组。
        val ending = "\n$MESSAGE_END\n\n"
        val size = header.length.toLong() + frames.sumOf { (prefix, text) ->
            prefix.length.toLong() + text.length + ending.length
        }
        require(size <= Int.MAX_VALUE) { "单组同步正文超过字符串容量" }
        return buildString(size.toInt()) {
            append(header)
            frames.forEach { (prefix, text) ->
                append(prefix)
                append(text)
                // 固定补一个换行，正文末尾的空行不会被吞。
                append(ending)
            }
        }
    }

    /** 解析 Mac 导出的 markdown（`## user` / `## assistant` 分段）。 */
    fun parseMessages(body: String): List<ChatMessage> {
        if (body.startsWith("$MAGIC_TITLE\n$FRAMED_MAGIC\n")) {
            // 新协议按 UTF-8 字节长度读正文，未闭合代码块和正文中的标记都不会影响边界。
            // 缺字节/缺结束标记时整组拒收，不退回启发式解析得到一个残本。
            return try { parseFramedMessages(body) } catch (_: Exception) { emptyList() }
        }
        val out = mutableListOf<ChatMessage>()
        var role: Role? = null
        val buf = StringBuilder()
        var meta: MessageMeta? = null
        var metaAllowed = false
        var fence: String? = null
        val taggedRoles = body.contains(ROLE_MARKER)

        fun roleOf(line: String): Role? {
            val clean = line.trimEnd()
            return if (taggedRoles) {
                when (clean) {
                    USER_ROLE_LINE -> Role.USER
                    ASSISTANT_ROLE_LINE -> Role.ASSISTANT
                    else -> null
                }
            } else {
                when (clean) {
                    "## user" -> Role.USER
                    "## assistant" -> Role.ASSISTANT
                    else -> null
                }
            }
        }

        fun flush(exactEnd: Boolean = false) {
            val r = role ?: return
            val buffered = buf.toString()
            val text = if (exactEnd) buffered.removeSuffix("\n") else buffered.trimEnd('\n', '\r')
            val m = meta
            buf.setLength(0)
            meta = null
            if (text.isNotEmpty() || m != null) {
                out += ChatMessage(
                    id = m?.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    role = r,
                    text = text,
                    reasoning = m?.reasoning.orEmpty(),
                    reasoningItem = m?.reasoningItem,
                    toolCalls = m?.toolCalls.orEmpty(),
                    createdAt = m?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    model = m?.model,
                    tokensIn = m?.tokensIn ?: 0,
                    tokensOut = m?.tokensOut ?: 0,
                    stopped = m?.stopped == true,
                )
            }
        }

        body.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            val taggedRole = if (fence == null) roleOf(line) else null
            when (taggedRole) {
                Role.USER -> {
                    flush()
                    role = Role.USER
                    metaAllowed = true
                    return@forEach
                }
                Role.ASSISTANT -> {
                    flush()
                    role = Role.ASSISTANT
                    metaAllowed = true
                    return@forEach
                }
                else -> Unit
            }

            if (taggedRoles && fence == null && line.trimEnd() == MESSAGE_END) {
                flush(exactEnd = true)
                role = null
                return@forEach
            }
            if (fence != null) {
                buf.appendLine(line)
                if (line.trimStart().startsWith(fence!!)) fence = null
                return@forEach
            }
            val fenceStart = line.trimStart().let {
                when {
                    it.startsWith("```") -> "```"
                    it.startsWith("~~~") -> "~~~"
                    else -> null
                }
            }
            if (fenceStart != null) {
                fence = fenceStart
                buf.appendLine(line)
                return@forEach
            }
            if (role != null && meta == null && metaAllowed) {
                val decoded = decodeMeta(line)
                if (decoded != null) {
                    meta = decoded
                    metaAllowed = false
                    return@forEach
                }
            }
            if (role != null) {
                if (line.isNotBlank()) metaAllowed = false
                buf.appendLine(line)
            }
        }
        flush()
        return out
    }

    private fun parseFramedMessages(body: String): List<ChatMessage> {
        var offset = 0
        fun line(): String {
            check(offset < body.length) { "缺少同步帧" }
            val start = offset
            val end = body.indexOf('\n', start).takeIf { it >= 0 } ?: body.length
            val value = body.substring(start, end)
            offset = if (end < body.length) end + 1 else end
            return value
        }
        fun text(byteCount: Long): String {
            check(byteCount >= 0 && byteCount <= (body.length - offset).toLong() * 3) { "同步正文不完整" }
            val start = offset
            var remaining = byteCount
            // 直接遍历 UTF-16，按 UTF-8 长度定边界，不再复制整组正文为 byte[]。
            while (remaining > 0) {
                check(offset < body.length) { "同步正文不完整" }
                val c = body[offset++]
                val width = when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.isHighSurrogate() && offset < body.length && body[offset].isLowSurrogate() -> {
                        offset++
                        4
                    }
                    c.isSurrogate() -> 1 // String.toByteArray 的无效代理项替代字节为 '?'
                    else -> 3
                }
                remaining -= width
                check(remaining >= 0) { "同步长度截断 UTF-8 字符" }
            }
            return body.substring(start, offset)
        }
        val out = mutableListOf<ChatMessage>()
        var started = false
        while (offset < body.length) {
            val header = line()
            val role = when (header) {
                USER_ROLE_LINE -> Role.USER
                ASSISTANT_ROLE_LINE -> Role.ASSISTANT
                else -> null
            }
            if (role == null) {
                check(!started || header.isBlank()) { "同步帧之间包含无效内容" }
                continue
            }
            started = true
            var next = line()
            val meta = if (next.startsWith(META_PREFIX)) {
                val value = checkNotNull(decodeMeta(next)) { "同步元数据损坏" }
                next = line()
                value
            } else null
            check(next.startsWith(LENGTH_PREFIX) && next.endsWith(META_SUFFIX)) { "缺少消息长度" }
            val count = next.removePrefix(LENGTH_PREFIX).removeSuffix(META_SUFFIX).toLong()
            val text = text(count)
            check(line().isEmpty() && line() == MESSAGE_END) { "同步帧结束标记缺失" }
            out += ChatMessage(
                id = meta?.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                role = role,
                text = text,
                reasoning = meta?.reasoning.orEmpty(),
                reasoningItem = meta?.reasoningItem,
                toolCalls = meta?.toolCalls.orEmpty(),
                createdAt = meta?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                model = meta?.model,
                tokensIn = meta?.tokensIn ?: 0,
                tokensOut = meta?.tokensOut ?: 0,
                stopped = meta?.stopped == true,
            )
        }
        return out
    }

    private fun parseSnapshotStream(raw: InputStream): List<ChatMessage> {
        val input = raw.buffered()
        val signature = "$MAGIC_TITLE\n$FRAMED_MAGIC\n".toByteArray(Charsets.UTF_8)
        input.mark(signature.size + 1)
        val prefix = ByteArray(signature.size)
        var read = 0
        while (read < prefix.size) {
            val count = input.read(prefix, read, prefix.size - read)
            if (count < 0) break
            read += count
        }
        if (read != prefix.size || !prefix.contentEquals(signature)) {
            input.reset()
            return parseMessages(input.reader(Charsets.UTF_8).readText())
        }
        fun line(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
                if (byte == '\n'.code) return bytes.toString("UTF-8")
                bytes.write(byte)
            }
        }
        val messages = mutableListOf<ChatMessage>()
        var started = false
        while (true) {
            val header = line() ?: break
            val role = when (header) {
                USER_ROLE_LINE -> Role.USER
                ASSISTANT_ROLE_LINE -> Role.ASSISTANT
                else -> null
            }
            if (role == null) {
                check(!started || header.isBlank()) { "同步帧之间包含无效内容" }
                continue
            }
            started = true
            var next = checkNotNull(line()) { "缺少同步帧" }
            val meta = if (next.startsWith(META_PREFIX)) {
                val value = checkNotNull(decodeMeta(next)) { "同步元数据损坏" }
                next = checkNotNull(line()) { "缺少消息长度" }
                value
            } else null
            check(next.startsWith(LENGTH_PREFIX) && next.endsWith(META_SUFFIX)) { "缺少消息长度" }
            var remaining = next.removePrefix(LENGTH_PREFIX).removeSuffix(META_SUFFIX).toLong()
            check(remaining >= 0) { "同步正文长度无效" }
            val limited = object : InputStream() {
                override fun read(): Int {
                    if (remaining == 0L) return -1
                    val value = input.read()
                    if (value < 0) throw EOFException("同步正文不完整")
                    remaining--
                    return value
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (remaining == 0L) return -1
                    val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
                    if (count < 0) throw EOFException("同步正文不完整")
                    remaining -= count
                    return count
                }
            }
            val text = InputStreamReader(limited, Charsets.UTF_8.newDecoder()).readText()
            check(line() == "" && line() == MESSAGE_END) { "同步帧结束标记缺失" }
            messages += ChatMessage(
                id = meta?.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                role = role, text = text, reasoning = meta?.reasoning.orEmpty(),
                reasoningItem = meta?.reasoningItem, toolCalls = meta?.toolCalls.orEmpty(),
                createdAt = meta?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                model = meta?.model, tokensIn = meta?.tokensIn ?: 0,
                tokensOut = meta?.tokensOut ?: 0, stopped = meta?.stopped == true,
            )
        }
        return messages
    }

    private fun encodeMeta(message: ChatMessage): String? {
        if (
            message.reasoning.isEmpty() && message.toolCalls.isEmpty() && !message.stopped &&
            message.reasoningItem.isNullOrBlank()
        ) {
            return null
        }
        val meta = MessageMeta(
            id = message.id,
            reasoning = message.reasoning,
            reasoningItem = message.reasoningItem,
            toolCalls = message.toolCalls,
            createdAt = message.createdAt,
            model = message.model,
            tokensIn = message.tokensIn,
            tokensOut = message.tokensOut,
            stopped = message.stopped,
        )
        val json = app.pocketagent.android.data.AppJson
            .encodeToString(MessageMeta.serializer(), meta)
            .toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(json) }
        }.toByteArray()
        return META_PREFIX + Base64.getEncoder().encodeToString(compressed) + META_SUFFIX
    }

    private fun decodeMeta(line: String): MessageMeta? {
        val clean = line.trim()
        if (!clean.startsWith(META_PREFIX) || !clean.endsWith(META_SUFFIX)) return null
        val encoded = clean.substring(META_PREFIX.length, clean.length - META_SUFFIX.length)
        return try {
            val compressed = Base64.getDecoder().decode(encoded)
            val json = GZIPInputStream(compressed.inputStream()).reader(Charsets.UTF_8).use { it.readText() }
            app.pocketagent.android.data.AppJson.decodeFromString(MessageMeta.serializer(), json)
        } catch (_: Exception) { null }
    }

    /** 导出的 markdown → 一条独立会话（新对话，不塞进当前窗口）。 */
    fun toConversation(ctx: RemoteContext, source: String = "mac"): Conversation {
        val messages = ctx.parsedMessages ?: parseMessages(ctx.body)
        val title = ctx.title.ifBlank {
            messages.firstOrNull { it.role == Role.USER }?.text?.replace(Regex("\\s+"), " ")?.take(24)
                ?: "Mac 会话 ${ctx.id.take(8)}"
        }
        return Conversation(
            id = ctx.id,
            title = title,
            messages = messages,
            createdAt = ctx.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            updatedAt = ctx.updatedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            origin = source,
        )
    }

    fun toPhoneContext(conversation: Conversation): PhoneContext = PhoneContext(
        id = conversation.id,
        title = conversation.title,
        startedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(conversation.createdAt)),
        updatedAt = conversation.updatedAt,
        count = conversation.messages.count(::isRenderable),
        rev = revOf(conversation.messages),
        body = render(conversation),
    )

    fun encodePhoneContext(ctx: PhoneContext): String =
        app.pocketagent.android.data.AppJson.encodeToString(PhoneContext.serializer(), ctx)

    /**
     * 解析 Mac 端的 dump 输出，每行：
     * `id \t title \t startedAt \t updatedAt \t count \t rev [\t base64(body)]`
     * 新版 dump 不带正文（正文按需 export），老格式仍兼容。
     */
    fun parseDump(text: String): List<RemoteContext> = parseDumpSequence(text).toList()

    /** 批量压缩传输后逐组解压、消费，不能同时保留整批长会话正文。 */
    fun parseDumpSequence(text: String, decodeMessages: Boolean = false): Sequence<RemoteContext> = text.lineSequence()
        .mapNotNull { line ->
            val clean = line.trim().trimEnd('\r')
            if (clean.isBlank()) return@mapNotNull null
            val parts = clean.split('\t')
            if (parts.size < 6) return@mapNotNull null
            var parsedMessages: List<ChatMessage>? = null
            val body = when {
                // dumpfull：id/title/started/updated/count/rev/gz/base64(gzip(body))
                parts.size >= 8 && parts[6] == "gz" -> try {
                    java.util.zip.GZIPInputStream(
                        Base64.getDecoder().decode(parts[7].trim()).inputStream(),
                    ).use { input ->
                        if (decodeMessages) {
                            parsedMessages = parseSnapshotStream(input)
                            ""
                        } else input.reader(Charsets.UTF_8).readText()
                    }
                } catch (_: Exception) {
                    parsedMessages = null
                    ""
                }
                parts.size >= 7 && parts[6].isNotBlank() -> try {
                    String(Base64.getDecoder().decode(parts[6].trim()), Charsets.UTF_8)
                } catch (_: Exception) { "" }
                else -> ""
            }
            RemoteContext(
                id = parts[0],
                title = parts[1],
                startedAt = parts[2],
                updatedAt = parts[3].toLongOrNull() ?: 0L,
                count = parts[4].toIntOrNull() ?: 0,
                rev = parts[5],
                body = body,
                parsedMessages = parsedMessages,
            )
        }

    /** 文件名安全化（Mac 端 from-phone 目录用）。 */
    fun safeFileName(id: String): String =
        id.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }.joinToString("")
}
