package app.pocketagent.android

import app.pocketagent.android.agent.TurnSteering
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role
import app.pocketagent.android.remote.RemoteOperations
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.RemoteShell
import app.pocketagent.android.remote.SshHost
import app.pocketagent.android.remote.SshRemoteShell
import app.pocketagent.android.remote.TofuHostKeyVerifier
import app.pocketagent.android.tools.RemoteCodexTool
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.PublicKey

/** Opt in with scripts/ssh_loopback_server.py and POCKETAGENT_SSH_LOOPBACK_STATE. */
class SshDuplexIntegrationTest {
    private fun shell(): SshRemoteShell {
        val stateFile = System.getenv("POCKETAGENT_SSH_LOOPBACK_STATE")
        assumeTrue("Run the isolated loopback SSH server to enable the transport integration tests", stateFile != null)
        val state = Json.parseToJsonElement(File(stateFile!!).readText()).jsonObject
        val port = state.getValue("port").jsonPrimitive.content.toInt()
        val fingerprint = state.getValue("fingerprint").jsonPrimitive.content
        val fixtureKey = File(state.getValue("clientKeyFile").jsonPrimitive.content).readText()
        return SshRemoteShell(
            SshHost(host = "127.0.0.1", port = port, user = "pocketagent-test", privateKeyPem = fixtureKey, workDir = "/tmp"),
            hostKeyVerifier = object : HostKeyVerifier {
                override fun verify(hostname: String, actualPort: Int, key: PublicKey): Boolean =
                    hostname == "127.0.0.1" && actualPort == port && TofuHostKeyVerifier.fingerprintOf(key) == fingerprint
                override fun findExistingAlgorithms(hostname: String, actualPort: Int): List<String> = emptyList()
            },
        )
    }

    @Test
    fun `real SSHJ duplex remains open past 100ms and receives the delayed response`() = runBlocking {
        val shell = shell()
        val input = Channel<ByteArray>(1)
        input.send("{\"id\":1,\"method\":\"initialize\"}\n".toByteArray())
        input.close()
        val lines = mutableListOf<String>()
        try {
            val result = shell.execDuplex(
                "IFS= read -r request; sleep 0.4; printf '%s\\n' \"\$request\"", 5_000, input,
            ) { lines += it }
            assertTrue(result.summary(), result.ok)
            assertTrue(lines.toString(), lines.any { it.contains("initialize") })
        } finally { shell.close() }
    }

    @Test
    fun `real SSHJ duplex times out at its task deadline and permits reconnection`() = runBlocking {
        val shell = shell()
        val input = Channel<ByteArray>(1)
        try {
            val result = shell.execDuplex("sleep 5", 300, input) { }
            assertTrue(result.summary(), result.timedOut)
            assertEquals("reconnected", shell.exec("printf reconnected", 5_000).stdout)
        } finally { input.close(); shell.close() }
    }

    @Test
    fun `cancelling a real SSHJ duplex permits another command`() = runBlocking {
        val shell = shell()
        val input = Channel<ByteArray>(1)
        try {
            val task = async { shell.execDuplex("sleep 5", 10_000, input) { } }
            delay(400)
            task.cancelAndJoin()
            assertEquals("after cancellation", shell.exec("printf 'after cancellation'", 5_000).stdout)
        } finally { input.close(); shell.close() }
    }

    @Test
    fun `real SSHJ production wrapper initializes starts steers and confirms a complete session`() = runBlocking {
        val shell = shell()
        val directory = Files.createTempDirectory("pocketagent-rpc-fixture-").toFile()
        try {
            val executable = File(directory, "codex-fixture")
            executable.writeText("""
                #!/usr/bin/env python3
                import json, sys, time
                if '--help' in sys.argv:
                    sys.exit(0)
                def send(node):
                    print(json.dumps(node), flush=True)
                for line in sys.stdin:
                    node = json.loads(line)
                    method = node.get('method')
                    if method == 'initialize':
                        time.sleep(0.4)
                        send({'id':node['id'],'result':{'userAgent':'isolated-fixture'}})
                    elif method == 'thread/start':
                        send({'id':node['id'],'result':{'thread':{'id':'thread-loopback','turns':[]}}})
                    elif method == 'turn/start':
                        turn={'id':'turn-loopback','status':'inProgress','items':[]}
                        send({'id':node['id'],'result':{'turn':turn}})
                        send({'method':'turn/started','params':{'threadId':'thread-loopback','turn':turn}})
                    elif method == 'turn/steer':
                        assert node['params']['expectedTurnId'] == 'turn-loopback'
                        assert node['params']['input'][0]['text'] == '补充方向'
                        send({'id':node['id'],'result':{'turnId':'turn-loopback'}})
                        send({'method':'item/completed','params':{'threadId':'thread-loopback','turnId':'turn-loopback','item':{'id':'native-user','type':'userMessage','clientId':node['params']['clientUserMessageId']}}})
                        send({'method':'item/completed','params':{'threadId':'thread-loopback','turnId':'turn-loopback','item':{'id':'answer','type':'agentMessage','phase':'final_answer','text':'LOOPBACK_STEER_OK'}}})
                        send({'method':'turn/completed','params':{'threadId':'thread-loopback','turn':{'id':'turn-loopback','status':'completed','items':[]}}})
            """.trimIndent() + "\n")
            assertTrue(executable.setExecutable(true))
            val host = SshHost(id = "loopback", host = "127.0.0.1", user = "pocketagent-test", codexExecutable = executable.path)
            val operations = object : RemoteOperations {
                override fun hosts(): List<SshHost> = listOf(host)
                override fun host(id: String?): SshHost = host
                override suspend fun shell(id: String?): RemoteShell = shell
                override suspend fun exec(command: String, timeoutMs: Long, hostId: String?): RemoteResult = shell.exec(command, timeoutMs)
            }
            val inbox = TurnSteering()
            val supplement = ChatMessage(id = "loopback-supplement", role = Role.USER, text = "补充方向")
            val applied = mutableListOf<ChatMessage>()
            val result = RemoteCodexTool(operations, { host.id }, { directory.path }).run(
                host.id, directory.path, "原始方向", null, steering = inbox,
                onProgress = { if (it.text.contains("开始处理")) inbox.offer(supplement) },
                onSteeringApplied = { applied += it },
                timeout = 10_000,
            )
            assertTrue(result.errors.toString(), result.ok)
            assertEquals("LOOPBACK_STEER_OK", result.finalText)
            assertEquals(listOf(supplement), applied)
            assertTrue(inbox.peek().isEmpty())
        } finally { shell.close(); directory.deleteRecursively() }
    }
}
