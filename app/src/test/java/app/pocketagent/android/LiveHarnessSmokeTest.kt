package app.pocketagent.android

import app.pocketagent.android.agent.AgentEvent
import app.pocketagent.android.agent.AgentInstructions
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.AutoApproveGate
import app.pocketagent.android.agent.MessageMapper
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.Role
import app.pocketagent.android.tools.HttpGetTool
import app.pocketagent.android.tools.ToolRegistry
import app.pocketagent.android.tools.WebSearchTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机外的端到端冒烟：连真实中转站跑一遍「提问 → 联网搜索 → 多步回灌 → 最终回答」。
 * 默认不跑（没有 PA_LIVE_TOKEN 时直接返回），避免 CI/离线环境打网络。
 *
 * 手动执行：
 *   PA_LIVE_TOKEN=<key> gradle :app:testDebugUnitTest --tests "*LiveHarnessSmokeTest*"
 */
class LiveHarnessSmokeTest {

    @Test
    fun `search then answer end to end`() = runBlocking {
        val token = System.getenv("PA_LIVE_TOKEN")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val base = System.getenv("PA_LIVE_BASE")?.takeIf { it.isNotBlank() }
            ?: "https://your-relay.example.com/v1"
        val question = System.getenv("PA_LIVE_QUESTION")?.takeIf { it.isNotBlank() }
            ?: "帮我看看宇树科技最近的股票行情和上市进展，值不值得关注？"

        // 与 App 的真实联网工具集一致。只给 web_search 会把“没有网页抓取工具”
        // 误判成 App 的能力限制，也会掩盖搜索后没有打开原文的问题。
        val registry = ToolRegistry(listOf(WebSearchTool(), HttpGetTool()))
        val baseProvider = Provider(
            name = "live",
            kind = ProviderKind.RESPONSES,
            baseUrl = base,
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
            maxSteps = System.getenv("PA_LIVE_STEPS")?.toIntOrNull() ?: 8,
            toolsEnabled = true,
        )
        val provider = baseProvider.copy(
            systemPrompt = AgentInstructions.build(
                provider = baseProvider,
                workspaceLabel = "test",
                agentsMd = null,
                tools = registry.specs(),
                now = "2026-11-01 20:00 +08:00",
            ),
        )

        val history = MessageMapper.toApiMessages(
            listOf(ChatMessage(role = Role.USER, text = question)),
        )
        val result = AgentRunner(registry, AutoApproveGate).run(provider, token, history) { event ->
            when (event) {
                is AgentEvent.StepStarted -> println("[step] ${event.step}")
                is AgentEvent.ToolFinished -> println("[tool] ${event.name} ok=${event.ok} ${event.output.take(200)}")
                is AgentEvent.Notice -> println("[notice] ${event.text}")
                else -> Unit
            }
        }

        println("[steps] ${result.steps} in=${result.tokensIn} out=${result.tokensOut}")
        println("[answer]\n${result.text}")
        assertTrue("回答太短：${result.text}", result.text.length > 80)
        assertTrue("没有跑完工具循环：steps=${result.steps}", result.steps >= 2)
    }
}
