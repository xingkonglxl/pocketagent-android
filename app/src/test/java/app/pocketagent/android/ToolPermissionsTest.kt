package app.pocketagent.android

import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.ApprovalGate
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.agent.ToolPermissions
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只发一次指定工具调用，第二阶段直接给结论。 */
private class OneToolClient(private val toolName: String, private val args: String = "{}") : LlmClient {
    private var round = 0
    override fun stream(
        config: LlmConfig,
        systemPrompt: String,
        messages: List<ApiMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        if (round++ == 0) {
            emit(LlmEvent.ToolCallStarted(0, "call_1", toolName))
            emit(LlmEvent.ToolCallArguments(0, args))
        } else {
            emit(LlmEvent.TextDelta("好了"))
        }
    }

    override suspend fun probe(config: LlmConfig): String? = null
}

private class RecordingGate : ApprovalGate {
    var asked = 0
    val requests = mutableListOf<ApprovalRequest>()

    override suspend fun request(request: ApprovalRequest): Boolean {
        asked++
        requests += request
        return true
    }
}

private class StubTool(override val spec: ToolSpec) : Tool {
    var runs = 0
    override suspend fun execute(argumentsJson: String): ToolOutcome {
        runs++
        return ToolOutcome.Ok("ok")
    }
}

class ToolPermissionsTest {

    @Test
    fun `every registered tool maps to a category`() {
        val tools = listOf(
            "web_search", "http_get",
            "fs_read", "fs_list", "fs_glob", "fs_search", "pdf_read", "ssh_read", "ssh_ls",
            "fs_write", "fs_edit",
            "fs_delete",
            "shell_exec",
            "ssh_exec", "ssh_write", "remote_codex",
        )
        tools.forEach { name ->
            assertTrue("$name 没有归类", ToolPermissions.categoryOf(name) != null)
        }
    }

    @Test
    fun `defaults allow only read and web`() {
        assertEquals(setOf(ToolPermissions.WEB, ToolPermissions.READ), ToolPermissions.defaults)
        assertTrue(ToolPermissions.DELETE !in ToolPermissions.defaults)
        assertTrue(ToolPermissions.SHELL !in ToolPermissions.defaults)
        assertTrue(ToolPermissions.WRITE !in ToolPermissions.defaults)
        assertTrue(ToolPermissions.REMOTE !in ToolPermissions.defaults)
    }

    @Test
    fun `every category has a label and description`() {
        ToolPermissions.all.forEach { category ->
            assertTrue(category, ToolPermissions.label(category).isNotBlank())
            assertTrue(category, ToolPermissions.describe(category).isNotBlank())
        }
    }

    @Test
    fun `web tools run without asking when web is granted`() = runTest {
        val tool = StubTool(ToolSpec("http_get", "抓网页", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            permissions = setOf(ToolPermissions.WEB),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) { OneToolClient("http_get") }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(1, tool.runs)
        assertEquals("放行的类别不该再弹审批", 0, gate.asked)
    }

    @Test
    fun `write tools still ask when only web is granted`() = runTest {
        val tool = StubTool(ToolSpec("fs_write", "写文件", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            permissions = setOf(ToolPermissions.WEB),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) { OneToolClient("fs_write") }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(1, gate.asked)
        assertEquals(1, tool.runs)
        assertFalse("覆盖文件的请求不能批量放行", gate.requests.single().sessionAllowable)
    }

    @Test
    fun `low risk read prompt is session allowable`() = runTest {
        val tool = StubTool(ToolSpec("fs_read", "读文件", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            permissions = setOf(ToolPermissions.WEB),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) {
            OneToolClient("fs_read", """{"path":"docs/README.md"}""")
        }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(1, gate.asked)
        assertTrue(gate.requests.single().sessionAllowable)
    }

    @Test
    fun `granting write lets it through without asking`() = runTest {
        val tool = StubTool(ToolSpec("fs_write", "写文件", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            permissions = setOf(ToolPermissions.WEB, ToolPermissions.WRITE),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) { OneToolClient("fs_write") }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(0, gate.asked)
    }

    @Test
    fun `denied category does not run the tool`() = runTest {
        val tool = StubTool(ToolSpec("fs_delete", "删文件", "{}"))
        val gate = object : ApprovalGate {
            override suspend fun request(request: ApprovalRequest) = false
        }
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            permissions = ToolPermissions.defaults,
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) { OneToolClient("fs_delete") }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(0, tool.runs)
        assertFalse(ToolPermissions.DELETE in provider.permissions)
    }

    @Test
    fun `sensitive file read still asks even when read and auto approve are on`() = runTest {
        val tool = StubTool(ToolSpec("ssh_read", "读远端文件", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            autoApprove = true,
            permissions = setOf(ToolPermissions.WEB, ToolPermissions.READ),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) {
            OneToolClient("ssh_read", """{"path":"/home/me/.ssh/id_ed25519"}""")
        }
        runner.run(provider, "k", emptyList()) { }

        assertEquals("私钥文件必须重新确认", 1, gate.asked)
        assertEquals(1, tool.runs)
        assertFalse(gate.requests.single().sessionAllowable)
    }

    @Test
    fun `ssh write always asks even with auto approve and remote permission`() = runTest {
        val tool = StubTool(ToolSpec("ssh_write", "写远端文件", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            autoApprove = true,
            permissions = setOf(ToolPermissions.REMOTE),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) {
            OneToolClient("ssh_write", """{"path":"src/a.kt","content":"x"}""")
        }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(1, gate.asked)
        assertEquals(1, tool.runs)
        assertFalse(gate.requests.single().sessionAllowable)
    }

    @Test
    fun `sensitive write still asks even with auto approve`() = runTest {
        val tool = StubTool(ToolSpec("fs_write", "写文件", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            autoApprove = true,
            permissions = setOf(ToolPermissions.WRITE),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) {
            OneToolClient("fs_write", """{"path":".env","content":"SECRET=x"}""")
        }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(1, gate.asked)
        assertEquals(1, tool.runs)
        assertFalse(gate.requests.single().sessionAllowable)
    }

    @Test
    fun `remote codex always asks even with auto approve and remote permission`() = runTest {
        val tool = StubTool(ToolSpec("remote_codex", "远端 harness", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            autoApprove = true,
            permissions = setOf(ToolPermissions.REMOTE),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) {
            OneToolClient("remote_codex", """{"prompt":"检查项目"}""")
        }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(1, gate.asked)
        assertEquals(1, tool.runs)
    }

    @Test
    fun `ordinary file read stays silent when read is granted`() = runTest {
        val tool = StubTool(ToolSpec("fs_read", "读文件", "{}"))
        val gate = RecordingGate()
        val provider = Provider(
            name = "t",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://example.com",
            model = "m",
            permissions = setOf(ToolPermissions.WEB, ToolPermissions.READ),
        )
        val runner = AgentRunner(ToolRegistry(listOf(tool)), gate) {
            OneToolClient("fs_read", """{"path":"app/src/main/AndroidManifest.xml"}""")
        }
        runner.run(provider, "k", emptyList()) { }

        assertEquals(0, gate.asked)
        assertEquals(1, tool.runs)
    }

    @Test
    fun `sensitive path detection applies to read and write tools`() {
        assertTrue(ToolPermissions.needsExtraApproval("fs_read", """{"path":"~/.aws/credentials"}"""))
        assertTrue(ToolPermissions.needsExtraApproval("ssh_ls", """{"path":"/root/.ssh"}"""))
        assertTrue(ToolPermissions.needsExtraApproval("fs_read", """{"path":"deploy/server.pem"}"""))
        assertTrue(ToolPermissions.needsExtraApproval("fs_read", """{"path":".env"}"""))
        assertTrue(ToolPermissions.needsExtraApproval("fs_write", """{"path":"~/.ssh/config"}"""))
        assertFalse(ToolPermissions.needsExtraApproval("fs_read", """{"path":"docs/README.md"}"""))
        assertFalse(ToolPermissions.needsExtraApproval("http_get", """{"url":"https://example.com/.env"}"""))
    }

    @Test
    fun `dangerous tools and sensitive paths require per call approval`() {
        ToolPermissions.alwaysConfirm.forEach { tool ->
            assertTrue(tool, ToolPermissions.requiresPerCallApproval(tool, "{}"))
            assertFalse(tool, ToolPermissions.isSessionAllowable(tool, "{}"))
        }
        assertTrue(
            ToolPermissions.requiresPerCallApproval(
                "fs_read",
                """{"path":"/home/me/.ssh/id_ed25519"}""",
            ),
        )
        assertTrue(
            ToolPermissions.requiresPerCallApproval(
                "fs_edit",
                """{"path":".env","find":"A","replace":"B"}""",
            ),
        )
        assertFalse(ToolPermissions.isSessionAllowable("fs_write", """{"path":"notes.txt"}"""))
        assertTrue(ToolPermissions.isSessionAllowable("http_get", """{"url":"https://example.com"}"""))
        assertTrue(ToolPermissions.isSessionAllowable("fs_read", """{"path":"README.md"}"""))
    }

    @Test
    fun `mcp tools are their own category and off by default`() {
        assertEquals(ToolPermissions.MCP, ToolPermissions.categoryOf("mcp__github__create_issue"))
        assertEquals(ToolPermissions.MCP, ToolPermissions.categoryOf("mcp__x__y"))
        // 未知的普通工具名不应被误判成 MCP
        assertEquals(null, ToolPermissions.categoryOf("mcp_server"))
        assertFalse(ToolPermissions.MCP in ToolPermissions.defaults)
        assertTrue(ToolPermissions.MCP in ToolPermissions.all)
        assertTrue(ToolPermissions.MCP in ToolPermissions.risky)
        assertTrue(ToolPermissions.label(ToolPermissions.MCP).contains("MCP"))
        assertTrue(ToolPermissions.describe(ToolPermissions.MCP).contains("默认逐次确认"))
        // MCP 调用不提供“本次会话都允许”，整类放行只能去 /permissions
        assertFalse(ToolPermissions.isSessionAllowable("mcp__github__create_issue", "{}"))
        assertFalse(ToolPermissions.requiresPerCallApproval("mcp__github__create_issue", "{}"))
    }
}
