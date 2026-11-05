package app.pocketagent.android

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pocketagent.android.agent.AgentInstructions
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 风格基础必须是桌面模板的逐字段落，不能夹带桌面专属工具名，
 * 否则模型会照着提示词调用手机上不存在的能力。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentInstructionsStyleBaseTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `style base keeps the desktop style sections`() {
        val style = AgentInstructions.styleBase(context())

        assertTrue(style.startsWith("# Personality"))
        listOf(
            "## Writing style",
            "## Technical communication",
            "# Working with the user",
            "## Intermediate commentary",
            "## Final answer",
            "### Formatting rules",
            "### Visualizations",
        ).forEach { heading ->
            assertTrue("missing $heading", style.contains(heading))
        }
        assertTrue(style.contains("like easing into a chat with an old friend"))
        assertTrue(style.contains("Avoid over-formatting responses"))
        assertTrue(style.contains("Lead with the outcome rather than the steps you took"))
    }

    @Test
    fun `style base does not carry desktop tool sections`() {
        val style = AgentInstructions.styleBase(context())

        listOf(
            "apply_patch",
            "functions.exec",
            "rg --files",
            "# Rules for getting work done",
            "# File editing constraints",
            "# Destructive Actions",
            "# Using skills",
        ).forEach { leaked ->
            assertFalse("style base leaked tool-specific text: $leaked", style.contains(leaked))
        }
    }

    @Test
    fun `asset manager reads the checked-in style base`() {
        val style = AgentInstructions.styleBase(context())
        val source = findProjectFile("app/src/main/assets/harness/style-base.md")

        assertEquals(source.readText().trim(), style)
    }

    @Test
    fun `build places the full style base before the operating contract`() {
        val style = AgentInstructions.styleBase(context())
        val text = AgentInstructions.build(
            provider = Provider(
                name = "relay",
                kind = ProviderKind.RESPONSES,
                baseUrl = "https://example.com/v1",
                model = "deepseek-v4-flash",
                systemPrompt = "",
            ),
            workspaceLabel = "手机工作区",
            agentsMd = null,
            tools = listOf(ToolSpec("fs_read", "读取工作区文件。", """{"type":"object"}""")),
            now = "2026-11-01 20:00",
            styleBase = style,
        )

        assertTrue(text.startsWith("# Personality"))
        assertTrue(
            text.indexOf("### Visualizations") <
                text.indexOf("You are Codex, the agent running inside PocketAgent"),
        )
        assertTrue(text.indexOf("# Operating contract") < text.indexOf("# Environment"))
        assertTrue(text.indexOf("# Environment") < text.indexOf("`fs_read`"))
    }

    private fun findProjectFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val current = dir ?: error("not found from ${System.getProperty("user.dir")}: $relative")
            val candidate = File(current, relative)
            if (candidate.isFile) return candidate
            dir = current.parentFile
        }
    }
}
