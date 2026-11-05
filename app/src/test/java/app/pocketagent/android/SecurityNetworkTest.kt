package app.pocketagent.android

import app.pocketagent.android.agent.ToolPermissions
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.mcp.McpClient
import app.pocketagent.android.net.*
import app.pocketagent.android.tools.HttpGetTool
import app.pocketagent.android.tools.ToolOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLException

class SecurityNetworkTest {
    private fun dns(block: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String) = block(hostname)
    }
    @Test fun `public network policy rejects private metadata and transition ranges`() {
        for (ip in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.100.100.200", "192.168.1.1", "198.18.0.1", "::1", "fd00::1", "fe80::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::")) {
            assertFalse(ip, isPublicAddress(InetAddress.getByName(ip)))
        }
        for (ip in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) {
            assertTrue(ip, isPublicAddress(InetAddress.getByName(ip)))
        }
        val mixed = PublicOnlyDns(dns { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")) })
        assertTrue(runCatching { mixed.lookup("mixed.invalid") }.exceptionOrNull() is UnknownHostException)
    }

    @Test fun `API URLs cannot disguise public HTTP hosts or userinfo`() {
        for (url in listOf("http://10.attacker.example", "http://172.16.example.com", "http://example.com", "https://user:pass@example.com", "https://", "ftp://example.com")) {
            assertFalse(url, isSecureBaseUrl(url))
            assertTrue(url, runCatching { endpoint(url, "responses") }.isFailure)
        }
        for (url in listOf("https://example.com/v1", "http://localhost:8000", "http://[::1]:8000", "http://10.1.2.3", "http://172.31.2.3", "http://192.168.5.1")) {
            assertTrue(url, isSecureBaseUrl(url))
        }
    }

    @Test fun `escaped credential paths still require approval`() {
        assertTrue(ToolPermissions.needsExtraApproval("ssh_read", """{"path":"~/\u002essh/id_rsa"}"""))
        assertTrue(ToolPermissions.needsExtraApproval("fs_read", """{"path":"~/\u002ecodex/auth.json"}"""))
        assertTrue(ToolPermissions.needsExtraApproval("fs_read", "not-json"))
        assertFalse(ToolPermissions.needsExtraApproval("fs_read", """{"path":"README.md"}"""))
    }

    private fun webClient(server: MockWebServer, dns: Dns): OkHttpClient = OkHttpClient.Builder()
        .dns(dns).socketFactory(object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    super.connect(InetSocketAddress("127.0.0.1", server.port), timeout)
                }
            }
            override fun createSocket(h: String, p: Int): Socket = error("unused")
            override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = error("unused")
            override fun createSocket(h: InetAddress, p: Int): Socket = error("unused")
            override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = error("unused")
        })
        .addInterceptor { chain ->
            // Exercise the HTTP fallback without a TLS fixture.
            if (chain.request().url.isHttps) throw SSLException("test HTTP-only server")
            chain.proceed(chain.request())
        }.retryOnConnectionFailure(false).callTimeout(3, TimeUnit.SECONDS).build()

    @Test fun `public redirect succeeds while redirect into LAN never reaches target`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val publicDns = dns { listOf(InetAddress.getByName("93.184.216.34")) }
            val tool = HttpGetTool(http = webClient(server, publicDns))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/ok"))
            server.enqueue(MockResponse().setBody("public-success"))
            assertTrue(tool.execute("""{"url":"http://public.invalid/start"}""") is ToolOutcome.Ok)
            assertEquals(2, server.requestCount)
            // Real response from the first host attempts a redirect to a literal local address.
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/private"))
            val blocked = HttpGetTool(http = webClient(server, dns { name ->
                listOf(InetAddress.getByName(if (name == "127.0.0.1") name else "93.184.216.34"))
            })).execute("""{"url":"http://public.invalid/redirect"}""")
            assertTrue(blocked is ToolOutcome.Error)
            assertEquals("Only the public redirect source was contacted", 3, server.requestCount)
        }
    }

    @Test fun `DNS rebinding between check and actual connect is rejected`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var lookups = 0
            val dns = dns {
                lookups++
                listOf(InetAddress.getByName(if (lookups <= 2) "93.184.216.34" else "127.0.0.1"))
            }
            val result = HttpGetTool(http = webClient(server, dns))
                .execute("""{"url":"http://rebind.invalid/page"}""")
            assertTrue(result is ToolOutcome.Error)
            assertTrue(lookups >= 3)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `MCP redirects never forward bearer or session headers`() = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.start(); target.start()
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/stolen")))
            val outcome = runCatching { McpClient(source.url("/mcp").toString(), token = "fixture-secret").initialize() }
            assertTrue(outcome.isFailure)
            assertEquals("Bearer fixture-secret", source.takeRequest().getHeader("Authorization"))
            assertEquals(0, target.requestCount)
        } }
        assertTrue(runCatching { McpClient("http://10.attacker.example/mcp").initialize() }.isFailure)
    }

    @Test fun `Anthropic redirect never forwards custom API key header`() = runBlocking {
        MockWebServer().use { source -> MockWebServer().use { target ->
            source.start(); target.start()
            source.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/stolen")))
            val provider = Provider(name = "fixture", kind = ProviderKind.ANTHROPIC,
                baseUrl = source.url("/").toString(), model = "fixture")
            runCatching { AnthropicClient().stream(LlmConfig(provider, "fixture-secret"), "", listOf(ApiMessage("user", "hi")), emptyList()).toList() }
            assertEquals("fixture-secret", source.takeRequest().getHeader("x-api-key"))
            assertEquals(0, target.requestCount)
        } }
    }
}
