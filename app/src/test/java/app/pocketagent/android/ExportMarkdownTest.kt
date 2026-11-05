package app.pocketagent.android

import app.pocketagent.android.agent.ExportMarkdown
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMarkdownTest {

    @Test
    fun `export keeps tool arguments results and reasoning`() {
        val messages = listOf(
            ChatMessage(role = Role.USER, text = "看一下日志"),
            ChatMessage(
                role = Role.ASSISTANT,
                text = "结论：磁盘满了。",
                reasoning = "先看 df，再看日志尾部。",
                model = "deepseek-v4-flash",
                tokensIn = 120,
                tokensOut = 30,
                toolCalls = listOf(
                    ToolCallRecord(
                        id = "c1",
                        name = "ssh_exec",
                        arguments = """{"command":"df -h"}""",
                        status = ToolStatus.OK,
                        result = "/dev/disk1 100%",
                        durationMs = 42,
                    ),
                ),
            ),
        )

        val md = ExportMarkdown.build("磁盘排查", messages, "2026-11-01 20:00:00")

        assertTrue(md.contains("# 磁盘排查"))
        assertTrue(md.contains("先看 df，再看日志尾部"))
        assertTrue(md.contains("""{"command":"df -h"}"""))
        assertTrue(md.contains("/dev/disk1 100%"))
        assertTrue(md.contains("deepseek-v4-flash"))
        assertTrue(md.contains("in 120"))
    }

    @Test
    fun `fence grows when content has backticks`() {
        val msg = ChatMessage(role = Role.ASSISTANT, text = "ok", reasoning = "```inner```")
        val md = ExportMarkdown.build("t", listOf(msg), "now")
        // 内容里有三个反引号时，围栏必须更长，否则 markdown 会被截断
        assertTrue(md.contains("````"))
    }
}
