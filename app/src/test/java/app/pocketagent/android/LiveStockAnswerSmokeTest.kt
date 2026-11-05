package app.pocketagent.android

import app.pocketagent.android.agent.AgentInstructions
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.ChatEngine
import app.pocketagent.android.agent.ToolPermissions
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.Role
import app.pocketagent.android.net.ResponsesClient
import app.pocketagent.android.tools.FsListTool
import app.pocketagent.android.tools.FsReadTool
import app.pocketagent.android.tools.HttpGetTool
import app.pocketagent.android.tools.ToolRegistry
import app.pocketagent.android.tools.WebSearchTool
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 用户反馈的“宇树股票”类问题：走 App 的完整 harness（同一 system prompt、同一批工具、
 * 同一 max 推理档），验证回复形态与取证是否和桌面 Codex 一致。
 *
 * 默认不跑；手动执行（本机 3181 适配器自己持有凭据，测试不接触任何 key）：
 *   PA_LIVE_TOKEN=local-adapter PA_LIVE_BASE=http://127.0.0.1:3181 gradle :app:testDebugUnitTest \
 *     --tests "*LiveStockAnswerSmokeTest*"
 */
class LiveStockAnswerSmokeTest {

    @Test
    fun `stock question uses web evidence and returns a clean body`() = runBlocking {
        val token = System.getenv("PA_LIVE_TOKEN")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val base = System.getenv("PA_LIVE_BASE")?.takeIf { it.isNotBlank() }
            ?: "http://127.0.0.1:3181"

        val dir = Files.createTempDirectory("pa-live-stock").toFile()
        val registry = ToolRegistry(
            listOf(
                FsListTool(dir),
                FsReadTool(dir),
                HttpGetTool(),
                WebSearchTool(),
            ),
        )
        val baseProvider = Provider(
            name = "live",
            kind = ProviderKind.RESPONSES,
            baseUrl = base,
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
            maxSteps = 12,
            toolsEnabled = true,
            permissions = ToolPermissions.defaults,
        )
        val provider = baseProvider.copy(
            systemPrompt = AgentInstructions.build(
                provider = baseProvider,
                workspaceLabel = "tmp",
                agentsMd = null,
                tools = registry.specs(),
                now = "2026-11-02 00:20 +08:00",
            ),
        )
        // local-adapter 模式：不往本地回环外发任何凭据，由本机适配器注入它自己的 key。
        val noAuth = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request().newBuilder().removeHeader("Authorization").build()
            chain.proceed(req)
        }.build()
        val engine = ChatEngine(
            AgentRunner(registry, app.pocketagent.android.agent.AutoApproveGate) {
                if (token == "local-adapter") ResponsesClient(noAuth) else ResponsesClient()
            },
        )

        val question = "宇树科技（688836）最近一个交易日的收盘价是多少？" +
            "给我结论、一张小表格和可点开的来源链接；今天是 2026-11-02。"
        val started = System.currentTimeMillis()
        val final = engine.runOnce(
            provider,
            token,
            listOf(ChatMessage(role = Role.USER, text = question)),
        ) { }
        val elapsed = System.currentTimeMillis() - started

        println("[body]\n${final.text}")
        println("[tools] ${final.toolCalls.map { "${it.name}:${it.status}" }}")
        println("[why] ${final.reasoning.take(400)}")
        println("[time] ${elapsed}ms in=${final.tokensIn} out=${final.tokensOut}")

        assertTrue("正文为空", final.text.isNotBlank())
        assertTrue("没有股票代码：${final.text}", final.text.contains("688836"))
        assertTrue("没有价格：${final.text}", Regex("""\d+\.\d{2}""").containsMatchIn(final.text))
        assertTrue("没有来源链接：${final.text}", final.text.contains("http"))
        assertTrue(
            "没有联网取证：${final.toolCalls.map { it.name }}",
            final.toolCalls.any { it.name == "http_get" || it.name == "web_search" },
        )
        // 过程说明应留在折叠区，正文以结论开头
        listOf("我先", "我来", "让我先", "我先去").forEach {
            assertFalse("正文混入过程说明「$it」：${final.text.take(200)}", final.text.trimStart().startsWith(it))
        }
    }
}
