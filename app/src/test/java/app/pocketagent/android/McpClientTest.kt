package app.pocketagent.android

import app.pocketagent.android.mcp.McpClient
import app.pocketagent.android.mcp.McpException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpClientTest {

    private fun json(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun sse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun accepted(): MockResponse = MockResponse().setResponseCode(202)

    private fun initializeBody(id: Int, name: String = "demo") = """
        {"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18",
        "capabilities":{"tools":{}},"serverInfo":{"name":"$name","version":"1.0"}}}
    """.trimIndent()

    @Test
    fun `initialize then list tools over plain JSON`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(json(initializeBody(1)))
        server.enqueue(accepted())
        server.enqueue(
            json(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                   {"name":"search","description":"搜索文档",
                    "inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}}
                ]}}""",
            ),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString())
            val name = client.initialize()
            assertEquals("demo", name)
            val tools = client.listTools()
            assertEquals(1, tools.size)
            assertEquals("search", tools[0].name)
            assertEquals("搜索文档", tools[0].description)
            assertTrue(tools[0].inputSchema.toString().contains("\"q\""))

            val initializeRequest = server.takeRequest()
            assertTrue(initializeRequest.body.readUtf8().contains("initialize"))
            server.takeRequest() // notifications/initialized
            val listRequest = server.takeRequest()
            assertTrue(listRequest.body.readUtf8().contains("tools/list"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `session id and bearer token are forwarded on later requests`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            json(initializeBody(1)).setHeader("Mcp-Session-Id", "sess-42"),
        )
        server.enqueue(accepted())
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""))
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString(), token = "secret-token")
            client.initialize()
            client.listTools()

            val init = server.takeRequest()
            assertEquals("Bearer secret-token", init.getHeader("Authorization"))
            assertEquals("application/json, text/event-stream", init.getHeader("Accept"))
            server.takeRequest()
            val list = server.takeRequest()
            assertEquals("sess-42", list.getHeader("Mcp-Session-Id"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tool results are read from an SSE stream`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            sse(
                "event: message\n" +
                    "data: " + initializeBody(1, "sse-server").replace("\n", "") + "\n\n",
            ),
        )
        server.enqueue(accepted())
        server.enqueue(
            sse(
                "event: message\n" +
                    "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[" +
                    "{\"name\":\"ping\",\"description\":\"ping\"}]}}\n\n",
            ),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString())
            assertEquals("sse-server", client.initialize())
            val tools = client.listTools()
            assertEquals(listOf("ping"), tools.map { it.name })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tools call joins text parts and reports isError`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(json(initializeBody(1)))
        server.enqueue(accepted())
        server.enqueue(
            json(
                """{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[
                   {"type":"text","text":"第一段"},
                   {"type":"text","text":"第二段"},
                   {"type":"image","mimeType":"image/png","data":"AAAA"}
                ]}}""",
            ),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString())
            val arguments: JsonObject = buildJsonObject { put("q", "kotlin") }
            val result = client.callTool("search", arguments)
            assertTrue(result.isError)
            assertTrue(result.text.contains("第一段"))
            assertTrue(result.text.contains("第二段"))
            assertTrue(result.text.contains("MCP 返回图片"))
            server.takeRequest() // initialize
            server.takeRequest() // notifications/initialized
            val call = server.takeRequest()
            val body = call.body.readUtf8()
            assertTrue(body.contains("tools/call"))
            assertTrue(body.contains("\"q\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `jsonrpc error becomes a readable exception`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            json("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}"""),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString())
            try {
                client.initialize()
                fail("应该抛异常")
            } catch (e: McpException) {
                assertTrue(e.message.orEmpty().contains("Method not found"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `401 explains the token without echoing it`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString(), token = "top-secret")
            try {
                client.initialize()
                fail("应该抛异常")
            } catch (e: McpException) {
                val message = e.message.orEmpty()
                assertTrue(message.contains("401"))
                assertTrue(message.contains("访问令牌"))
                assertTrue("错误信息里不能回显令牌", !message.contains("top-secret"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `expired session is re-initialized once`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(json(initializeBody(1)).setHeader("Mcp-Session-Id", "s1"))
        server.enqueue(accepted())
        server.enqueue(MockResponse().setResponseCode(404).setBody("session not found"))
        server.enqueue(json(initializeBody(3)).setHeader("Mcp-Session-Id", "s2"))
        server.enqueue(accepted())
        server.enqueue(
            json("""{"jsonrpc":"2.0","id":4,"result":{"tools":[{"name":"after-retry"}]}}"""),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString())
            client.initialize()
            val tools = client.listTools()
            assertEquals(listOf("after-retry"), tools.map { it.name })

            val bodies = generateSequence { runCatching { server.takeRequest() }.getOrNull() }
                .take(6)
                .map { it.body.readUtf8() }
                .toList()
            assertEquals(2, bodies.count { it.contains("\"initialize\"") })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `resources can be listed and read`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(json(initializeBody(1)))
        server.enqueue(accepted())
        server.enqueue(
            json(
                """{"jsonrpc":"2.0","id":2,"result":{"resources":[
                   {"uri":"file:///docs/a.md","name":"A","mimeType":"text/markdown","description":"文档 A"}
                ]}}""",
            ),
        )
        server.enqueue(
            json(
                """{"jsonrpc":"2.0","id":3,"result":{"contents":[
                   {"uri":"file:///docs/a.md","text":"# hi"}
                ]}}""",
            ),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString())
            client.initialize()
            val resources = client.listResources()
            assertEquals(1, resources.size)
            assertEquals("file:///docs/a.md", resources[0].uri)
            assertEquals("text/markdown", resources[0].mimeType)
            assertEquals("# hi", client.readResource("file:///docs/a.md"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `server without resources support says so`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(json(initializeBody(1)))
        server.enqueue(accepted())
        server.enqueue(
            json("""{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found"}}"""),
        )
        server.start()
        try {
            val client = McpClient(server.url("/mcp").toString())
            client.initialize()
            try {
                client.listResources()
                fail("应该抛异常")
            } catch (e: McpException) {
                assertTrue(e.message.orEmpty().contains("不支持 resources/list"))
            }
        } finally {
            server.shutdown()
        }
    }
}
