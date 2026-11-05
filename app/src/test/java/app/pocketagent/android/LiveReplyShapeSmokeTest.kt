package app.pocketagent.android

import app.pocketagent.android.agent.AgentInstructions
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.AutoApproveGate
import app.pocketagent.android.agent.ChatEngine
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.Role
import app.pocketagent.android.net.ResponsesClient
import app.pocketagent.android.tools.FsListTool
import app.pocketagent.android.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 真机外的端到端校验：连真实中转站，跑一个必须调用工具的两步任务，
 * 检查「过程说明」没有混进最终正文（只留在折叠的思考/过程里）。
 *
 * 默认不跑；手动执行：
 *   PA_LIVE_TOKEN=<key> gradle :app:testDebugUnitTest --tests "*LiveReplyShapeSmokeTest*"
 */
class LiveReplyShapeSmokeTest {

    @Test
    fun `tool step narration stays out of the final body`() = runBlocking {
        val token = System.getenv("PA_LIVE_TOKEN")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val base = System.getenv("PA_LIVE_BASE")?.takeIf { it.isNotBlank() }
            ?: "https://your-relay.example.com/v1"

        // 用自己建的临时目录，避免把用户工作区里的内容发给模型。
        val dir = Files.createTempDirectory("pa-live-shape").toFile()
        dir.resolve("note.txt").writeText("alpha\nbeta\ngamma\n")

        val registry = ToolRegistry(listOf(FsListTool(dir)))
        val baseProvider = Provider(
            name = "live",
            kind = ProviderKind.RESPONSES,
            baseUrl = base,
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
            maxSteps = 6,
            toolsEnabled = true,
        )
        val provider = baseProvider.copy(
            systemPrompt = AgentInstructions.build(
                provider = baseProvider,
                workspaceLabel = "tmp",
                agentsMd = null,
                tools = registry.specs(),
                now = "2026-11-01 23:10 +08:00",
            ),
        )
        val engine = ChatEngine(AgentRunner(registry, AutoApproveGate) { ResponsesClient() })

        val question = "先列一下工作区里有哪些文件，然后用一句话告诉我 note.txt 有几行。"
        val final = engine.runOnce(
            provider,
            token,
            listOf(ChatMessage(role = Role.USER, text = question)),
        ) { }

        println("[body]\n${final.text}")
        println("[process]\n${final.reasoning.take(600)}")
        println("[tools] ${final.toolCalls.map { it.name }} in=${final.tokensIn} out=${final.tokensOut}")

        assertTrue("正文为空", final.text.isNotBlank())
        // 过程说明应落在 reasoning，而不是正文。
        assertTrue("没有记录过程：${final.reasoning}", final.reasoning.isNotBlank())
        assertFalse("正文仍混入过程说明：${final.text}", final.text.contains("我先列"))
        assertFalse("正文仍混入过程说明：${final.text}", final.text.contains("我来列"))
    }
}
