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
import app.pocketagent.android.tools.FsEditTool
import app.pocketagent.android.tools.FsGlobTool
import app.pocketagent.android.tools.FsReadTool
import app.pocketagent.android.tools.FsSearchTool
import app.pocketagent.android.tools.LocalWorkspace
import app.pocketagent.android.tools.ShellExecTool
import app.pocketagent.android.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 新工具的端到端冒烟：让模型自己用 fs_search 找、fs_edit 改、shell_exec 验证。
 * 没有 PA_LIVE_TOKEN 时直接返回。手动执行：
 *   PA_LIVE_TOKEN=<key> gradle :app:testDebugUnitTest --tests "*LiveToolsSmokeTest*"
 */
class LiveToolsSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `model edits a file with the new tools`() = runBlocking {
        val token = System.getenv("PA_LIVE_TOKEN")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val root = tmp.newFolder()
        val target = File(root, "config.txt")
        target.writeText("host=example\nstatus=TODO\nport=8080\n")

        val workspace = LocalWorkspace(root)
        val registry = ToolRegistry(
            listOf(
                FsReadTool(workspace),
                FsGlobTool(workspace),
                FsSearchTool(workspace),
                FsEditTool(workspace),
                ShellExecTool(root),
            ),
        )
        val baseProvider = Provider(
            name = "live",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://your-relay.example.com/v1",
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
            maxSteps = 6,
            toolsEnabled = true,
        )
        val provider = baseProvider.copy(
            systemPrompt = AgentInstructions.build(
                provider = baseProvider,
                workspaceLabel = workspace.label,
                agentsMd = null,
                tools = registry.specs(),
                now = "2026-11-01 21:00 +08:00",
            ),
        )

        val history = MessageMapper.toApiMessages(
            listOf(
                ChatMessage(
                    role = Role.USER,
                    text = "工作区里 config.txt 的 status 现在是 TODO。用 fs_search 定位，用 fs_edit 改成 DONE，" +
                        "再用 shell_exec 执行 grep DONE config.txt 确认。改完告诉我结果。",
                ),
            ),
        )
        val result = AgentRunner(registry, AutoApproveGate).run(provider, token, history) { event ->
            when (event) {
                is AgentEvent.StepStarted -> println("[step] ${event.step}")
                is AgentEvent.ReasoningItem -> println("[reasoning-item] ${event.json.length} chars")
                is AgentEvent.ToolStarted -> println("[tool-start] ${event.name} ${event.arguments.take(80)}")
                is AgentEvent.ToolFinished -> println("[tool] ${event.name} ok=${event.ok} ${event.output.take(120)}")
                is AgentEvent.Notice -> println("[notice] ${event.text}")
                else -> Unit
            }
        }

        println("[answer]\n${result.text}")
        assertTrue("文件没被改成 DONE：${target.readText()}", target.readText().contains("status=DONE"))
        assertTrue("模型没有给出结论：${result.text}", result.text.isNotBlank())
    }
}
