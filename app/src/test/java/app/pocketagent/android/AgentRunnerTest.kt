package app.pocketagent.android

import app.pocketagent.android.agent.AgentEvent
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.ApprovalGate
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.LlmClient
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.ToolSpec
import app.pocketagent.android.net.decodeDeepSeekReasoning
import app.pocketagent.android.tools.Tool
import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.FsWriteTool
import app.pocketagent.android.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class ScriptedClient(private val rounds: List<List<LlmEvent>>) : LlmClient {
    var round = 0
    var lastTools: List<ToolSpec> = emptyList()
    var lastMessages: List<ApiMessage> = emptyList()

    override fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        lastTools = tools
        lastMessages = messages
        val events = rounds.getOrElse(round++) { emptyList() }
        events.forEach { emit(it) }
    }

    override suspend fun probe(config: LlmConfig): String? = null
}

private class CountingGate(private val allow: Boolean) : ApprovalGate {
    var asked = 0
    override suspend fun request(request: ApprovalRequest): Boolean {
        asked++
        return allow
    }
}

private class CountingSearchTool : Tool {
    var executions = 0

    override val spec = ToolSpec(
        "web_search",
        "搜索公开网页",
        """{"type":"object"}""",
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome {
        executions++
        return ToolOutcome.Ok("搜索结果")
    }
}

private class ImageTool : Tool {
    override val spec = ToolSpec("view_image", "看图片", """{"type":"object"}""")
    override suspend fun execute(argumentsJson: String): ToolOutcome =
        ToolOutcome.Ok("已读取图片 a.png", listOf("data:image/png;base64,AAAA"))
}

private class BarrierReadTool(
    override val spec: ToolSpec,
    private val started: java.util.concurrent.atomic.AtomicInteger,
    private val active: java.util.concurrent.atomic.AtomicInteger,
    private val maxActive: java.util.concurrent.atomic.AtomicInteger,
    private val bothStarted: CompletableDeferred<Unit>,
) : Tool {
    override suspend fun execute(argumentsJson: String): ToolOutcome {
        val now = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, now) }
        try {
            if (started.incrementAndGet() >= 2) bothStarted.complete(Unit)
            withTimeout(1_000) { bothStarted.await() }
            return ToolOutcome.Ok("${spec.name} ok")
        } finally {
            active.decrementAndGet()
        }
    }
}

/** 第一次带思考档位就报 400，用来验证降级重试。 */
private class EffortRejectingClient : LlmClient {
    var calls = 0
    var sawEffort = false

    override fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        calls++
        if (config.provider.reasoningEffort != null) {
            sawEffort = true
            throw app.pocketagent.android.net.LlmException(400, "unsupported reasoning effort: max")
        }
        emit(LlmEvent.TextDelta("降级后正常"))
        emit(LlmEvent.Finished("stop"))
    }

    override suspend fun probe(config: LlmConfig): String? = null
}

class AgentRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun provider(autoApprove: Boolean = false, maxSteps: Int = 8, tools: Boolean = true) = Provider(
        name = "t",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://example.com",
        model = "deepseek-chat",
        autoApprove = autoApprove,
        maxSteps = maxSteps,
        toolsEnabled = tools,
    )

    @Test
    fun `tool call is approved executed and fed back`() = runTest {
        val root = tmp.newFolder()
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmEvent.ToolCallStarted(0, "call_a", "fs_write"),
                    LlmEvent.ToolCallArguments(0, """{"path":"x.txt","content":"hi"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                listOf(LlmEvent.TextDelta("写好了"), LlmEvent.Finished("stop")),
            )
        )
        val gate = CountingGate(true)
        val runner = AgentRunner(ToolRegistry(listOf(FsWriteTool(root))), gate) { client }

        val events = mutableListOf<AgentEvent>()
        val result = runner.run(provider(), "k", listOf(ApiMessage("user", "写文件"))) { events += it }

        assertEquals("写好了", result.text)
        assertEquals("hi", File(root, "x.txt").readText())
        assertEquals(1, gate.asked)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolFinished>().count { it.ok })
        assertEquals(2, result.steps)
        assertTrue(result.apiMessages.any { it.role == "tool" && it.content == "已写入 2 字节到 x.txt" })
        assertTrue(client.lastMessages.any { it.role == "tool" })
    }

    @Test
    fun `a truncated answer is continued once instead of returning half a reply`() = runTest {
        val client = ScriptedClient(
            listOf(
                listOf(LlmEvent.TextDelta("补充两点："), LlmEvent.Finished("length")),
                listOf(LlmEvent.TextDelta("\n1. 第一点\n2. 第二点"), LlmEvent.Finished("stop")),
            )
        )
        val runner = AgentRunner(ToolRegistry(emptyList()), CountingGate(true)) { client }
        val events = mutableListOf<AgentEvent>()

        val result = runner.run(provider(), "k", listOf(ApiMessage("user", "解释一下"))) { events += it }

        // 两段要拼在一起，不能只剩后半段
        assertEquals("补充两点：\n1. 第一点\n2. 第二点", result.text)
        assertTrue(events.filterIsInstance<AgentEvent.Notice>().any { it.text.contains("自动接着写") })
        // 续写请求里要带上已写出的部分，让模型知道从哪里接
        assertTrue(
            client.lastMessages.any { it.role == "assistant" && it.content.contains("补充两点") },
        )
        assertTrue(client.lastMessages.any { it.role == "user" && it.content.contains("截断") })
    }

    @Test
    fun `an unsupported reasoning effort is retried without it`() = runTest {
        val client = EffortRejectingClient()
        val runner = AgentRunner(ToolRegistry(emptyList()), CountingGate(true)) { client }
        val events = mutableListOf<AgentEvent>()

        val result = runner.run(
            provider().copy(reasoningEffort = "max"),
            "k",
            listOf(ApiMessage("user", "分析一下")),
        ) { events += it }

        assertEquals("降级后正常", result.text)
        assertTrue(client.sawEffort)
        assertEquals(2, client.calls)
        assertTrue(events.filterIsInstance<AgentEvent.Notice>().any { it.text.contains("思考档位") })
    }

    @Test
    fun `image tool result is fed back as a user image message`() = runTest {
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmEvent.ToolCallStarted(0, "call_img", "view_image"),
                    LlmEvent.ToolCallArguments(0, """{"path":"a.png"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                listOf(LlmEvent.TextDelta("图里是一个按钮"), LlmEvent.Finished("stop")),
            )
        )
        val runner = AgentRunner(ToolRegistry(listOf(ImageTool())), CountingGate(true)) { client }

        val result = runner.run(provider(), "k", listOf(ApiMessage("user", "看看这张图"))) {}

        assertEquals("图里是一个按钮", result.text)
        val withImage = client.lastMessages.filter { it.images.isNotEmpty() }
        assertEquals(1, withImage.size)
        assertEquals("user", withImage.single().role)
        assertEquals("data:image/png;base64,AAAA", withImage.single().images.single())
        assertTrue(client.lastMessages.any { it.role == "tool" && it.content.contains("已读取图片") })
    }

    @Test
    fun `denied tool is not executed`() = runTest {
        val root = tmp.newFolder()
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmEvent.ToolCallStarted(0, "call_b", "fs_write"),
                    LlmEvent.ToolCallArguments(0, """{"path":"y.txt","content":"no"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                listOf(LlmEvent.TextDelta("那就算了")),
            )
        )
        val runner = AgentRunner(ToolRegistry(listOf(FsWriteTool(root))), CountingGate(false)) { client }

        val events = mutableListOf<AgentEvent>()
        runner.run(provider(), "k", listOf(ApiMessage("user", "写文件"))) { events += it }

        assertFalse(File(root, "y.txt").exists())
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolDenied>().size)
        assertTrue(client.lastMessages.any { it.content.contains("用户拒绝") })
    }

    @Test
    fun `auto approve skips the gate`() = runTest {
        val root = tmp.newFolder()
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmEvent.ToolCallStarted(0, "call_c", "fs_write"),
                    LlmEvent.ToolCallArguments(0, """{"path":"z.txt","content":"auto"}"""),
                ),
                listOf(LlmEvent.TextDelta("ok")),
            )
        )
        val gate = CountingGate(false)
        val runner = AgentRunner(ToolRegistry(listOf(FsWriteTool(root))), gate) { client }
        runner.run(provider(autoApprove = true), "k", listOf(ApiMessage("user", "x"))) { }

        assertEquals(0, gate.asked)
        assertEquals("auto", File(root, "z.txt").readText())
    }

    @Test
    fun `stops at max steps`() = runTest {
        val root = tmp.newFolder()
        val loop = listOf(
            LlmEvent.ToolCallStarted(0, "call_d", "fs_write"),
            LlmEvent.ToolCallArguments(0, """{"path":"loop.txt","content":"x"}"""),
        )
        val client = ScriptedClient(List(5) { loop })
        val runner = AgentRunner(ToolRegistry(listOf(FsWriteTool(root))), CountingGate(true)) { client }

        val events = mutableListOf<AgentEvent>()
        val result = runner.run(provider(maxSteps = 2), "k", listOf(ApiMessage("user", "loop"))) { events += it }

        assertTrue(result.stoppedByLimit)
        assertEquals(2, result.steps)
        assertTrue(events.filterIsInstance<AgentEvent.Notice>().isNotEmpty())
    }

    @Test
    fun `tools disabled sends empty tool list`() = runTest {
        val root = tmp.newFolder()
        val client = ScriptedClient(listOf(listOf(LlmEvent.TextDelta("plain"))))
        val runner = AgentRunner(ToolRegistry(listOf(FsWriteTool(root))), CountingGate(true)) { client }
        val result = runner.run(provider(tools = false), "k", listOf(ApiMessage("user", "hi"))) { }

        assertEquals("plain", result.text)
        assertTrue(client.lastTools.isEmpty())
    }

    @Test
    fun `unknown tool returns error to model without crashing`() = runTest {
        val client = ScriptedClient(
            listOf(
                listOf(LlmEvent.ToolCallStarted(0, "call_x", "nope_tool"), LlmEvent.ToolCallArguments(0, "{}")),
                listOf(LlmEvent.TextDelta("done")),
            )
        )
        val runner = AgentRunner(ToolRegistry(emptyList()), CountingGate(true)) { client }
        val result = runner.run(provider(), "k", listOf(ApiMessage("user", "x"))) { }

        assertEquals("done", result.text)
        assertTrue(result.apiMessages.any { it.content.contains("未知工具") })
    }

    @Test
    fun `web search is capped even if the model keeps reformulating`() = runTest {
        val search = CountingSearchTool()
        val rounds = (1..5).map { index ->
            listOf(
                LlmEvent.ToolCallStarted(0, "search_$index", "web_search"),
                LlmEvent.ToolCallArguments(0, """{"query":"第 $index 次"}"""),
            )
        } + listOf(listOf(LlmEvent.TextDelta("基于已查到的内容回答"), LlmEvent.Finished("stop")))
        val client = ScriptedClient(rounds)
        val runner = AgentRunner(ToolRegistry(listOf(search)), CountingGate(true)) { client }

        val events = mutableListOf<AgentEvent>()
        val result = runner.run(provider(maxSteps = 8), "k", listOf(ApiMessage("user", "查资料"))) { events += it }

        assertEquals(3, search.executions)
        assertEquals(3, events.filterIsInstance<AgentEvent.ToolFinished>().count { it.ok })
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolFinished>().count { !it.ok })
        assertTrue(result.apiMessages.any { it.content.contains("web_search 预算已用尽") })
        assertEquals("基于已查到的内容回答", result.text)
    }

    @Test
    fun `reasoning item is attached to the tool round and sent back`() = runTest {
        val root = tmp.newFolder()
        val reasoning = """{"type":"reasoning","id":"rs_1","status":"completed","content":[],"summary":[],"encrypted_content":"opaque"}"""
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmEvent.ReasoningItem(reasoning),
                    LlmEvent.ToolCallStarted(0, "call_r", "fs_write"),
                    LlmEvent.ToolCallArguments(0, """{"path":"r.txt","content":"x"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                listOf(LlmEvent.TextDelta("完成"), LlmEvent.Finished("stop")),
            ),
        )
        val runner = AgentRunner(ToolRegistry(listOf(FsWriteTool(root))), CountingGate(true)) { client }

        val result = runner.run(provider(), "k", listOf(ApiMessage("user", "写文件"))) { }

        assertEquals("完成", result.text)
        val assistant = client.lastMessages.first { it.role == "assistant" && it.toolCalls.isNotEmpty() }
        assertEquals(reasoning, assistant.reasoningItem)
    }

    @Test
    fun `independent read tools execute in parallel`() = runTest {
        val started = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maxActive = java.util.concurrent.atomic.AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val tools = ToolRegistry(listOf(
            BarrierReadTool(ToolSpec("fs_read", "read", """{"type":"object"}"""), started, active, maxActive, gate),
            BarrierReadTool(ToolSpec("fs_search", "search", """{"type":"object"}"""), started, active, maxActive, gate),
        ))
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmEvent.ToolCallStarted(0, "read_1", "fs_read"),
                    LlmEvent.ToolCallArguments(0, """{"path":"a"}"""),
                    LlmEvent.ToolCallStarted(1, "read_2", "fs_search"),
                    LlmEvent.ToolCallArguments(1, """{"query":"b"}"""),
                    LlmEvent.Finished("tool_calls"),
                ),
                listOf(LlmEvent.TextDelta("并行完成"), LlmEvent.Finished("stop")),
            ),
        )
        val runner = AgentRunner(tools, CountingGate(true)) { client }

        val result = runner.run(provider(maxSteps = 2), "k", listOf(ApiMessage("user", "read both"))) {}

        assertEquals("并行完成", result.text)
        assertEquals(2, started.get())
        assertEquals(2, maxActive.get())
        assertTrue(result.apiMessages.count { it.role == "tool" } >= 2)
    }

    @Test
    fun `deepseek reasoning deltas are preserved for the next chat turn`() = runTest {
        val client = ScriptedClient(
            listOf(
                listOf(
                    LlmEvent.ReasoningDelta("先分析再回答"),
                    LlmEvent.TextDelta("答案"),
                    LlmEvent.Finished("stop"),
                ),
            ),
        )
        val runner = AgentRunner(ToolRegistry(emptyList()), CountingGate(true)) { client }

        val result = runner.run(provider(maxSteps = 1), "k", listOf(ApiMessage("user", "问题"))) {}

        assertEquals("先分析再回答", decodeDeepSeekReasoning(result.reasoningItem))
    }
}
