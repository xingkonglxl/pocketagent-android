package app.pocketagent.android

import app.pocketagent.android.remote.RemoteInputShell
import app.pocketagent.android.remote.RemoteOperations
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.RemoteShell
import app.pocketagent.android.remote.RemoteStreamingShell
import app.pocketagent.android.remote.SshHost
import app.pocketagent.android.tools.RemoteCodexTool
import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.parseImageDataUrl
import app.pocketagent.android.tools.parseRemoteCodexProgress
import app.pocketagent.android.tools.parseRemoteCodexEvents
import app.pocketagent.android.ui.chat.shouldUseRemoteCodexHarness
import app.pocketagent.android.ui.chat.chooseRemoteResume
import app.pocketagent.android.ui.chat.remoteResumeUnavailable
import app.pocketagent.android.ui.chat.workspaceHostFromRemoteSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCodexToolTest {
    private val transcript = """
        {"type":"thread.started","thread_id":"thread-1"}
        {"type":"turn.started"}
        {"type":"item.completed","item":{"type":"reasoning","text":"先检查 workspace"}}
        {"type":"item.completed","item":{"type":"agent_message","text":"远端完成"}}
        {"type":"turn.completed","usage":{"input_tokens":12,"output_tokens":3}}
    """.trimIndent()

    private class InputShell(private val result: RemoteResult) : RemoteInputShell {
        var command = ""
        var prompt = ""
        val execCommands = mutableListOf<String>()
        val inputCommands = mutableListOf<String>()
        val inputs = mutableListOf<ByteArray>()
        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult {
            execCommands += command
            return result
        }
        override suspend fun execWithInput(
            command: String, input: ByteArray, timeoutMs: Long,
        ): RemoteResult {
            inputCommands += command
            inputs += input.copyOf()
            this.command = command
            prompt = input.toString(Charsets.UTF_8)
            return result
        }
        override suspend fun close() {}
    }

    private class StreamingInputShell(private val result: RemoteResult) : RemoteStreamingShell {
        val lines = result.stdout.lineSequence().filter { it.isNotBlank() }.toList()
        var command = ""
        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult = result
        override suspend fun execWithInput(
            command: String, input: ByteArray, timeoutMs: Long,
        ): RemoteResult = result
        override suspend fun execStreaming(
            command: String,
            input: ByteArray,
            timeoutMs: Long,
            onStdoutLine: (String) -> Unit,
        ): RemoteResult {
            this.command = command
            lines.forEach(onStdoutLine)
            return result
        }
        override suspend fun close() {}
    }

    private fun operations(shell: RemoteShell) = object : RemoteOperations {
        override fun hosts(): List<SshHost> = emptyList()
        override fun host(id: String?): SshHost? = null
        override suspend fun shell(id: String?): RemoteShell = shell
        override suspend fun exec(command: String, timeoutMs: Long, hostId: String?): RemoteResult =
            RemoteResult(0)
    }

    @Test
    fun `detects installed codex and distinguishes absent executable`() = runBlocking {
        assertTrue(RemoteCodexTool(operations(InputShell(RemoteResult(0, "codex-cli 0.166.0"))), { null }, { "~" }).isAvailable("server"))
        assertEquals(false, RemoteCodexTool(
            operations(InputShell(RemoteResult(127, "POCKETAGENT_CODEX_NOT_FOUND\n"))), { null }, { "~" },
        ).isAvailable("server"))
    }

    @Test
    fun `timeout disconnected ssh and broken node never silently fall back to phone`() = runBlocking {
        for (failure in listOf(
            RemoteResult.timeout(20_000),
            RemoteResult(255, stderr = "SSH disconnected"),
            RemoteResult(127, stderr = "env: node: No such file or directory"),
            RemoteResult(1, stderr = "Invalid configuration"),
        )) {
            val outcome = runCatching {
                RemoteCodexTool(operations(InputShell(failure)), { null }, { "~" }).isAvailable("server")
            }
            assertTrue("Must report detection failure for ${failure.exitCode}", outcome.isFailure)
        }
    }

    @Test
    fun `parses codex exec jsonl into final answer and session`() {
        val parsed = parseRemoteCodexEvents(RemoteResult(0, transcript))
        assertEquals("thread-1", parsed.threadId)
        assertEquals(listOf("先检查 workspace"), parsed.reasoning)
        assertEquals("远端完成", parsed.finalText)
        assertEquals(12 to 3, parsed.usage)
    }

    @Test
    fun `delegates prompt to remote codex with workspace defaults`() = runBlocking {
        val shell = InputShell(RemoteResult(0, transcript))
        val tool = RemoteCodexTool(
            operations(shell),
            workspaceHostId = { "mac" },
            workspaceDir = { "/Users/example/project" },
        )

        val result = tool.execute("""{"prompt":"检查并修复项目"}""") as ToolOutcome.Ok

        assertTrue(result.text.contains("远端完成"))
        assertEquals("检查并修复项目", shell.prompt)
        assertTrue(shell.command.contains("codex"))
        assertTrue(shell.command.contains("--json"))
        assertTrue(shell.command.contains("/Users/example/project"))
        assertTrue(shell.command.contains("PATH=\"\$CODEX_DIR:\$PATH\""))
        assertTrue(shell.command.contains("mkfifo"))
        assertTrue(shell.command.contains("awk"))
    }

    @Test
    fun `resume uses the requested session and workdir`() = runBlocking {
        val shell = InputShell(RemoteResult(0, transcript))
        val tool = RemoteCodexTool(operations(shell), { "mac" }, { "~" })

        tool.execute(
            """{"prompt":"继续","resume":"thread-1","cwd":"/tmp/work","model":"deepseek-v4-flash","reasoning_effort":"max"}""",
        )

        assertTrue(shell.command.contains("exec resume"))
        assertTrue(shell.command.contains("--all"))
        assertTrue(shell.command.contains("thread-1"))
        assertTrue(shell.command.contains("/tmp/work"))
        // 回归：cd 子 shell 必须包住整个 codex 调用，不能拼成 `codex (cd ...)`。
        assertTrue(
            "工作目录应包住 codex 命令：${shell.command}",
            shell.command.contains("(cd '/tmp/work' && {\n\"\$CODEX_BIN\" exec resume"),
        )
        assertEquals(false, shell.command.contains("\"\$CODEX_BIN\" (cd"))
        // 老版本 Codex 的 exec resume 不认识 --approve-for-me；保持命令只使用跨版本公共参数。
        assertEquals(false, shell.command.contains("--approve-for-me"))
        assertTrue(shell.command.contains("deepseek-v4-flash"))
        assertTrue(shell.command.contains("model_reasoning_effort"))
    }

    @Test
    fun `local-only workspace rejects remote codex explicitly`() = runBlocking {
        val shell = InputShell(RemoteResult(0, transcript))
        val tool = RemoteCodexTool(operations(shell), { null }, { "" })
        val result = tool.execute("""{"prompt":"检查"}""") as ToolOutcome.Error
        assertTrue(result.message.contains("先把工作区切到远端"))
    }

    @Test
    fun `tilde workspace is expanded before codex changes directory`() = runBlocking {
        val shell = InputShell(RemoteResult(0, transcript))
        val tool = RemoteCodexTool(operations(shell), { "mac" }, { "~/pocketagent" })

        tool.execute("""{"prompt":"检查"}""")

        assertTrue(shell.command.contains("\"\$HOME\"/'pocketagent'"))
        assertEquals(false, shell.command.contains("'~/pocketagent'"))
    }

    @Test
    fun `reasoning is only surfaced when requested`() {
        assertEquals(
            emptyList<String>(),
            parseRemoteCodexEvents(RemoteResult(0, transcript), includeReasoning = false).reasoning,
        )
        assertEquals(
            listOf("先检查 workspace"),
            parseRemoteCodexEvents(RemoteResult(0, transcript), includeReasoning = true).reasoning,
        )
    }

    @Test
    fun `streaming shell reports thread and tool progress before completion`() = runBlocking {
        val streaming = """
            {"type":"thread.started","thread_id":"thread-1"}
            {"type":"turn.started"}
            {"type":"item.started","item":{"type":"command_execution","command":"swift test"}}
            {"type":"item.completed","item":{"type":"command_execution","command":"swift test"}}
            {"type":"item.completed","item":{"type":"agent_message","text":"全部通过"}}
            {"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":4}}
        """.trimIndent()
        val shell = StreamingInputShell(RemoteResult(0, streaming))
        val tool = RemoteCodexTool(operations(shell), { "mac" }, { "/tmp" })
        val progress = mutableListOf<String>()

        val result = tool.run(
            "mac",
            "/tmp",
            "跑测试",
            null,
            onProgress = { progress += it.text },
        )

        assertEquals("全部通过", result.finalText)
        assertTrue(progress.any { it.contains("已连接远端 Codex session") })
        assertTrue(progress.any { it.contains("正在执行命令：swift test") })
        assertTrue(progress.any { it.contains("命令已完成：swift test") })
        assertTrue(progress.any { it.contains("已生成回答") })
        assertTrue(shell.command.contains("mkfifo"))
    }

    @Test
    fun `progress parser ignores unknown and reasoning completion events`() {
        assertEquals(
            "正在执行命令：npm test",
            parseRemoteCodexProgress(
                """{"type":"item.started","item":{"type":"command_execution","command":"npm test"}}""",
            )?.text,
        )
        assertEquals(
            "正在调用 MCP：github/search",
            parseRemoteCodexProgress(
                """{"type":"item.started","item":{"type":"mcp_tool_call","server":"github","tool":"search"}}""",
            )?.text,
        )
        assertEquals(
            null,
            parseRemoteCodexProgress(
                """{"type":"item.completed","item":{"type":"reasoning","text":"done"}}""",
            ),
        )
        assertEquals(null, parseRemoteCodexProgress("not json"))
    }

    @Test
    fun `requested reasoning streams directly without the remote filter`() = runBlocking {
        val shell = InputShell(RemoteResult(0, transcript))
        val tool = RemoteCodexTool(operations(shell), { "mac" }, { "/tmp" })

        val result = tool.run("mac", "/tmp", "检查", null, includeReasoning = true)

        assertEquals(listOf("先检查 workspace"), result.reasoning)
        assertEquals(false, shell.command.contains("mkfifo"))
    }

    @Test
    fun `undetected hosts wait for actual detection and bound sessions stay remote`() {
        assertEquals(false, shouldUseRemoteCodexHarness(false))
        assertTrue(shouldUseRemoteCodexHarness(true))
        assertEquals(false, shouldUseRemoteCodexHarness(false))
    }

    @Test
    fun `installed linux harness routes remotely regardless of hostname`() {
        assertTrue(shouldUseRemoteCodexHarness(false, true))
        assertTrue(shouldUseRemoteCodexHarness(false, true))
        assertEquals(false, shouldUseRemoteCodexHarness(false, false))
        // A bound native session must never silently fall back to a local agent.
        assertTrue(shouldUseRemoteCodexHarness(true, false))
    }

    @Test
    fun `harness detection survives host storage and older hosts stay undetected`() {
        val old = app.pocketagent.android.data.AppJson.decodeFromString<SshHost>(
            """{"id":"linux","name":"server","host":"192.0.2.20","user":"tester"}""",
        )
        assertEquals(null, old.codexHarnessAvailable)
        val detected = old.copy(codexHarnessAvailable = true)
        val json = app.pocketagent.android.data.AppJson.encodeToString(SshHost.serializer(), detected)
        assertEquals(true, app.pocketagent.android.data.AppJson.decodeFromString<SshHost>(json).codexHarnessAvailable)
    }

    @Test
    fun `remote resume prefers explicit thread then synced conversation identity`() {
        assertEquals(
            "thread-explicit",
            chooseRemoteResume("thread-explicit", null, "mac", "conv", "phone", true),
        )
        assertEquals("conv", chooseRemoteResume(null, null, "mac", "conv", "mac", false))
        assertEquals("conv", chooseRemoteResume(null, null, "mac", "conv", "phone", true))
        assertEquals(null, chooseRemoteResume(null, null, "mac", "conv", "phone", false))
    }

    @Test
    fun `remote thread never resumes on a different host`() {
        assertEquals(
            "thread-1",
            chooseRemoteResume("thread-1", "host-a", "host-a", "conv", "phone", true),
        )
        assertEquals(
            null,
            chooseRemoteResume("thread-1", "host-a", "host-b", "conv", "mac", true),
        )
    }

    @Test
    fun `legacy remote conversation keeps its host after workspace migration`() {
        assertEquals("mac", workspaceHostFromRemoteSession("thread-1", "mac"))
        assertEquals(null, workspaceHostFromRemoteSession(null, "mac"))
        assertEquals(null, workspaceHostFromRemoteSession("thread-1", null))
        assertEquals(null, workspaceHostFromRemoteSession("thread-1", "  "))
    }

    @Test
    fun `only a missing native session falls back to a new remote thread`() {
        val missing = app.pocketagent.android.tools.RemoteCodexEvents(
            ok = false,
            exitCode = 1,
            threadId = null,
            reasoning = emptyList(),
            finalText = "",
            errors = listOf("thread/resume failed: no rollout found for thread id x"),
            usage = null,
        )
        val transient = missing.copy(
            errors = listOf("stream disconnected before completion"),
        )

        assertTrue(remoteResumeUnavailable(missing))
        assertEquals(false, remoteResumeUnavailable(transient))
    }

    @Test
    fun `image attachments are uploaded and passed to remote codex`() = runBlocking {
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47,
            0x0d, 0x0a, 0x1a, 0x0a,
            1, 2, 3,
        )
        val dataUrl = "data:image/png;base64," +
            java.util.Base64.getEncoder().encodeToString(bytes)
        val image = parseImageDataUrl(dataUrl)!!
        assertArrayEquals(bytes, image.bytes)
        assertEquals("png", image.extension)

        val shell = InputShell(RemoteResult(0, transcript))
        val tool = RemoteCodexTool(operations(shell), { "mac" }, { "/tmp" })
        tool.run("mac", "/tmp", "看看这张图", null, images = listOf(image))

        assertTrue(shell.inputCommands.any { it.contains("cat >") })
        assertArrayEquals(bytes, shell.inputs.first())
        assertTrue(shell.command.contains("/tmp/pocketagent-images-"))
        assertTrue(shell.command.contains(" -i "))
        assertTrue(shell.execCommands.any { it.contains("rm -rf --") })
    }
}
