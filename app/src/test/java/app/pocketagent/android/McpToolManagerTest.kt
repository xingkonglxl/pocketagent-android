package app.pocketagent.android

import app.pocketagent.android.mcp.McpCallResult
import app.pocketagent.android.mcp.McpServerConfig
import app.pocketagent.android.mcp.McpServerStore
import app.pocketagent.android.mcp.McpSession
import app.pocketagent.android.mcp.McpToolInfo
import app.pocketagent.android.mcp.McpResource
import app.pocketagent.android.mcp.McpToolManager
import app.pocketagent.android.security.InMemorySecretStore
import app.pocketagent.android.tools.ToolOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class McpToolManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSession(
        private val serverName: String = "fake",
        private val tools: List<McpToolInfo> = emptyList(),
        private val listError: Exception? = null,
        var callResult: McpCallResult = McpCallResult("ok", false),
        private val resources: List<McpResource> = emptyList(),
        private val resourceText: String = "",
        private val resourceError: Exception? = null,
    ) : McpSession {
        var listCalls = 0
        var lastCall: Pair<String, JsonObject>? = null

        override suspend fun initialize(): String = serverName

        override suspend fun listTools(): List<McpToolInfo> {
            listCalls += 1
            listError?.let { throw it }
            return tools
        }

        override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
            lastCall = name to arguments
            return callResult
        }

        override suspend fun listResources(): List<McpResource> {
            resourceError?.let { throw it }
            return resources
        }

        override suspend fun readResource(uri: String): String {
            resourceError?.let { throw it }
            return resourceText
        }
    }

    private fun store(): McpServerStore = McpServerStore(tmp.newFolder())

    private fun tool(name: String, description: String = ""): McpToolInfo = McpToolInfo(
        name = name,
        description = description,
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
    )

    @Test
    fun `discovers tools and prefixes them with the server id`() = runBlocking {
        val store = store()
        store.save(
            listOf(
                McpServerConfig(id = "alpha", name = "Alpha", url = "https://a.example/mcp"),
                McpServerConfig(id = "beta", name = "Beta", url = "https://b.example/mcp"),
            ),
        )
        val sessions = mapOf(
            "https://a.example/mcp" to FakeSession(tools = listOf(tool("search", "搜索文档"))),
            "https://b.example/mcp" to FakeSession(tools = listOf(tool("write", "写文件"))),
        )
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { server, _ -> sessions.getValue(server.url) })

        val summary = manager.ensureFresh()
        assertEquals(2, summary.servers)
        // 2 个服务器各 1 个工具 + 2 个公共的资源工具
        assertEquals(4, summary.tools)
        assertTrue(summary.errors.isEmpty())
        val names = manager.tools().map { it.spec.name }
        assertEquals(
            listOf("mcp__alpha__search", "mcp__beta__write", "mcp__resources__list", "mcp__resources__read"),
            names,
        )
        val search = manager.tools().first { it.spec.name == "mcp__alpha__search" }
        assertTrue(search.spec.description.startsWith("[MCP Alpha]"))
        assertTrue(search.spec.description.contains("搜索文档"))
        assertTrue(search.spec.parametersJson.contains("\"object\""))
    }

    @Test
    fun `disabled and blank servers are skipped`() = runBlocking {
        val store = store()
        store.save(
            listOf(
                McpServerConfig(id = "off", name = "Off", url = "https://off.example/mcp", enabled = false),
                McpServerConfig(id = "blank", name = "Blank", url = ""),
                McpServerConfig(id = "on", name = "On", url = "https://on.example/mcp"),
            ),
        )
        var created = 0
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { _: McpServerConfig, _: String? ->
            created += 1
            FakeSession(tools = listOf(tool("ping")))
        })

        val summary = manager.ensureFresh()
        assertEquals(1, created)
        assertEquals(1, summary.servers)
        val names = manager.tools().map { it.spec.name }
        assertTrue(names.contains("mcp__on__ping"))
        assertFalse(names.any { it.startsWith("mcp__off__") || it.startsWith("mcp__blank__") })
    }

    @Test
    fun `one broken server does not drop the others`() = runBlocking {
        val store = store()
        store.save(
            listOf(
                McpServerConfig(id = "bad", name = "Bad", url = "https://bad.example/mcp"),
                McpServerConfig(id = "good", name = "Good", url = "https://good.example/mcp"),
            ),
        )
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { server, _ ->
            if (server.id == "bad") FakeSession(listError = IllegalStateException("HTTP 500"))
            else FakeSession(tools = listOf(tool("ok")))
        })

        val summary = manager.ensureFresh()
        val names = manager.tools().map { it.spec.name }
        assertEquals("mcp__good__ok", names.first())
        assertFalse(names.any { it.startsWith("mcp__bad__") })
        assertEquals(1, summary.errors.size)
        assertTrue(summary.message().contains("Bad"))
        assertTrue(summary.message().contains("1/2"))
    }

    @Test
    fun `discovery is cached until invalidated`() = runBlocking {
        val store = store()
        store.save(listOf(McpServerConfig(id = "s", name = "S", url = "https://s.example/mcp")))
        val session = FakeSession(tools = listOf(tool("one")))
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { _, _ -> session })

        manager.ensureFresh()
        manager.ensureFresh()
        assertEquals(1, session.listCalls)

        manager.invalidate()
        manager.ensureFresh()
        assertEquals(2, session.listCalls)
    }

    @Test
    fun `duplicate remote names get unique tool names`() = runBlocking {
        val store = store()
        store.save(listOf(McpServerConfig(id = "srv", name = "One", url = "https://1.example/mcp")))
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { _, _ ->
            FakeSession(tools = listOf(tool("search"), tool("search")))
        })
        val summary = manager.ensureFresh()
        val names = manager.tools().map { it.spec.name }
        assertEquals(names.size, names.distinct().size)
        assertEquals(4, summary.tools)
        assertEquals(
            listOf("mcp__srv__search", "mcp__srv__search_2", "mcp__resources__list", "mcp__resources__read"),
            names,
        )
    }

    @Test
    fun `stored token is handed to the client factory`() = runBlocking {
        val store = store()
        val server = McpServerConfig(id = "tok", name = "Tok", url = "https://t.example/mcp")
        store.save(listOf(server))
        val secrets = InMemorySecretStore()
        secrets.put(server.tokenId, "abc123")
        var seen: String? = null
        val manager = McpToolManager(store, secrets, clientFactory = { _, token ->
            seen = token
            FakeSession(tools = listOf(tool("x")))
        })
        manager.ensureFresh()
        assertEquals("abc123", seen)
    }

    @Test
    fun `tool execution forwards arguments and error flag`() = runBlocking {
        val store = store()
        store.save(listOf(McpServerConfig(id = "s", name = "S", url = "https://s.example/mcp")))
        val session = FakeSession(tools = listOf(tool("search")))
        session.callResult = McpCallResult("服务器错误：参数缺失", true)
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { _, _ -> session })
        manager.ensureFresh()

        val outcome = manager.tools().first { it.spec.name == "mcp__s__search" }.execute("""{"q":"kotlin"}""")
        assertTrue(outcome is ToolOutcome.Error)
        assertTrue((outcome as ToolOutcome.Error).message.contains("服务器错误"))
        assertEquals("search", session.lastCall?.first)
        assertEquals("kotlin", session.lastCall?.second?.get("q")?.toString()?.trim('"'))
    }

    @Test
    fun `invalid arguments are rejected before hitting the server`() = runBlocking {
        val store = store()
        store.save(listOf(McpServerConfig(id = "s", name = "S", url = "https://s.example/mcp")))
        val session = FakeSession(tools = listOf(tool("search")))
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { _, _ -> session })
        manager.ensureFresh()

        val outcome = manager.tools().first { it.spec.name == "mcp__s__search" }.execute("not-json")
        assertTrue(outcome is ToolOutcome.Error)
        assertTrue((outcome as ToolOutcome.Error).message.contains("JSON"))
        assertEquals(null, session.lastCall)
    }

    @Test
    fun `config store round trip keeps tokens out of the json file`() {
        val dir = tmp.newFolder()
        val store = McpServerStore(dir)
        store.save(listOf(McpServerConfig(id = "a", name = "A", url = "https://a.example/mcp")))
        val text = File(dir, "mcp_servers.json").readText()
        assertTrue(text.contains("https://a.example/mcp"))
        assertTrue(!text.contains("token", ignoreCase = true))
        assertEquals(1, store.load().size)
    }

    @Test
    fun `resource tools list and read resources`() = runBlocking {
        val store = store()
        store.save(listOf(McpServerConfig(id = "r", name = "Docs", url = "https://r.example/mcp")))
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { _, _ ->
            FakeSession(
                tools = listOf(tool("search")),
                resources = listOf(McpResource("file:///a.md", "A", "text/markdown", "文档")),
                resourceText = "# hi",
            )
        })
        manager.ensureFresh()

        val names = manager.tools().map { it.spec.name }
        assertTrue(names.contains("mcp__resources__list"))
        assertTrue(names.contains("mcp__resources__read"))

        val listTool = manager.tools().first { it.spec.name == "mcp__resources__list" }
        val listed = listTool.execute("{}")
        assertTrue(listed is ToolOutcome.Ok)
        assertTrue((listed as ToolOutcome.Ok).text.contains("file:///a.md"))
        assertTrue(listed.text.contains("Docs"))

        val readTool = manager.tools().first { it.spec.name == "mcp__resources__read" }
        val read = readTool.execute("""{"server":"Docs","uri":"file:///a.md"}""")
        assertTrue(read is ToolOutcome.Ok)
        assertTrue((read as ToolOutcome.Ok).text.contains("# hi"))
    }

    @Test
    fun `resource tool explains unsupported servers and unknown names`() = runBlocking {
        val store = store()
        store.save(listOf(McpServerConfig(id = "r", name = "Docs", url = "https://r.example/mcp")))
        val manager = McpToolManager(store, InMemorySecretStore(), clientFactory = { _, _ ->
            FakeSession(
                tools = listOf(tool("search")),
                resourceError = IllegalStateException("该服务器不支持 resources/list（只提供工具）"),
            )
        })
        manager.ensureFresh()

        val listTool = manager.tools().first { it.spec.name == "mcp__resources__list" }
        val listed = listTool.execute("{}") as ToolOutcome.Ok
        assertTrue(listed.text.contains("不支持 resources/list"))

        val unknown = listTool.execute("""{"server":"nope"}""")
        assertTrue(unknown is ToolOutcome.Error)
        assertTrue((unknown as ToolOutcome.Error).message.contains("找不到"))

        val readTool = manager.tools().first { it.spec.name == "mcp__resources__read" }
        val missing = readTool.execute("""{"server":"Docs"}""")
        assertTrue(missing is ToolOutcome.Error)
        assertTrue((missing as ToolOutcome.Error).message.contains("uri"))
    }
}
