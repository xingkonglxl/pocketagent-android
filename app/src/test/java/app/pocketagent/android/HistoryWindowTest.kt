package app.pocketagent.android

import app.pocketagent.android.agent.HistoryWindow
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryWindowTest {

    private fun user(text: String) = ChatMessage(role = Role.USER, text = text)
    private fun bot(text: String) = ChatMessage(role = Role.ASSISTANT, text = text)

    @Test
    fun `keeps only the newest messages`() {
        val messages = (1..100).flatMap { listOf(user("q$it"), bot("a$it")) }
        val trimmed = HistoryWindow.trim(messages, maxMessages = 10, maxTokens = 1_000_000)

        assertEquals(10, trimmed.size)
        assertEquals("a100", trimmed.last().text)
        assertTrue(trimmed.none { it.text == "q1" })
    }

    @Test
    fun `always keeps the last user message`() {
        val messages = listOf(
            user("很早的问题"),
            bot("x".repeat(500)),
            user("最新问题"),
        )
        val trimmed = HistoryWindow.trim(messages, maxMessages = 10, maxTokens = 100)

        assertTrue(trimmed.any { it.text == "最新问题" })
        assertTrue(trimmed.size <= 2)
    }

    @Test
    fun `keeps one oversized message instead of returning empty`() {
        val big = user("x".repeat(200_000))
        val trimmed = HistoryWindow.trim(listOf(big), maxMessages = 40, maxTokens = 10_000)
        assertEquals(1, trimmed.size)
        assertEquals(big.id, trimmed.first().id)
    }

    @Test
    fun `tool results count toward the budget`() {
        val toolCall = ChatMessage(
            role = Role.ASSISTANT,
            text = "结果如下",
            toolCalls = listOf(
                ToolCallRecord("c1", "fs_read", """{"path":"a"}""", ToolStatus.OK, "y".repeat(1000)),
            ),
        )
        val trimmed = HistoryWindow.trim(
            listOf(user("读文件"), toolCall, user("继续")),
            maxMessages = 40,
            maxTokens = 200,
        )
        assertTrue(trimmed.any { it.text == "继续" })
        assertTrue(trimmed.none { it.text == "结果如下" })
    }

    @Test
    fun `a one million token window keeps a long conversation intact`() {
        // deepseek-v4-flash 在本地 codex 目录里是 1,000,000 token 窗口
        val budget = HistoryWindow.budgetFor(1_000_000)
        assertEquals(900_000, budget)

        // 200 轮、每轮约 2.5k token 的会话，旧实现只留 40 条，新实现应该全部保留
        val messages = (1..400).map { i ->
            if (i % 2 == 1) user("问题 $i " + "中".repeat(1_200)) else bot("回答 $i " + "中".repeat(1_200))
        }
        val trimmed = HistoryWindow.trim(messages, maxTokens = budget)
        assertEquals(messages.size, trimmed.size)
        assertEquals(messages.first().id, trimmed.first().id)
    }

    @Test
    fun `system prompt and tool schema reserve space before messages are trimmed`() {
        assertEquals(80_000, HistoryWindow.budgetFor(100_000, reservedTokens = 10_000))
        assertEquals(1_000, HistoryWindow.budgetFor(20_000, reservedTokens = 100_000))
    }

    @Test
    fun `estimation is conservative for chinese text`() {
        assertEquals(10, HistoryWindow.estimateTokens("中".repeat(10)))
        assertEquals(3, HistoryWindow.estimateTokens("abcdefghij"))
    }

    @Test
    fun `request byte budget trims old messages before the relay rejects them`() {
        val messages = (1..10).map { i -> user("$i:" + "中".repeat(1_000)) }
        val trimmed = HistoryWindow.trim(
            messages,
            maxMessages = 100,
            maxTokens = 1_000_000,
            maxBytes = 6_000,
        )

        assertTrue(trimmed.isNotEmpty())
        assertTrue(trimmed.size < messages.size)
        assertEquals(messages.last().id, trimmed.last().id)
    }
}
