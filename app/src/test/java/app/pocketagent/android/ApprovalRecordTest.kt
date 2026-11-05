package app.pocketagent.android

import app.pocketagent.android.agent.ApprovalRecord
import app.pocketagent.android.agent.MessageMapper
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalRecordTest {

    @Test
    fun `approval records are not replayed to the model`() {
        val messages = listOf(
            ChatMessage(role = Role.USER, text = "看下这个命令"),
            ChatMessage(
                role = Role.ASSISTANT,
                text = """{"outcome":"allow","rationale":"该命令从私有 Release 下载，风险可控"}""",
            ),
            ChatMessage(role = Role.USER, text = "继续"),
        )

        val api = MessageMapper.toApiMessages(messages)

        // 审批 JSON 是内部记录，不该作为模型的上一轮回答回灌
        assertEquals(2, api.size)
        assertTrue(api.none { it.content.contains("\"outcome\"") })
    }

    @Test
    fun `local notices are not replayed to the model`() {
        val messages = listOf(
            ChatMessage(role = Role.USER, text = "帮我看下日志"),
            // appendLocal() 写入的本地提示：只该在手机上显示
            ChatMessage(role = Role.ASSISTANT, text = "已撤回上一轮（含用户输入）。", model = "local"),
            ChatMessage(role = Role.USER, text = "继续"),
        )

        val api = MessageMapper.toApiMessages(messages)

        assertEquals(2, api.size)
        assertTrue(api.none { it.content.contains("已撤回") })
    }

    @Test
    fun `parses desktop approval json`() {
        val raw = """{"outcome":"allow","rationale":"该命令从私有 Release 下载，风险可控"}"""
        val record = ApprovalRecord.parse(raw)
        assertTrue(record != null)
        assertTrue(record!!.allowed)
        assertEquals("该命令从私有 Release 下载，风险可控", record.rationale)
    }

    @Test
    fun `parses deny`() {
        val record = ApprovalRecord.parse("""{"outcome":"deny","rationale":"会删除用户数据"}""")
        assertTrue(record != null)
        assertTrue(!record!!.allowed)
    }

    @Test
    fun `leaves normal answers alone`() {
        assertNull(ApprovalRecord.parse("## 结论\n宇树科技已上市"))
        assertNull(ApprovalRecord.parse("""{"foo":"bar"}"""))
        val bare = ApprovalRecord.parse("""{"outcome":"allow"}""")
        assertTrue(bare != null)
        assertTrue(bare!!.allowed)
        assertEquals("", bare.rationale)
        val classified = ApprovalRecord.parse(
            """{"outcome":"allow","risk_level":"low","user_authorization":"high","rationale":"read only"}""",
        )
        assertTrue(classified != null)
        assertEquals("read only", classified!!.rationale)
        assertNull(ApprovalRecord.parse("""{"outcome":"unknown"}"""))
        assertNull(ApprovalRecord.parse("不是 JSON"))
    }

    @Test
    fun `local user messages such as slash commands are not replayed`() {
        val api = MessageMapper.toApiMessages(
            listOf(
                ChatMessage(role = Role.USER, text = "/model", model = "local"),
                ChatMessage(role = Role.USER, text = "真实问题"),
            ),
        )
        assertEquals(1, api.size)
        assertEquals("真实问题", api.single().content)
    }
}
