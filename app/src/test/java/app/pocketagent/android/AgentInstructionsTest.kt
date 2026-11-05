package app.pocketagent.android

import app.pocketagent.android.agent.AgentInstructions
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ToolSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentInstructionsTest {

    private val styleBase = """
        # Personality

        STYLE_BASE_MARKER: desktop personality.
    """.trimIndent()

    private val provider = Provider(
        name = "relay",
        kind = ProviderKind.RESPONSES,
        baseUrl = "https://example.com/v1",
        model = "deepseek-v4-flash",
        systemPrompt = "自定义角色",
        reasoningEffort = "max",
    )

    private val tools = listOf(
        ToolSpec("web_search", "搜索公开网页，返回标题、摘要和链接。", """{"type":"object"}"""),
        ToolSpec("fs_read", "读取工作区文件。", """{"type":"object"}"""),
    )

    @Test
    fun `includes contract environment agents and actual tools`() {
        val text = AgentInstructions.build(
            provider = provider,
            workspaceLabel = "手机工作区",
            agentsMd = "# 项目约定\n- 先跑测试",
            tools = tools,
            now = "2026-11-01 20:00",
            styleBase = styleBase,
        )

        assertTrue(text.startsWith("自定义角色"))
        assertTrue(text.contains("STYLE_BASE_MARKER"))
        assertTrue(text.contains("Keep going until the turn is actually finished"))
        assertTrue(text.contains("Never print tool calls as JSON, XML"))
        assertTrue(text.contains("`web_search`"))
        assertTrue(text.contains("`fs_read`"))
        assertTrue(text.contains("<agents_md path=\"AGENTS.md hierarchy\">"))
        assertTrue(text.contains("2026-11-01 20:00"))
        assertTrue(
            text.indexOf("STYLE_BASE_MARKER") <
                text.indexOf("You are Codex, the agent running inside PocketAgent"),
        )
        assertTrue(
            text.indexOf("You are Codex, the agent running inside PocketAgent") <
                text.indexOf("# Environment"),
        )
        assertTrue(text.indexOf("# Environment") < text.indexOf("# Tools available this turn"))
        assertFalse(text.contains("apply_patch"))
        assertFalse(text.contains("functions.exec"))
    }

    @Test
    fun `disabled tools are not advertised`() {
        val text = AgentInstructions.build(
            provider = provider.copy(toolsEnabled = false),
            workspaceLabel = "手机工作区",
            agentsMd = null,
            tools = tools,
            now = "2026-11-01 20:00",
            styleBase = styleBase,
        )

        assertTrue(text.contains("Tools are disabled for this turn"))
        assertTrue(text.contains("STYLE_BASE_MARKER"))
        assertTrue(text.contains("Do not assume access to a local filesystem this turn"))
        assertTrue(text.contains("memory is not evidence of the current state"))
        assertTrue(text.contains("do not assert a negative current state"))
        assertFalse(text.contains("`web_search`:"))
        assertFalse(text.contains("`fs_read`:"))
    }

    @Test
    fun `environment describes the tools that actually exist`() {
        val realTools = tools + listOf(
            ToolSpec("shell_exec", "在 Android 工作区执行命令。", """{"type":"object"}"""),
            ToolSpec("ssh_exec", "在远端主机执行命令。", """{"type":"object"}"""),
            ToolSpec("apply_patch", "按补丁修改文件。", """{"type":"object"}"""),
            ToolSpec("mcp__demo__search", "MCP 搜索。", """{"type":"object"}"""),
        )
        val text = AgentInstructions.build(
            provider = provider,
            workspaceLabel = "手机工作区",
            agentsMd = null,
            tools = realTools,
            now = "2026-11-01 20:00",
            styleBase = styleBase,
        )

        assertTrue(text.contains("Local files live in the app workspace"))
        assertTrue(text.contains("`shell_exec` runs non-interactive commands"))
        assertTrue(text.contains("`ssh_exec` runs non-interactive commands"))
        assertTrue(text.contains("written under `.pocketagent/logs/`"))
        assertTrue(text.contains("There is no ripgrep on the phone shell"))
        assertTrue(text.contains("Prefer `apply_patch` for targeted, reviewable edits"))
        assertTrue(text.contains("1 MCP tool(s) come from servers the user configured"))
        assertFalse(text.contains("There is no local shell"))
        // 手机本地有 shell，但不等于桌面 Codex 的登录 shell / OS 沙箱
        assertTrue(text.contains("not an OS-level sandbox"))
    }

    @Test
    fun `workspace contract keeps edits minimal and warns about missing phone tooling`() {
        val text = AgentInstructions.build(
            provider = provider,
            workspaceLabel = "手机工作区",
            agentsMd = null,
            tools = tools,
            now = "2026-11-01 20:00",
            styleBase = styleBase,
        )

        assertTrue(text.contains("## Working inside the workspace"))
        assertTrue(text.contains("Do not assume `rg`, `git`, `node`, `python`"))
        assertTrue(text.contains("Read before you edit"))
        assertTrue(text.contains("never revert work you did not do in this turn"))
        assertTrue(text.contains("Treat deletion and overwrite as destructive"))
        // 工具清单里没有 apply_patch 时不能凭空宣传它
        assertFalse(text.contains("`apply_patch`"))
    }

    @Test
    fun `web research contract asks for direct sources`() {
        val text = AgentInstructions.build(
            provider = provider,
            workspaceLabel = "手机工作区",
            agentsMd = null,
            tools = tools,
            now = "2026-11-01 20:00",
            styleBase = styleBase,
        )

        assertTrue(text.contains("Search snippets are leads, not proof"))
        assertTrue(text.contains("direct Markdown links"))
    }

    @Test
    fun `goal is appended after skills`() {
        val text = AgentInstructions.build(
            provider = provider,
            workspaceLabel = "手机工作区",
            agentsMd = null,
            tools = tools,
            now = "2026-11-01 20:00",
            skills = listOf("deploy" to "部署流程"),
            styleBase = styleBase,
            goal = "把登录页修好",
        )

        assertTrue(text.contains("- `deploy`: 部署流程"))
        assertTrue(text.contains("# 当前目标（必须朝它推进，不要停在计划上）"))
        assertTrue(text.contains("把登录页修好"))
        assertTrue(text.trimEnd().endsWith("GOAL_DONE。"))
        assertTrue(text.indexOf("# Workspace skills") < text.indexOf("# 当前目标"))
    }

    @Test
    fun `build without style or goal stays backward compatible`() {
        val text = AgentInstructions.build(
            provider = provider,
            workspaceLabel = "手机工作区",
            agentsMd = null,
            tools = tools,
            now = "2026-11-01 20:00",
        )

        assertTrue(text.contains("Keep going until the turn is actually finished"))
        assertFalse(text.contains("# 当前目标"))
        assertFalse(text.contains("GOAL_DONE"))
    }
}
