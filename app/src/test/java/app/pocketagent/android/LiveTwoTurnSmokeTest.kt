package app.pocketagent.android

import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.AutoApproveGate
import app.pocketagent.android.agent.ChatEngine
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.Role
import app.pocketagent.android.tools.ToolRegistry
import app.pocketagent.android.tools.WebSearchTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨轮冒烟：第一轮调用工具，把结果落成 ChatMessage；第二轮带着这段历史继续问。
 * 这验证「多步工具轮次被折叠成一条消息」之后，reasoning 回传是否仍被中转站接受。
 * 没有 PA_LIVE_TOKEN 时直接返回。
 */
class LiveTwoTurnSmokeTest {

    @Test
    fun `follow up after a tool turn still works`() = runBlocking {
        val token = System.getenv("PA_LIVE_TOKEN")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val registry = ToolRegistry(listOf(WebSearchTool()))
        val provider = Provider(
            name = "live",
            kind = ProviderKind.RESPONSES,
            baseUrl = System.getenv("PA_LIVE_BASE")?.takeIf { it.isNotBlank() }
                ?: "https://your-relay.example.com/v1",
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
            maxSteps = 3,
            toolsEnabled = true,
        )
        val engine = ChatEngine(AgentRunner(registry, AutoApproveGate))
        val history = mutableListOf<ChatMessage>()

        history += ChatMessage(role = Role.USER, text = "搜索一下今天的日期和上证指数点位，简短回答。")
        val first = engine.runOnce(provider, token, history) { }
        history += first
        println("[turn1] steps=${first.toolCalls.size} tools, text=${first.text.take(80)}")

        history += ChatMessage(role = Role.USER, text = "用一句话总结你刚才查到的东西，不要再调用工具。")
        val second = engine.runOnce(provider, token, history) { }
        println("[turn2] ${second.text.take(300)}")
        assertTrue("第二轮没有回答：${second.text}", second.text.isNotBlank())
    }
}
