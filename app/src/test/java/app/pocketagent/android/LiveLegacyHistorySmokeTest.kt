package app.pocketagent.android

import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.AutoApproveGate
import app.pocketagent.android.agent.MessageMapper
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.ApiToolCall
import app.pocketagent.android.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧会话兼容：修复前保存的工具轮没有 reasoning item（用户那条 1641 条对话大量存在）。
 * 这里构造一段这样的历史，再追问一句，确认中转站不会因为缺 reasoning_text 拒绝。
 * 没有 PA_LIVE_TOKEN 时直接返回。
 */
class LiveLegacyHistorySmokeTest {

    @Test
    fun `long history with legacy tool turns still answers`() = runBlocking {
        val token = System.getenv("PA_LIVE_TOKEN")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val provider = Provider(
            name = "live",
            kind = ProviderKind.RESPONSES,
            baseUrl = System.getenv("PA_LIVE_BASE")?.takeIf { it.isNotBlank() }
                ?: "https://your-relay.example.com/v1",
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
            maxSteps = 2,
            toolsEnabled = false,
        )

        // 40 条旧工具轮：assistant 带 toolCalls 但没有 reasoningItem
        val legacy = buildList {
            for (i in 1..40) {
                add(ApiMessage("user", "第 $i 步：看一下文件 chunk$i.txt"))
                add(
                    ApiMessage(
                        role = "assistant",
                        content = "看一下 chunk$i.txt",
                        toolCalls = listOf(ApiToolCall("legacy_$i", "fs_read", """{"path":"chunk$i.txt"}""")),
                        reasoningItem = null,
                    ),
                )
                add(ApiMessage("tool", "chunk$i 的内容：ok", toolCallId = "legacy_$i"))
            }
            add(ApiMessage("user", "用一句话总结上面这些步骤在做什么。"))
        }

        val result = AgentRunner(ToolRegistry(emptyList()), AutoApproveGate)
            .run(provider, token, legacy) { }

        println("[answer] ${result.text.take(300)}")
        assertTrue("旧会话继续对话没有回答：${result.text}", result.text.isNotBlank())
    }
}
