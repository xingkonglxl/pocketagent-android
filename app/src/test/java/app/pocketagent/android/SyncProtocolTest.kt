package app.pocketagent.android

import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import app.pocketagent.android.sync.SyncProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream

class SyncProtocolTest {

    private fun conversation() = Conversation(
        id = "abc-123",
        title = "改一下同步",
        messages = listOf(
            ChatMessage(role = Role.USER, text = "帮我看下 /sync"),
            ChatMessage(role = Role.ASSISTANT, text = "好的，先看协议。"),
        ),
    )

    @Test
    fun `render then parse keeps messages in order`() {
        val conv = conversation()
        val body = SyncProtocol.render(conv)
        assertTrue(body.startsWith(SyncProtocol.MAGIC_TITLE))

        val parsed = SyncProtocol.parseMessages(body)
        assertEquals(2, parsed.size)
        assertEquals(Role.USER, parsed[0].role)
        assertEquals("帮我看下 /sync", parsed[0].text)
        assertEquals(Role.ASSISTANT, parsed[1].role)
        assertEquals("好的，先看协议。", parsed[1].text)
    }

    @Test
    fun `round trip keeps the same rev`() {
        val conv = conversation()
        val parsed = SyncProtocol.parseMessages(SyncProtocol.render(conv))
        assertEquals(SyncProtocol.revOf(conv.messages), SyncProtocol.revOf(parsed))
    }

    @Test fun `unclosed code blocks literal delimiters and emoji do not swallow later messages`() {
        val first = "中文😀\r\n```md\n## assistant <!-- pocketagent-role:assistant -->\n" +
            "<!-- pocketagent-message-end -->\n<!-- pocketagent-text-bytes:3 -->\n仍然是正文\n\n"
        val conv = conversation().copy(messages = listOf(
            ChatMessage(role = Role.USER, text = first),
            ChatMessage(role = Role.ASSISTANT, text = "~~~python\nprint('unclosed')"),
            ChatMessage(role = Role.USER, text = "下一条消息"),
        ))
        val parsed = SyncProtocol.parseMessages(SyncProtocol.render(conv))
        assertEquals(conv.messages.map { it.text }, parsed.map { it.text })
        assertEquals(SyncProtocol.revOf(conv.messages), SyncProtocol.revOf(parsed))
    }

    @Test fun `truncated framed document is rejected instead of returning a prefix`() {
        val body = SyncProtocol.render(conversation())
        assertTrue(SyncProtocol.parseMessages(body.dropLast(30)).isEmpty())
    }

    @Test fun `character based framing honors all UTF8 widths and rejects partial characters`() {
        val text = "\u0000\u007F\u0080\u07FF\u0800\uD7FF\uE000\uFFFF😀\uDBFF\uDFFF\r\n"
        val conv = conversation().copy(messages = listOf(ChatMessage(role = Role.USER, text = text)))
        val body = SyncProtocol.render(conv)
        assertEquals(text, SyncProtocol.parseMessages(body).single().text)
        val emoji = SyncProtocol.render(conv.copy(messages = listOf(ChatMessage(role = Role.USER, text = "😀"))))
        listOf("-1", "1", "2", "3", "10000000000").forEach { invalid ->
            assertTrue(SyncProtocol.parseMessages(emoji.replace("pocketagent-text-bytes:4", "pocketagent-text-bytes:$invalid")).isEmpty())
        }
    }

    @Test
    fun `rev changes when content changes`() {
        val a = conversation()
        val b = a.copy(messages = a.messages + ChatMessage(role = Role.USER, text = "再来一条"))
        assertTrue(SyncProtocol.revOf(a.messages) != SyncProtocol.revOf(b.messages))
    }

    @Test
    fun `parse dump handles tabs in title and base64 body`() {
        val body = "# Codex 会话同步\n\n## user\n你好"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray())
        val line = "id-1\t标题\t2026-10-30T10:00:00\t1760000000000\t2\tabcdef\t$encoded"
        val parsed = SyncProtocol.parseDump(line)
        assertEquals(1, parsed.size)
        assertEquals("id-1", parsed[0].id)
        assertEquals("标题", parsed[0].title)
        assertEquals(1760000000000L, parsed[0].updatedAt)
        assertEquals(2, parsed[0].count)
        assertEquals(body, parsed[0].body)
    }

    @Test
    fun `parse dump ignores broken lines`() {
        assertTrue(SyncProtocol.parseDump("nonsense\n\nid\ttoo-few\tfields").isEmpty())
    }

    @Test
    fun `remote context becomes its own conversation with title`() {
        val body = "# Codex 会话同步\n\n## user\n第一个问题\n\n## assistant\n回答"
        val ctx = app.pocketagent.android.sync.RemoteContext(
            id = "s-1",
            title = "",
            rev = "rev",
            body = body,
            updatedAt = 1234L,
        )
        val conv = SyncProtocol.toConversation(ctx)
        assertEquals("s-1", conv.id)
        assertEquals("第一个问题", conv.title)
        assertEquals("mac", conv.origin)
        assertEquals(2, conv.messages.size)
    }

    @Test
    fun `phone context carries rev and body`() {
        val conv = conversation()
        val ctx = SyncProtocol.toPhoneContext(conv)
        assertEquals(conv.id, ctx.id)
        assertEquals(SyncProtocol.revOf(conv.messages), ctx.rev)
        assertEquals(2, ctx.count)
        assertEquals(conv.title, ctx.title)
    }

    @Test
    fun `long reply with table and role-like code fence is not split or truncated`() {
        val text = buildString {
            appendLine("结论：完整回复如下。")
            appendLine()
            appendLine("## user")
            appendLine("这是正文里的二级标题，不是新的消息。")
            appendLine()
            appendLine("| 项目 | 数值 |")
            appendLine("|---|---:|")
            appendLine("| 行数 | 100000 |")
            appendLine()
            appendLine("```text")
            appendLine("## user")
            appendLine("这一行在代码块里，不能被当成消息边界")
            appendLine("```")
            append("长回复".repeat(20_000))
        }.trimEnd('\n')
        val conv = Conversation(
            id = "long",
            title = "长回复",
            messages = listOf(ChatMessage(role = Role.ASSISTANT, text = text)),
        )

        val parsed = SyncProtocol.parseMessages(SyncProtocol.render(conv))
        assertEquals(1, parsed.size)
        assertEquals(text, parsed.single().text)
        assertEquals(SyncProtocol.revOf(conv.messages), SyncProtocol.revOf(parsed))
    }

    @Test
    fun `tool calls reasoning and message identity survive phone round trip`() {
        val calls = listOf(
            ToolCallRecord(
                id = "call-1",
                name = "fs_read",
                arguments = """{"path":"big.log"}""",
                status = ToolStatus.OK,
                result = "第一行\n" + "完整工具输出".repeat(20_000),
                durationMs = 321,
            ),
        )
        val conv = Conversation(
            id = "tools",
            title = "工具",
            messages = listOf(
                ChatMessage(
                    id = "assistant-1",
                    role = Role.ASSISTANT,
                    text = "读完了，结论见上。",
                    reasoning = "先看文件大小，再分段读取。",
                    reasoningItem = """{"type":"reasoning","id":"rs_9","encrypted_content":"op"}""",
                    toolCalls = calls,
                    tokensIn = 12,
                    tokensOut = 34,
                ),
            ),
        )

        val ctx = SyncProtocol.toPhoneContext(conv)
        val parsed = SyncProtocol.parseMessages(ctx.body).single()
        assertEquals("assistant-1", parsed.id)
        assertEquals("先看文件大小，再分段读取。", parsed.reasoning)
        assertEquals(
            """{"type":"reasoning","id":"rs_9","encrypted_content":"op"}""",
            parsed.reasoningItem,
        )
        assertEquals(calls, parsed.toolCalls)
        assertEquals(12, parsed.tokensIn)
        assertEquals(34, parsed.tokensOut)
        assertEquals(1, ctx.count)
    }

    @Test
    fun `trailing blank lines in a reply are preserved`() {
        val text = "正文\n\n\n"
        val conv = Conversation(
            id = "trailing",
            messages = listOf(ChatMessage(role = Role.ASSISTANT, text = text)),
        )
        assertEquals(text, SyncProtocol.parseMessages(SyncProtocol.render(conv)).single().text)
    }

    @Test
    fun `rev changes when only a tool result changes`() {
        val call = ToolCallRecord("c", "fs_read", "{}", ToolStatus.OK, "old")
        val a = ChatMessage(role = Role.ASSISTANT, text = "结论", toolCalls = listOf(call))
        val b = a.copy(toolCalls = listOf(call.copy(result = "new")))
        assertTrue(SyncProtocol.revOf(listOf(a)) != SyncProtocol.revOf(listOf(b)))
    }

    @Test
    fun `plain text revision stays compatible with the previous algorithm`() {
        val messages = conversation().messages
        val legacy = SyncProtocol.revOfText(messages.joinToString("\u0001") {
            "${it.role.name}\u0002${it.text}"
        })
        // 升级后第一次同步不能把这些历史纯文本会话全部误判成本机改动再推回 Mac。
        assertEquals(legacy, SyncProtocol.revOf(messages))
    }

    @Test fun `streamed revisions match legacy bytes across unicode and encoder boundaries`() {
        val texts = listOf("", "中文😀\u0001\u0002", "a".repeat(8191) + "😀中", "\uD800x\uDC00", "尾\uD800")
        fun oldHash(text: String): String = MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
        texts.forEach { text -> assertEquals(oldHash(text), SyncProtocol.revOfText(text)) }
        val messages = texts.mapIndexed { index, text ->
            ChatMessage(role = if (index % 2 == 0) Role.USER else Role.ASSISTANT, text = text)
        } + ChatMessage(role = Role.TOOL, text = "不参与版本计算")
        val joined = messages.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .joinToString("\u0001") { "${it.role.name}\u0002${it.text}" }
        assertEquals(oldHash(joined), SyncProtocol.revOf(messages))
        assertEquals(oldHash(""), SyncProtocol.revOf(emptyList()))
    }

    @Test fun `streamed structured revision preserves length prefixes and full tool results`() {
        val call = ToolCallRecord("call", "fs_read", "{}", ToolStatus.OK, "中😀".repeat(5000), 42)
        val message = ChatMessage(role = Role.ASSISTANT, text = "正文", reasoning = "\uD800", toolCalls = listOf(call))
        val fields = listOf(message.role.name, message.text, message.reasoning, "0", "1",
            call.id, call.name, call.arguments, call.status.name, call.result.orEmpty(), call.durationMs.toString())
        val bytes = ByteArrayOutputStream().apply {
            fields.forEach { field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                write("${encoded.size}:".toByteArray(Charsets.US_ASCII))
                write(encoded)
                write('\n'.code)
            }
        }.toByteArray()
        val expected = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)
        assertEquals(expected, SyncProtocol.revOf(listOf(message)))
    }

    @Test
    fun `dumpfull keeps a megabyte body`() {
        val body = "# Codex 会话同步\n\n## assistant\n" + "完整正文".repeat(200_000)
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(body.toByteArray()) }
        }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(compressed)
        val line = "id-big\t百万字\t2026-10-30T10:00:00\t1760000000000\t1\trev\tgz\t$encoded"

        val parsed = SyncProtocol.parseDump(line).single()
        assertEquals(body, parsed.body)
        assertEquals(1, SyncProtocol.parseMessages(parsed.body).size)
    }

    private fun compressedDump(body: String, corruptTrailer: Boolean = false): String {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        if (corruptTrailer) compressed[compressed.size - 8] = (compressed[compressed.size - 8].toInt() xor 1).toByte()
        return "streamed\t测试\t2026-10-30T10:00:00\t1760000000000\t2\trev\tgz\t" +
            Base64.getEncoder().encodeToString(compressed)
    }

    @Test fun `gzip frames stream full unicode text and rich metadata without retaining markdown`() {
        val call = ToolCallRecord("call", "fs_read", "{}", ToolStatus.OK, "工具结果😀".repeat(20_000), 12)
        val conv = conversation().copy(messages = listOf(
            ChatMessage(role = Role.USER, text = "\u0000\u007F\u0080\u07FF\u0800\uFFFF😀\uDBFF\uDFFF\r\n" +
                "中文😀\n```md\n## assistant <!-- pocketagent-role:assistant -->\n" +
                "<!-- pocketagent-message-end -->\n".repeat(4000)),
            ChatMessage(id = "rich", role = Role.ASSISTANT, text = "", reasoning = "思考",
                reasoningItem = "{\"type\":\"reasoning\"}", toolCalls = listOf(call), stopped = true,
                tokensIn = 12, tokensOut = 34, model = "test-model"),
        ))
        val body = SyncProtocol.render(conv)
        val context = SyncProtocol.parseDumpSequence(compressedDump(body), decodeMessages = true).single()
        val streamed = SyncProtocol.toConversation(context).messages
        val regular = SyncProtocol.parseMessages(body)
        assertEquals("", context.body)
        assertEquals(conv.messages.map { it.text }, streamed.map { it.text })
        assertEquals(regular[1], streamed[1])
        assertEquals(SyncProtocol.revOf(conv.messages), SyncProtocol.revOf(streamed))
        assertEquals(body, SyncProtocol.parseDump(compressedDump(body)).single().body)
    }

    @Test fun `gzip streaming supports legacy documents`() {
        val body = "# Codex 会话同步\n\n## user\n旧格式😀\n\n## assistant\n完整回答\n"
        val context = SyncProtocol.parseDumpSequence(compressedDump(body), decodeMessages = true).single()
        assertEquals("", context.body)
        assertEquals(listOf("旧格式😀", "完整回答"), SyncProtocol.toConversation(context).messages.map { it.text })
    }

    @Test fun `broken gzip frames reject the whole snapshot and never accept a message prefix`() {
        val conv = conversation().copy(messages = listOf(
            ChatMessage(role = Role.USER, text = "first"),
            ChatMessage(role = Role.ASSISTANT, text = "😀"),
        ))
        val body = SyncProtocol.render(conv)
        val malformed = listOf(
            body.dropLast(30),
            body + "unexpected trailing content\n",
            body.replace("pocketagent-text-bytes:4", "pocketagent-text-bytes:-1"),
            body.replace("pocketagent-text-bytes:4", "pocketagent-text-bytes:3"),
            body.replace("pocketagent-text-bytes:4", "pocketagent-text-bytes:9999999999"),
            body.replace("pocketagent-text-bytes:4", "pocketagent-message-v1:broken -->\n<!-- pocketagent-text-bytes:4"),
        )
        (malformed.map { compressedDump(it) } + compressedDump(body, corruptTrailer = true)).forEach { dump ->
            val context = SyncProtocol.parseDumpSequence(dump, decodeMessages = true).single()
            assertTrue(context.parsedMessages == null)
            assertEquals("", context.body)
            assertTrue(SyncProtocol.toConversation(context).messages.isEmpty())
        }
        // 同一批里下一组有效会话仍能独立读取，不能沿用失败组的消息前缀。
        val contexts = SyncProtocol.parseDumpSequence(
            compressedDump(malformed.first()) + "\n" + compressedDump(body), decodeMessages = true,
        ).toList()
        assertTrue(contexts.first().parsedMessages == null)
        assertEquals(conv.messages.map { it.text }, contexts.last().parsedMessages!!.map { it.text })
    }

    @Test
    fun `file name is sanitized`() {
        assertEquals("a_b_c", SyncProtocol.safeFileName("a:b/c"))
    }
}
