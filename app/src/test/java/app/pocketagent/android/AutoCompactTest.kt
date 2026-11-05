package app.pocketagent.android

import app.pocketagent.android.agent.HistoryWindow
import app.pocketagent.android.agent.MessageMapper
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role
import app.pocketagent.android.ui.chat.buildAutoCompactHistory
import app.pocketagent.android.ui.chat.compactSummaryMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCompactTest {

    @Test
    fun `manual compact summary is sent back to the model`() {
        val summary = compactSummaryMessage(
            summary = "- 已完成认证改造\n- 下一步补测试",
            oldMessageCount = 42,
            model = "deepseek-v4-flash",
        )

        assertEquals(Role.USER, summary.role)
        assertEquals("deepseek-v4-flash", summary.model)
        assertTrue(summary.text.contains("前面 42 条对话"))
        val api = MessageMapper.toApiMessages(listOf(summary))
        assertEquals(1, api.size)
        assertTrue(api.single().content.contains("下一步补测试"))
    }

    @Test
    fun `auto compaction produces a summary followed by the newest six messages`() = runTest {
        val messages = (1..20).map { index ->
            ChatMessage(
                id = "message-$index",
                role = if (index % 2 == 1) Role.USER else Role.ASSISTANT,
                text = buildString {
                    if (index == 1) append("旧决策 ALPHA：认证必须保留。")
                    if (index == 14) append("旧决策 BETA：不要改用真实密钥。")
                    append("第 $index 轮对话。")
                    append("上下文".repeat(500))
                },
            )
        }

        val smallBudget = HistoryWindow.trim(messages, maxTokens = 5_000)
        assertTrue("测试历史必须真的超过预算", smallBudget.size < messages.size)

        val summaryInputs = mutableListOf<String>()
        val compacted = buildAutoCompactHistory(messages, model = "test-model") { transcript ->
            summaryInputs += transcript
            buildString {
                // 两段式摘要：分段时看原文关键字，合并时只能看到上一段摘要，所以两处都要认。
                if (transcript.contains("ALPHA") || transcript.contains("认证必须保留")) {
                    appendLine("- 认证必须保留。")
                }
                if (transcript.contains("BETA") || transcript.contains("不要改用真实密钥")) {
                    appendLine("- 不要改用真实密钥。")
                }
            }.trim().ifBlank { "- 无新增要点。" }
        }

        assertNotNull(compacted)
        val result = requireNotNull(compacted)
        assertEquals(7, result.size)
        assertEquals(Role.USER, result.first().role)
        assertEquals("test-model", result.first().model)
        assertNotEquals(app.pocketagent.android.agent.LOCAL_NOTICE_MODEL, result.first().model)
        assertTrue(result.first().text.contains("前面 14 条对话的压缩摘要"))
        assertTrue(result.first().text.contains("认证必须保留"))
        assertTrue(result.first().text.contains("不要改用真实密钥"))
        assertEquals(
            messages.takeLast(6).map { it.id },
            result.drop(1).map { it.id },
        )
        assertTrue("超长历史应触发分段摘要", summaryInputs.size > 1)

        // 摘要消息必须进入 API 历史，不能因为被标成本地提示而过滤掉。
        val api = MessageMapper.toApiMessages(result)
        assertEquals("user", api.first().role)
        assertTrue(api.first().content.contains("认证必须保留"))
    }
}
