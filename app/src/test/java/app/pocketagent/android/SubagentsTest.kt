package app.pocketagent.android

import app.pocketagent.android.agent.AgentEvent
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.ApprovalGate
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.agent.Subagents
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ApiMessage
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class SubagentClient(private val rounds: List<List<LlmEvent>>) : LlmClient {
    var round = 0
    val toolLists = mutableListOf<List<ToolSpec>>()
    val messageLists = mutableListOf<List<ApiMessage>>()
    val systemPrompts = mutableListOf<String>()

    override fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        toolLists += tools
        messageLists += messages
        systemPrompts += systemPrompt
        rounds.getOrElse(round++) { emptyList() }.forEach { emit(it) }
    }

    override suspend fun probe(config: LlmConfig): String? = null
}

private class SubagentGate(private val allow: Boolean = true) : ApprovalGate {
    val requests = mutableListOf<ApprovalRequest>()
    override suspend fun request(request: ApprovalRequest): Boolean {
        requests += request
        return allow
    }
}

private class FixedTool(
    override val spec: ToolSpec,
    private val result: ToolOutcome,
) : Tool {
    var executions = 0
    override suspend fun execute(argumentsJson: String): ToolOutcome {
        executions += 1
        return result
    }
}

class SubagentsTest {

    private val provider = Provider(
        name = "测试",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://example.com/v1",
        model = "demo",
        maxSteps = 4,
        toolsEnabled = true,
    )

    @Test
    fun `parse requires a task and keeps context`() {
        assertNull(Subagents.parse("""{"context":"只有背景"}"""))
        assertNull(Subagents.parse("not-json"))
        val call = Subagents.parse("""{"task":" 看下 README ","context":" 工作区根目录 "}""")
        assertNotNull(call)
        assertEquals("看下 README", call!!.task)
        assertEquals("工作区根目录", call.context)
        val prompt = Subagents.prompt(call)
        assertTrue(prompt.contains("看下 README"))
        assertTrue(prompt.contains("工作区根目录"))
    }

    @Test
    fun `read only tool set never contains mutating tools`() {
        listOf(
            "fs_write", "fs_edit", "fs_delete", "apply_patch",
            "shell_exec", "exec_command", "write_stdin", "ssh_exec", "ssh_write",
            "remote_codex", Subagents.TOOL_NAME,
        ).forEach { name ->
            assertFalse("$name 不能出现在子 agent 工具集里", name in Subagents.readOnlyTools)
        }
        assertTrue("fs_read" in Subagents.readOnlyTools)
        assertTrue("web_search" in Subagents.readOnlyTools)
    }

    @Test
    fun `clip bounds the report`() {
        val long = "x".repeat(Subagents.MAX_RESULT_CHARS + 500)
        val clipped = Subagents.clip(long)
        assertTrue(clipped.length < long.length)
        assertTrue(clipped.contains("已截断"))
    }

    @Test
    fun `delegate runs a read-only subagent and feeds its report back`() = runTest {
        val client = SubagentClient(
            listOf(
                // 1. 主 agent 要求调研
                listOf(
                    LlmEvent.ToolCallStarted(0, "call_delegate", Subagents.TOOL_NAME),
                    LlmEvent.ToolCallArguments(0, """{"task":"看下 a.txt 写了什么"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                // 2. 子 agent 读文件
                listOf(
                    LlmEvent.ToolCallStarted(0, "call_sub", "fs_read"),
                    LlmEvent.ToolCallArguments(0, """{"path":"a.txt"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                // 3. 子 agent 给结论
                listOf(
                    LlmEvent.TextDelta("findings: a.txt 里是 hi"),
                    LlmEvent.Usage(100, 20),
                    LlmEvent.Finished("stop"),
                ),
                // 4. 主 agent 收尾
                listOf(
                    LlmEvent.TextDelta("结论：a.txt 里是 hi"),
                    LlmEvent.Usage(10, 5),
                    LlmEvent.Finished("stop"),
                ),
            ),
        )
        val readTool = FixedTool(
            ToolSpec("fs_read", "读取文件", """{"type":"object"}"""),
            ToolOutcome.Ok("hi"),
        )
        val writeTool = FixedTool(
            ToolSpec("fs_write", "写文件", """{"type":"object"}"""),
            ToolOutcome.Ok("ok"),
        )
        val registry = ToolRegistry(listOf(readTool, writeTool))
        val gate = SubagentGate()
        val runner = AgentRunner(registry, gate) { client }

        val events = mutableListOf<AgentEvent>()
        val result = runner.run(
            provider = provider,
            apiKey = "k",
            history = listOf(ApiMessage(role = "user", content = "帮我看看 a.txt")),
            allowSubagents = true,
            onEvent = { events += it },
        )

        assertEquals("结论：a.txt 里是 hi", result.text)
        // 子 agent 的 token 也要算进本轮账，/context 才不会低估
        assertEquals(110, result.tokensIn)
        assertEquals(25, result.tokensOut)
        assertEquals(1, readTool.executions)
        assertEquals(0, writeTool.executions)
        // 主 agent 的 schema 里有 delegate_task
        assertTrue(client.toolLists[0].any { it.name == Subagents.TOOL_NAME })
        // 子 agent 只有只读工具，不能再开子 agent
        val childTools = client.toolLists[1].map { it.name }
        assertEquals(listOf("fs_read"), childTools)
        assertTrue(client.systemPrompts[1].contains("read-only subagent"))
        // 主 agent 收到的工具结果包含子 agent 结论
        val fedBack = client.messageLists[3].last { it.role == "tool" }.content
        assertTrue(fedBack.contains("a.txt 里是 hi"))
        // 只读委托不需要人工审批
        assertTrue(gate.requests.isEmpty())
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.name == Subagents.TOOL_NAME && it.ok })
    }

    @Test
    fun `delegate asks the gate when read permission is off`() = runTest {
        val client = SubagentClient(
            listOf(
                listOf(
                    LlmEvent.ToolCallStarted(0, "call_delegate", Subagents.TOOL_NAME),
                    LlmEvent.ToolCallArguments(0, """{"task":"调研"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                listOf(LlmEvent.TextDelta("不会执行到这里"), LlmEvent.Finished("stop")),
            ),
        )
        val gate = SubagentGate(allow = false)
        val runner = AgentRunner(ToolRegistry(listOf(FixedTool(
            ToolSpec("fs_read", "读取文件", """{"type":"object"}"""),
            ToolOutcome.Ok("hi"),
        ))), gate) { client }

        val result = runner.run(
            provider = provider.copy(
                permissions = emptySet(),
                autoApprove = false,
            ),
            apiKey = "k",
            history = listOf(ApiMessage(role = "user", content = "调研一下")),
            allowSubagents = true,
            onEvent = {},
        )
        assertEquals(1, gate.requests.size)
        assertEquals(Subagents.TOOL_NAME, gate.requests[0].toolName)
        assertFalse(gate.requests[0].sessionAllowable)
        assertTrue(result.text.contains("不会执行到这里"))
    }

    @Test
    fun `subagents are not advertised when disabled`() = runTest {
        val client = SubagentClient(listOf(listOf(LlmEvent.TextDelta("ok"), LlmEvent.Finished("stop"))))
        val runner = AgentRunner(
            ToolRegistry(listOf(FixedTool(ToolSpec("fs_read", "读", """{"type":"object"}"""), ToolOutcome.Ok("x")))),
            SubagentGate(),
        ) { client }
        runner.run(
            provider = provider,
            apiKey = "k",
            history = listOf(ApiMessage(role = "user", content = "hi")),
            onEvent = {},
        )
        assertTrue(client.toolLists.single().none { it.name == Subagents.TOOL_NAME })
    }
}
