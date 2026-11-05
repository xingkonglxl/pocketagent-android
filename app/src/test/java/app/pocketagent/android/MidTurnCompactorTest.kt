package app.pocketagent.android

import app.pocketagent.android.agent.AgentEvent
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.ApprovalGate
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.agent.MidTurnCompactor
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.ApiToolCall
import app.pocketagent.android.net.LlmClient
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.ToolSpec
import app.pocketagent.android.tools.Tool
import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidTurnCompactorTest {
    private fun provider() = Provider(
        name = "test",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://example.invalid/v1",
        model = "test-model",
        maxSteps = 4,
    )

    private fun round(id: String, output: String) = listOf(
        ApiMessage(
            role = "assistant",
            toolCalls = listOf(ApiToolCall("call_$id", "shell_exec", """{"cmd":"$id"}""")),
        ),
        ApiMessage(role = "tool", toolCallId = "call_$id", content = output),
    )

    @Test
    fun `compaction keeps every tool call paired`() {
        val messages = buildList {
            add(ApiMessage(role = "user", content = "整理项目并跑测试"))
            addAll(round("old", "旧结果".repeat(2_000)))
            addAll(round("mid", "中间结果".repeat(2_000)))
            addAll(round("new", "最新结果"))
        }

        val compacted = MidTurnCompactor.compact(messages, budgetTokens = 4_000)

        assertTrue(MidTurnCompactor.isValid(compacted))
        assertTrue(compacted.first().content.contains("整理项目"))
        assertTrue(compacted.any { it.content.startsWith(MidTurnCompactor.SUMMARY_PREFIX) })
        assertTrue(compacted.any { it.role == "tool" && it.toolCallId == "call_new" })
        assertTrue(
            MidTurnCompactor.estimateTokens(compacted) < MidTurnCompactor.estimateTokens(messages),
        )
    }

    @Test
    fun `latest round survives and old round is summarized`() {
        val messages = buildList {
            add(ApiMessage(role = "user", content = "开始"))
            addAll(round("old", "OLD_TOOL_OUTPUT".repeat(1_000)))
            addAll(round("new", "NEW_TOOL_OUTPUT"))
        }

        val compacted = MidTurnCompactor.compact(messages, budgetTokens = 3_000, keepRecentUnits = 1)
        val text = compacted.joinToString("\n") { it.content }

        assertTrue(text.contains("NEW_TOOL_OUTPUT"))
        assertTrue(text.contains("shell_exec"))
        assertTrue(
            compacted.none { it.role == "tool" && it.toolCallId == "call_old" } ||
                compacted.firstOrNull { it.toolCallId == "call_old" }!!.content.length < 1_000,
        )
    }

    @Test
    fun `oversized single tool result is clipped without breaking the round`() {
        val huge = "HEAD" + "x".repeat(80_000) + "TAIL"
        val messages = listOf(
            ApiMessage(role = "user", content = "读一个大文件"),
            ApiMessage(
                role = "assistant",
                toolCalls = listOf(ApiToolCall("call_big", "fs_read", """{"path":"big.log"}""")),
            ),
            ApiMessage(role = "tool", toolCallId = "call_big", content = huge),
        )

        val compacted = MidTurnCompactor.compact(messages, budgetTokens = 8_000)
        val tool = compacted.single { it.role == "tool" }

        assertTrue(MidTurnCompactor.isValid(compacted))
        assertEquals("call_big", tool.toolCallId)
        assertTrue(tool.content.length < huge.length)
        assertTrue(tool.content.contains("中间省略"))
        assertTrue(MidTurnCompactor.estimateTokens(compacted) < 8_000)
    }

    @Test
    fun `only the newest image survives`() {
        val messages = buildList {
            add(ApiMessage(role = "user", content = "看图"))
            add(ApiMessage(role = "assistant", toolCalls = listOf(ApiToolCall("call_a", "view_image", "{}"))))
            add(ApiMessage(role = "tool", toolCallId = "call_a", content = "第一张"))
            add(ApiMessage(role = "user", content = "（上面 view_image 读到的图片）", images = listOf("data:image/png;base64,AAAA")))
            add(ApiMessage(role = "assistant", toolCalls = listOf(ApiToolCall("call_b", "view_image", "{}"))))
            add(ApiMessage(role = "tool", toolCallId = "call_b", content = "第二张"))
            add(ApiMessage(role = "user", content = "（上面 view_image 读到的图片）", images = listOf("data:image/png;base64,BBBB")))
        }

        val compacted = MidTurnCompactor.compact(messages, budgetTokens = 5_000)
        val images = compacted.filter { it.images.isNotEmpty() }

        assertEquals(1, images.size)
        assertEquals("data:image/png;base64,BBBB", images.single().images.single())
        assertTrue(MidTurnCompactor.isValid(compacted))
    }

    @Test
    fun `orphan tool message is never turned into a valid looking request`() {
        val messages = listOf(
            ApiMessage(role = "user", content = "hi"),
            ApiMessage(role = "tool", toolCallId = "missing", content = "orphan"),
        )

        val compacted = MidTurnCompactor.compact(messages, budgetTokens = 1_000)

        assertFalse(MidTurnCompactor.isValid(compacted))
        assertEquals(messages, compacted)
    }

    private class HugeTool : Tool {
        override val spec = ToolSpec("big_read", "读大文件", """{"type":"object"}""")
        var calls = 0
        override suspend fun execute(argumentsJson: String): ToolOutcome {
            calls++
            return ToolOutcome.Ok("BEGIN" + "工具输出".repeat(20_000) + "END")
        }
    }

    private class ScriptedClient : LlmClient {
        var round = 0
        val snapshots = mutableListOf<List<ApiMessage>>()
        override fun stream(
            config: LlmConfig,
            systemPrompt: String,
            messages: List<ApiMessage>,
            tools: List<ToolSpec>,
        ): Flow<LlmEvent> = flow {
            snapshots += messages.toList()
            when (round++) {
                0 -> {
                    emit(LlmEvent.ToolCallStarted(0, "call_big", "big_read"))
                    emit(LlmEvent.ToolCallArguments(0, "{}"))
                    emit(LlmEvent.Finished("tool_calls"))
                }
                else -> {
                    emit(LlmEvent.TextDelta("完成"))
                    emit(LlmEvent.Finished("stop"))
                }
            }
        }
        override suspend fun probe(config: LlmConfig): String? = null
    }

    private object AllowGate : ApprovalGate {
        override suspend fun request(request: ApprovalRequest): Boolean = true
    }

    @Test
    fun `agent runner compacts between complete tool rounds`() = runTest {
        val client = ScriptedClient()
        val runner = AgentRunner(ToolRegistry(listOf(HugeTool())), AllowGate) { client }
        val notices = mutableListOf<String>()

        val result = runner.run(
            provider = provider(),
            apiKey = "test",
            history = listOf(ApiMessage(role = "user", content = "读大文件并总结")),
            historyBudgetTokens = 6_000,
        ) { event ->
            if (event is AgentEvent.Notice) notices += event.text
        }

        assertEquals("完成", result.text)
        assertEquals(2, client.snapshots.size)
        val second = client.snapshots[1]
        assertTrue(MidTurnCompactor.isValid(second))
        assertTrue(second.none { it.role == "tool" && it.content.length > 3_000 })
        assertTrue(notices.any { it.contains("折叠为摘要") })
        assertTrue(MidTurnCompactor.estimateTokens(second) < 6_000)
    }
}
