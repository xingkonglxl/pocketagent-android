package app.pocketagent.android

import app.pocketagent.android.agent.ExportMarkdown
import app.pocketagent.android.agent.SessionMirror
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手机 → 电脑 → 手机：远端文件里的新消息要能并回会话，且不能重复。 */
class SessionMirrorTest {

    private fun md(vararg messages: ChatMessage): String =
        ExportMarkdown.build("测试会话", messages.toList(), "2026-11-03 12:00:00")

    @Test
    fun `parses user and assistant blocks from an exported session`() {
        val text = md(
            ChatMessage(role = Role.USER, text = "帮我看下日志"),
            ChatMessage(role = Role.ASSISTANT, text = "结论：磁盘满了。"),
        )
        val parsed = SessionMirror.parse(text)
        assertEquals(2, parsed.size)
        assertEquals(Role.USER, parsed[0].role)
        assertEquals("帮我看下日志", parsed[0].text)
        assertEquals(Role.ASSISTANT, parsed[1].role)
        assertTrue(parsed[1].text.contains("磁盘满了"))
    }

    @Test
    fun `merge only adds messages the phone does not already have`() {
        val local = listOf(ChatMessage(role = Role.USER, text = "帮我看下日志"))
        val remote = listOf(
            SessionMirror.Parsed(Role.USER, "帮我看下日志"),
            SessionMirror.Parsed(Role.ASSISTANT, "结论：磁盘满了。"),
            SessionMirror.Parsed(Role.USER, "在电脑上继续：清理 /tmp"),
        )
        val (merged, added) = SessionMirror.merge(local, remote)
        assertEquals(2, added)
        assertEquals(3, merged.size)
        assertEquals("在电脑上继续：清理 /tmp", merged.last().text)
    }

    @Test
    fun `duplicate and blank entries are skipped`() {
        val local = listOf(ChatMessage(role = Role.USER, text = "同一句话"))
        val remote = listOf(
            SessionMirror.Parsed(Role.USER, "同一句话"),
            SessionMirror.Parsed(Role.USER, "   "),
            SessionMirror.Parsed(Role.USER, "另一句话"),
        )
        val (merged, added) = SessionMirror.merge(local, remote)
        assertEquals(1, added)
        assertEquals(2, merged.size)
    }

    @Test
    fun `remote-only content is detected before the phone overwrites the mirror`() {
        val local = listOf(
            ChatMessage(role = Role.USER, text = "帮我看下日志"),
            ChatMessage(role = Role.ASSISTANT, text = "结论：磁盘满了。"),
        )
        val remote = SessionMirror.parse(
            md(
                ChatMessage(role = Role.USER, text = "帮我看下日志"),
                ChatMessage(role = Role.ASSISTANT, text = "结论：磁盘满了。"),
                ChatMessage(role = Role.USER, text = "在电脑上继续：清理 /tmp"),
            ),
        )
        assertTrue(SessionMirror.hasRemoteOnly(local, remote))
    }

    @Test
    fun `same content on both sides does not block the mirror write`() {
        val local = listOf(
            ChatMessage(role = Role.USER, text = "帮我看下日志"),
            ChatMessage(role = Role.ASSISTANT, text = "结论：磁盘满了。"),
        )
        val remote = SessionMirror.parse(
            md(
                ChatMessage(role = Role.USER, text = "帮我看下日志"),
                ChatMessage(role = Role.ASSISTANT, text = "结论：磁盘满了。"),
            ),
        )
        assertTrue(!SessionMirror.hasRemoteOnly(local, remote))
    }
}
