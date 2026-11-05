package app.pocketagent.android

import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.ApprovalGate
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.agent.ChatEngine
import app.pocketagent.android.agent.MessageMapper
import app.pocketagent.android.agent.decodeReplyText
import app.pocketagent.android.agent.encodeReplyText
import app.pocketagent.android.agent.isAutomaticLimitReply
import app.pocketagent.android.agent.isLegacyAutomaticLimitReply
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.AppJson
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.LlmClient
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.ToolSpec
import app.pocketagent.android.tools.FsWriteTool
import app.pocketagent.android.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class TwoRoundClient : LlmClient {
    val reasoning = """{"type":"reasoning","id":"rs_engine","status":"completed","content":[],"summary":[],"encrypted_content":"opaque"}"""
    var round = 0
    override fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        if (round++ == 0) {
            emit(LlmEvent.TextDelta("先查"))
            emit(LlmEvent.ToolCallStarted(0, "call_1", "fs_write"))
            emit(LlmEvent.ToolCallArguments(0, """{"path":"a.txt","content":"x"}"""))
        } else {
            emit(LlmEvent.ReasoningItem(reasoning))
            emit(LlmEvent.ReasoningDelta("先看日志"))
            emit(LlmEvent.ReasoningDelta("，再下结论"))
            emit(LlmEvent.TextDelta("好"))
            emit(LlmEvent.Usage(3, 4))
        }
    }

    override suspend fun probe(config: LlmConfig): String? = null
}

class ChatEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = Provider(
        name = "t",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://example.com",
        model = "deepseek-chat",
    )

    @Test
    fun `engine returns one assistant message with tool records and stable id`() = runTest {
        val root = tmp.newFolder()
        val client = TwoRoundClient()
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest) = true
        }
        val engine = ChatEngine(AgentRunner(ToolRegistry(listOf(FsWriteTool(root))), gate) { client })

        val ids = mutableSetOf<String>()
        val final = engine.runOnce(provider, "k", listOf(ChatMessage(role = Role.USER, text = "写"))) { live ->
            ids += live.id
        }

        assertEquals(1, ids.size)
        assertEquals(final.id, ids.first())
        // 工具调用前那一步的说明属于“过程”，不能混进最终答案；
        // 最终正文只保留最后一步输出，否则回复会变成 过程+过程+结论 的流水账。
        assertEquals("好", final.text)
        assertEquals(0, final.text.split("先查").size - 1)
        assertEquals(1, final.toolCalls.size)
        assertEquals(ToolStatus.OK, final.toolCalls.first().status)
        assertEquals(3, final.tokensIn)
        assertEquals(4, final.tokensOut)
        assertTrue(final.reasoning.contains("先查"))
        assertTrue(final.reasoning.contains("先看日志，再下结论"))
        assertEquals(client.reasoning, final.reasoningItem)
    }

    @Test
    fun `history keeps tool results in order`() {
        val reasoning = """{"type":"reasoning","id":"rs_1","status":"completed","content":[],"summary":[],"encrypted_content":"opaque"}"""
        val history = listOf(
            ChatMessage(role = Role.USER, text = "hi"),
            ChatMessage(
                role = Role.ASSISTANT,
                text = "看一下",
                reasoningItem = reasoning,
                toolCalls = listOf(
                    ToolCallRecord("c1", "fs_read", """{"path":"a"}""", ToolStatus.OK, "内容"),
                ),
            ),
        )
        val api = MessageMapper.toApiMessages(history)
        assertEquals(listOf("user", "assistant", "tool"), api.map { it.role })
        assertEquals("c1", api[2].toolCallId)
        assertEquals("内容", api[2].content)
        assertEquals("fs_read", api[1].toolCalls.first().name)
        assertEquals(reasoning, api[1].reasoningItem)
    }

    @Test
    fun `messages without content are skipped`() {
        val api = MessageMapper.toApiMessages(
            listOf(
                ChatMessage(role = Role.ASSISTANT, text = ""),
                ChatMessage(role = Role.TOOL, text = "ignored"),
            )
        )
        assertTrue(api.isEmpty())
    }

    @Test
    fun `interrupted tool calls still get a paired result for deepseek history`() {
        val api = MessageMapper.toApiMessages(
            listOf(
                ChatMessage(role = Role.USER, text = "开始"),
                ChatMessage(
                    role = Role.ASSISTANT,
                    text = "",
                    toolCalls = listOf(
                        ToolCallRecord("c1", "fs_read", """{"path":"a"}""", ToolStatus.RUNNING, null),
                    ),
                ),
            ),
        )

        assertEquals(listOf("user", "assistant", "tool"), api.map { it.role })
        assertEquals("c1", api.last().toolCallId)
        assertTrue(api.last().content.contains("no recorded result"))
    }

    @Test
    fun `old conversation json still decodes with no reasoning item`() {
        val reasoning = """{"type":"reasoning","id":"rs_1","status":"completed","content":[],"summary":[],"encrypted_content":"opaque"}"""
        val old = """{"id":"m1","role":"USER","text":"你好"}"""
        val message = AppJson.decodeFromString(ChatMessage.serializer(), old)

        assertEquals("m1", message.id)
        assertEquals("你好", message.text)
        assertNull(message.reasoningItem)

        val encoded = AppJson.encodeToString(
            ChatMessage.serializer(),
            message.copy(role = Role.ASSISTANT, text = "答", reasoningItem = reasoning),
        )
        val restored = AppJson.decodeFromString(ChatMessage.serializer(), encoded)
        assertEquals(reasoning, restored.reasoningItem)
    }

    @Test
    fun `step limit is a notice rather than a user stop or assistant body`() = runTest {
        val root = tmp.newFolder()
        val client = object : LlmClient {
            override fun stream(
                config: LlmConfig,
                systemPrompt: String,
                messages: List<ApiMessage>,
                tools: List<ToolSpec>,
            ): Flow<LlmEvent> = flow {
                emit(LlmEvent.ToolCallStarted(0, "call_loop", "fs_write"))
                emit(LlmEvent.ToolCallArguments(0, """{"path":"loop.txt","content":"x"}"""))
                emit(LlmEvent.Finished("tool_calls"))
            }

            override suspend fun probe(config: LlmConfig): String? = null
        }
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest) = true
        }
        val engine = ChatEngine(AgentRunner(ToolRegistry(listOf(FsWriteTool(root))), gate) { client })

        val final = engine.runOnce(
            provider.copy(maxSteps = 1),
            "k",
            listOf(ChatMessage(role = Role.USER, text = "一直写")),
        ) { }

        assertFalse(final.stopped)
        assertFalse(final.text.isBlank())

        val rendered = decodeReplyText(final.text)
        assertFalse(rendered.body.contains("最大步数"))
        assertTrue(rendered.notices.any { it.contains("最大步数") })
        assertTrue(isAutomaticLimitReply(final.text))
        assertTrue(isAutomaticLimitReply("正文\n\n> 已达到最大步数（24），正在基于已有结果收尾"))
        assertFalse(isLegacyAutomaticLimitReply(final.text))
        assertTrue(isLegacyAutomaticLimitReply("正文\n\n> 已达到最大步数（24），正在基于已有结果收尾"))

        // 自动收尾提示不能作为上一轮 assistant 正文回灌给模型。
        assertTrue(MessageMapper.toApiMessages(listOf(final.copy(toolCalls = emptyList()))).isEmpty())
        val withBody = ChatMessage(
            role = Role.ASSISTANT,
            text = encodeReplyText("正文", listOf("运行状态")),
        )
        assertEquals("正文", MessageMapper.toApiMessages(listOf(withBody)).single().content)
    }
}
