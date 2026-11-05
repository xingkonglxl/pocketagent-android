package app.pocketagent.android

import app.pocketagent.android.agent.ToolPermissions
import app.pocketagent.android.tools.ApplyPatchTool
import app.pocketagent.android.tools.FsDeleteTool
import app.pocketagent.android.tools.FsEditTool
import app.pocketagent.android.tools.FsListTool
import app.pocketagent.android.tools.FsGlobTool
import app.pocketagent.android.tools.FsSearchTool
import app.pocketagent.android.tools.FsReadTool
import app.pocketagent.android.tools.LocalWorkspace
import app.pocketagent.android.tools.ShellExecTool
import app.pocketagent.android.tools.SkillReadTool
import app.pocketagent.android.tools.FsWriteTool
import app.pocketagent.android.tools.HttpGetTool
import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.ToolRegistry
import app.pocketagent.android.tools.ToolSchemas
import app.pocketagent.android.tools.Tool
import app.pocketagent.android.tools.Workspace
import app.pocketagent.android.tools.resolveInside
import app.pocketagent.android.net.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun patchArguments(patch: String): String =
    buildJsonObject { put("patch", patch) }.toString()

private suspend fun applyPatch(root: File, patch: String): ToolOutcome =
    ApplyPatchTool(LocalWorkspace(root)).execute(patchArguments(patch))

private class FailingWriteWorkspace(
    private val delegate: Workspace,
    private val failPath: String,
) : Workspace by delegate {
    private var failed = false

    override suspend fun write(path: String, content: String) {
        if (path == failPath && !failed) {
            failed = true
            throw java.io.IOException("模拟磁盘写入失败")
        }
        delegate.write(path, content)
    }
}

private class FailingAddWorkspace(
    private val delegate: Workspace,
    private val failPath: String,
) : Workspace by delegate {
    override suspend fun write(path: String, content: String) {
        if (path == failPath) {
            delegate.write(path, "partial\n")
            throw java.io.IOException("模拟 Add 写入中断")
        }
        delegate.write(path, content)
    }
}

class ToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `write read list delete`() = runTest {
        val root = tmp.newFolder()
        val write = FsWriteTool(root)
        val result = write.execute("""{"path":"notes/a.txt","content":"hello"}""")
        assertTrue(result is ToolOutcome.Ok)
        assertEquals("hello", File(root, "notes/a.txt").readText())

        val read = FsReadTool(root).execute("""{"path":"notes/a.txt"}""")
        assertEquals("hello", (read as ToolOutcome.Ok).text)

        val list = FsListTool(root).execute("""{"path":"."}""")
        assertTrue((list as ToolOutcome.Ok).text.contains("notes"))

        val del = FsDeleteTool(root).execute("""{"path":"notes/a.txt"}""")
        assertTrue(del is ToolOutcome.Ok)
        assertFalse(File(root, "notes/a.txt").exists())
    }

    @Test
    fun `path traversal is blocked`() = runTest {
        val root = tmp.newFolder()
        val outcome = FsReadTool(root).execute("""{"path":"../../etc/hosts"}""")
        assertTrue(outcome is ToolOutcome.Error)
        assertTrue((outcome as ToolOutcome.Error).message.contains("路径越界"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolveInside rejects sibling prefix`() {
        val root = tmp.newFolder("root")
        File(tmp.root, "root-evil").mkdirs()
        resolveInside(root, "../root-evil/x.txt")
    }

    @Test
    fun `read truncates large files`() = runTest {
        val root = tmp.newFolder()
        File(root, "big.txt").writeText("x".repeat(300 * 1024))
        val outcome = FsReadTool(root).execute("""{"path":"big.txt"}""") as ToolOutcome.Ok
        assertTrue(outcome.text.contains("已截断"))
        assertTrue(outcome.text.contains("next_offset="))
        assertTrue(outcome.text.contains("offset=262144"))
        assertTrue(outcome.text.length < 300 * 1024)
    }

    @Test
    fun `glob and search find files without walking manually`() = runTest {
        val root = tmp.newFolder()
        File(root, "src").mkdirs()
        File(root, "src/a.kt").writeText("fun main() { println(\"MARKER-42\") }")
        File(root, "src/b.md").writeText("无关内容")
        File(root, "README.md").writeText("nothing here")
        val workspace = app.pocketagent.android.tools.LocalWorkspace(root)

        val glob = FsGlobTool(workspace).execute("""{"pattern":"**/*.kt"}""") as ToolOutcome.Ok
        assertTrue(glob.text.contains("src/a.kt"))
        assertTrue(!glob.text.contains("README.md"))

        val search = FsSearchTool(workspace).execute("""{"query":"MARKER-42"}""") as ToolOutcome.Ok
        assertTrue(search.text.contains("src/a.kt:1"))
        assertTrue(!search.text.contains("b.md"))
    }

    @Test
    fun `fs_edit replaces only unique snippets unless all is set`() = runTest {
        val root = tmp.newFolder()
        val workspace = app.pocketagent.android.tools.LocalWorkspace(root)
        File(root, "a.txt").writeText("line1\nTARGET\nline3\n")

        val ok = FsEditTool(workspace).execute("""{"path":"a.txt","find":"TARGET","replace":"DONE"}""")
        assertTrue(ok is ToolOutcome.Ok)
        assertEquals("line1\nDONE\nline3\n", File(root, "a.txt").readText())

        File(root, "b.txt").writeText("X\nX\n")
        val ambiguous = FsEditTool(workspace).execute("""{"path":"b.txt","find":"X","replace":"Y"}""")
        assertTrue(ambiguous is ToolOutcome.Error)
        assertEquals("X\nX\n", File(root, "b.txt").readText())

        val all = FsEditTool(workspace).execute("""{"path":"b.txt","find":"X","replace":"Y","all":true}""")
        assertTrue(all is ToolOutcome.Ok)
        assertEquals("Y\nY\n", File(root, "b.txt").readText())
    }

    @Test
    fun `shell_exec runs in the workspace and reports failures`() = runTest {
        val root = tmp.newFolder()
        File(root, "hello.txt").writeText("hi")
        val tool = ShellExecTool(root)

        val ok = tool.execute("""{"command":"echo marker && ls"}""")
        assertTrue(ok is ToolOutcome.Ok)
        assertTrue((ok as ToolOutcome.Ok).text.contains("marker"))
        assertTrue(ok.text.contains("hello.txt"))

        val failed = tool.execute("""{"command":"exit 3"}""")
        assertTrue(failed is ToolOutcome.Error)
        assertTrue((failed as ToolOutcome.Error).message.contains("3"))

        val timeout = tool.execute("""{"command":"sleep 5","timeout":1}""")
        assertTrue(timeout is ToolOutcome.Error)
        assertTrue((timeout as ToolOutcome.Error).message.contains("强制终止"))
    }

    @Test
    fun `shell_exec spills clipped output to a readable workspace log`() = runTest {
        val root = tmp.newFolder()
        val tool = ShellExecTool(root, maxOutputChars = 200)
        val loop = "i=1; while [ \$i -le 200 ]; do echo line-\$i; i=\$((i+1)); done"

        val out = tool.execute("""{"command":"$loop"}""")
        assertTrue(out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue(text.contains("fs_read"))
        // 中间被省略，但头尾仍然可见
        assertTrue(text.contains("line-1"))
        assertTrue(text.contains("line-200"))

        val rel = Regex("`(\\S+\\.log)`").find(text)?.groupValues?.get(1)
        assertTrue("日志路径缺失：$text", rel != null)
        val log = File(root, rel!!)
        assertTrue("日志文件不存在：$rel", log.isFile)
        val full = log.readText()
        assertTrue(full.contains("line-1\n"))
        assertTrue(full.contains("line-200\n"))

        // 模型能按结果里给的路径继续分页读
        val read = FsReadTool(root).execute("""{"path":"$rel","limit":64}""")
        assertTrue(read is ToolOutcome.Ok)
        assertTrue((read as ToolOutcome.Ok).text.contains("line-1"))
    }

    @Test
    fun `shell_exec keeps short output untouched`() = runTest {
        val root = tmp.newFolder()
        val tool = ShellExecTool(root, maxOutputChars = 200)
        val out = tool.execute("""{"command":"echo small"}""")
        assertTrue(out is ToolOutcome.Ok)
        assertEquals("small\n", (out as ToolOutcome.Ok).text)
        assertFalse(File(root, ".pocketagent/logs").exists())
    }

    @Test
    fun `read paginates with byte offset and limit`() = runTest {
        val root = tmp.newFolder()
        File(root, "abcdefghij.txt").writeText("0123456789")
        val tool = FsReadTool(root)

        val first = tool.execute("""{"path":"abcdefghij.txt","offset":2,"limit":3}""") as ToolOutcome.Ok
        assertTrue(first.text.startsWith("234"))
        assertTrue(first.text.contains("next_offset=5"))

        val last = tool.execute("""{"path":"abcdefghij.txt","offset":5,"limit":100}""") as ToolOutcome.Ok
        assertEquals("56789", last.text)
    }

    @Test
    fun `read rejects invalid paging and malformed json`() = runTest {
        val root = tmp.newFolder()
        File(root, "a.txt").writeText("abc")
        val tool = FsReadTool(root)

        val beyond = tool.execute("""{"path":"a.txt","offset":4}""") as ToolOutcome.Error
        assertTrue(beyond.message.contains("超出文件末尾"))
        assertTrue(beyond.message.contains("3 字节"))

        val badLimit = tool.execute("""{"path":"a.txt","limit":0}""") as ToolOutcome.Error
        assertTrue(badLimit.message.contains("limit 必须在"))

        val malformed = tool.execute("""{"path":""") as ToolOutcome.Error
        assertTrue(malformed.message.contains("不是合法 JSON"))
    }

    @Test
    fun `http tool fetches and rejects non http`() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("pong"))
            server.start()
            val tool = HttpGetTool(allowLocal = true)
            val ok = tool.execute("""{"url":"${server.url("/ping")}"}""") as ToolOutcome.Ok
            assertTrue(ok.text.contains("pong"))
            val bad = tool.execute("""{"url":"file:///etc/passwd"}""")
            assertTrue(bad is ToolOutcome.Error)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http body is bounded with an actionable marker`() = runTest {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("x".repeat(40 * 1024)))
            server.start()
            val tool = HttpGetTool(allowLocal = true)
            val result = tool.execute("""{"url":"${server.url("/big")}"}""") as ToolOutcome.Ok
            assertTrue(result.text.contains("HTTP 200"))
            assertTrue(result.text.contains("已截断"))
            assertTrue(result.text.contains("支持分页"))
            assertTrue(result.text.length < 34 * 1024)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `tool schemas are valid json objects`() {
        val schemas = listOf(
            ToolSchemas.FS_LIST,
            ToolSchemas.FS_READ,
            ToolSchemas.FS_READ_PAGED,
            ToolSchemas.FS_WRITE,
            ToolSchemas.FS_DELETE,
            ToolSchemas.APPLY_PATCH,
            ToolSchemas.HTTP_GET,
            ToolSchemas.SSH_EXEC,
            ToolSchemas.SSH_READ,
            ToolSchemas.SSH_WRITE,
            ToolSchemas.SSH_LS,
        )
        schemas.forEach { schema ->
            val root = Json.parseToJsonElement(schema).jsonObject
            assertEquals("object", root["type"].toString().trim('"'))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate tool names are rejected`() {
        val root = tmp.newFolder()
        ToolRegistry(listOf(FsReadTool(root), FsReadTool(root)))
    }

    @Test
    fun `missing arguments produce error not crash`() = runTest {
        val root = tmp.newFolder()
        assertTrue(FsReadTool(root).execute("{}") is ToolOutcome.Error)
        assertTrue(FsWriteTool(root).execute("""{"path":"x"}""") is ToolOutcome.Error)
    }

    @Test
    fun `skill_read loads workflow markdown and rejects bad names`() = runTest {
        val root = tmp.newFolder()
        val dir = File(root, "skills/deploy").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: deploy\ndescription: 部署到 h200\n---\n步骤一：先跑测试\n")
        val workspace = app.pocketagent.android.tools.LocalWorkspace(root)

        val ok = SkillReadTool(workspace).execute("""{"name":"deploy"}""")
        assertTrue(ok is ToolOutcome.Ok)
        assertTrue((ok as ToolOutcome.Ok).text.contains("步骤一：先跑测试"))

        val bad = SkillReadTool(workspace).execute("""{"name":"../etc"}""")
        assertTrue(bad is ToolOutcome.Error)
    }

    @Test
    fun `skill_read supports codex agents skills directory and exact path`() = runTest {
        val root = tmp.newFolder()
        val dir = File(root, ".agents/skills/review").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: review\ndescription: 代码审查流程\n---\n检查回归和测试\n")
        val workspace = app.pocketagent.android.tools.LocalWorkspace(root)

        val discovered = app.pocketagent.android.tools.SkillCatalog.discover(workspace)
        assertEquals("review", discovered.single().name)
        assertEquals(".agents/skills/review/SKILL.md", discovered.single().path)
        assertTrue(discovered.single().description.contains("代码审查"))

        val byName = SkillReadTool(workspace).execute("""{"name":"review"}""") as ToolOutcome.Ok
        assertTrue(byName.text.contains("检查回归和测试"))

        val byPath = SkillReadTool(workspace).execute(
            """{"path":".agents/skills/review/SKILL.md"}""",
        ) as ToolOutcome.Ok
        assertTrue(byPath.text.contains("检查回归和测试"))

        val traversal = SkillReadTool(workspace).execute(
            """{"path":".agents/skills/../../etc/SKILL.md"}""",
        )
        assertTrue(traversal is ToolOutcome.Error)
    }

    @Test
    fun `apply_patch updates adds and deletes files from codex patch syntax`() = runTest {
        val root = tmp.newFolder()
        File(root, "app.txt").writeText("before\nold\nend\n")
        File(root, "gone.txt").writeText("remove me\n")

        val patch = """
            *** Begin Patch
            *** Update File: app.txt
            @@
             before
            -old
            +new
             end
            *** Add File: added.txt
            +hello
            *** Delete File: gone.txt
            *** End Patch
        """.trimIndent()
        val result = applyPatch(root, patch)

        assertTrue(result is ToolOutcome.Ok)
        assertEquals("before\nnew\nend\n", File(root, "app.txt").readText())
        assertEquals("hello\n", File(root, "added.txt").readText())
        assertTrue(!File(root, "gone.txt").exists())
    }

    @Test
    fun `apply_patch leaves earlier files untouched when a later hunk is invalid`() = runTest {
        val root = tmp.newFolder()
        File(root, "good.txt").writeText("keep\n")
        File(root, "bad.txt").writeText("actual\n")

        val patch = """
            *** Begin Patch
            *** Update File: good.txt
            @@
            -keep
            +changed
            *** Update File: bad.txt
            @@
            -missing
            +never
            *** End Patch
        """.trimIndent()
        val result = applyPatch(root, patch)

        assertTrue(result is ToolOutcome.Error)
        assertTrue((result as ToolOutcome.Error).message.contains("找不到上下文"))
        assertEquals("keep\n", File(root, "good.txt").readText())
        assertEquals("actual\n", File(root, "bad.txt").readText())
    }

    @Test
    fun `apply_patch supports multiple hunks and section anchors`() = runTest {
        val root = tmp.newFolder()
        File(root, "Example.kt").writeText(
            """
            class Example {
                fun first() {
                    return 1
                }

                fun second() {
                    return 2
                }
            }
            """.trimIndent() + "\n",
        )

        val patch = """
            *** Begin Patch
            *** Update File: Example.kt
            @@ fun first() {
            -        return 1
            +        return 10
            @@ fun second() {
            -        return 2
            +        return 20
            *** End Patch
        """.trimIndent()
        val result = applyPatch(root, patch)

        assertTrue((result as ToolOutcome.Ok).text.contains("Example.kt"))
        val updated = File(root, "Example.kt").readText()
        assertTrue(updated.contains("return 10"))
        assertTrue(updated.contains("return 20"))
        assertFalse(updated.contains("return 1\n"))
        assertFalse(updated.contains("return 2\n"))
    }

    @Test
    fun `apply_patch rejects add existing and delete missing without changing files`() = runTest {
        val root = tmp.newFolder()
        File(root, "exists.txt").writeText("original\n")
        val addExisting = """
            *** Begin Patch
            *** Add File: exists.txt
            +overwritten
            *** End Patch
        """.trimIndent()
        assertTrue(applyPatch(root, addExisting) is ToolOutcome.Error)
        assertEquals("original\n", File(root, "exists.txt").readText())

        File(root, "keep.txt").writeText("keep\n")
        val deleteMissing = """
            *** Begin Patch
            *** Update File: keep.txt
            @@
            -keep
            +changed
            *** Delete File: missing.txt
            *** End Patch
        """.trimIndent()
        val result = applyPatch(root, deleteMissing)
        assertTrue(result is ToolOutcome.Error)
        assertEquals("keep\n", File(root, "keep.txt").readText())
    }

    @Test
    fun `apply_patch blocks traversal absolute paths and duplicate operations`() = runTest {
        val root = tmp.newFolder("workspace")
        File(root, "keep.txt").writeText("old\n")
        File(tmp.root, "outside.txt").writeText("outside\n")

        val traversal = """
            *** Begin Patch
            *** Update File: keep.txt
            @@
            -old
            +new
            *** Add File: ../outside.txt
            +hacked
            *** End Patch
        """.trimIndent()
        val traversalResult = applyPatch(root, traversal)
        assertTrue(traversalResult is ToolOutcome.Error)
        assertTrue((traversalResult as ToolOutcome.Error).message.contains("路径越界"))
        assertEquals("old\n", File(root, "keep.txt").readText())
        assertEquals("outside\n", File(tmp.root, "outside.txt").readText())

        val absolutePath = File(tmp.root, "absolute.txt").apply { writeText("absolute\n") }.absolutePath
        val absolute = """
            *** Begin Patch
            *** Update File: keep.txt
            @@
            -old
            +new
            *** Delete File: $absolutePath
            *** End Patch
        """.trimIndent()
        assertTrue(applyPatch(root, absolute) is ToolOutcome.Error)
        assertEquals("old\n", File(root, "keep.txt").readText())
        assertEquals("absolute\n", File(absolutePath).readText())

        val duplicate = """
            *** Begin Patch
            *** Update File: keep.txt
            @@
            -old
            +new
            *** Update File: ./keep.txt
            @@
            -new
            +newer
            *** End Patch
        """.trimIndent()
        val duplicateResult = applyPatch(root, duplicate)
        assertTrue(duplicateResult is ToolOutcome.Error)
        assertTrue((duplicateResult as ToolOutcome.Error).message.contains("出现了多次"))
        assertEquals("old\n", File(root, "keep.txt").readText())
    }

    @Test
    fun `apply_patch rejects malformed hunks and unsupported moves`() = runTest {
        val root = tmp.newFolder()
        File(root, "a.txt").writeText("old\n")
        val malformed = listOf(
            """
            *** Begin Patch
            *** Update File: a.txt
            @@
            -old
            +new
            """.trimIndent(),
            """
            *** Begin Patch
            *** Update File: a.txt
            not a hunk line
            *** End Patch
            """.trimIndent(),
            """
            *** Begin Patch
            *** Update File: a.txt
            *** End Patch
            """.trimIndent(),
            """
            *** Begin Patch
            *** Update File: a.txt
            @@
            +added without anchor
            *** End Patch
            """.trimIndent(),
            """
            *** Begin Patch
            *** Update File: a.txt
            *** Move to: b.txt
            *** End Patch
            """.trimIndent(),
        )
        malformed.forEach { patch ->
            assertTrue("应拒绝：\n$patch", applyPatch(root, patch) is ToolOutcome.Error)
            assertEquals("old\n", File(root, "a.txt").readText())
            assertFalse(File(root, "b.txt").exists())
        }
    }

    @Test
    fun `apply_patch supports end of file markers CRLF and blank add lines`() = runTest {
        val root = tmp.newFolder()
        File(root, "tail.txt").writeText("top\nmiddle\nlast\n")
        val endOfFile = """
            *** Begin Patch
            *** Update File: tail.txt
            @@
             middle
            -last
            +final
            *** End of File
            *** End Patch
        """.trimIndent()
        assertTrue(applyPatch(root, endOfFile) is ToolOutcome.Ok)
        assertEquals("top\nmiddle\nfinal\n", File(root, "tail.txt").readText())

        File(root, "crlf.txt").writeText("a\r\nb\r\n")
        val crlf = """
            *** Begin Patch
            *** Update File: crlf.txt
            @@
             a
            -b
            +c
            *** End Patch
        """.trimIndent()
        assertTrue(applyPatch(root, crlf) is ToolOutcome.Ok)
        assertEquals("a\r\nc\r\n", File(root, "crlf.txt").readText())

        val addWithBlanks = """
            *** Begin Patch
            *** Add File: new.txt
            +line1
            +
            +line3
            *** Add File: empty.txt
            *** End Patch
        """.trimIndent()
        assertTrue(applyPatch(root, addWithBlanks) is ToolOutcome.Ok)
        assertEquals("line1\n\nline3\n", File(root, "new.txt").readText())
        assertEquals("", File(root, "empty.txt").readText())

        File(root, "grow.txt").writeText("")
        val grow = """
            *** Begin Patch
            *** Update File: grow.txt
            +first line
            +second line
            *** End Patch
        """.trimIndent()
        assertTrue(applyPatch(root, grow) is ToolOutcome.Ok)
        assertEquals("first line\nsecond line\n", File(root, "grow.txt").readText())
    }

    @Test
    fun `apply_patch rolls back completed writes when a later write fails`() = runTest {
        val root = tmp.newFolder()
        File(root, "first.txt").writeText("first\n")
        File(root, "second.txt").writeText("second\n")
        val workspace = FailingWriteWorkspace(LocalWorkspace(root), "second.txt")
        val patch = """
            *** Begin Patch
            *** Update File: first.txt
            @@
            -first
            +changed-first
            *** Update File: second.txt
            @@
            -second
            +changed-second
            *** End Patch
        """.trimIndent()
        val result = ApplyPatchTool(workspace).execute(patchArguments(patch))

        assertTrue(result is ToolOutcome.Error)
        assertTrue((result as ToolOutcome.Error).message.contains("回滚"))
        assertEquals("first\n", File(root, "first.txt").readText())
        assertEquals("second\n", File(root, "second.txt").readText())

        val addRoot = tmp.newFolder()
        File(addRoot, "keep.txt").writeText("keep\n")
        val partialWorkspace = FailingAddWorkspace(LocalWorkspace(addRoot), "new.txt")
        val addPatch = """
            *** Begin Patch
            *** Update File: keep.txt
            @@
            -keep
            +changed
            *** Add File: new.txt
            +new
            *** End Patch
        """.trimIndent()
        val addResult = ApplyPatchTool(partialWorkspace).execute(patchArguments(addPatch))
        assertTrue(addResult is ToolOutcome.Error)
        assertEquals("keep\n", File(addRoot, "keep.txt").readText())
        assertFalse(File(addRoot, "new.txt").exists())
    }

    @Test
    fun `apply_patch is registered as write with per call approval and a valid schema`() {
        val root = tmp.newFolder()
        val tool = ApplyPatchTool(LocalWorkspace(root))
        val registry = ToolRegistry(listOf(tool))

        assertEquals("apply_patch", registry.find("apply_patch")?.spec?.name)
        val schema = Json.parseToJsonElement(ToolSchemas.APPLY_PATCH).jsonObject
        assertEquals("object", schema["type"].toString().trim('"'))
        assertTrue(schema["required"].toString().contains("patch"))
        assertEquals(ToolPermissions.WRITE, ToolPermissions.categoryOf("apply_patch"))
        assertTrue(ToolPermissions.describe(ToolPermissions.WRITE).contains("apply_patch"))
        assertTrue(
            ToolPermissions.requiresPerCallApproval(
                "apply_patch",
                patchArguments("*** Begin Patch\n*** End Patch"),
            ),
        )
        assertFalse(
            ToolPermissions.isSessionAllowable(
                "apply_patch",
                patchArguments("*** Begin Patch\n*** End Patch"),
            ),
        )
    }

    @Test
    fun `registry merges dynamic tools and keeps static ones first`() {
        fun spec(name: String) = ToolSpec(name, "说明", """{"type":"object"}""")
        fun tool(name: String, output: String) = object : Tool {
            override val spec = spec(name)
            override suspend fun execute(argumentsJson: String) = ToolOutcome.Ok(output)
        }

        val static = tool("fs_read", "static")
        val dynamic = listOf(tool("mcp__demo__search", "dynamic"), tool("fs_read", "shadowed"))
        val registry = ToolRegistry(listOf(static), dynamicTools = { dynamic })

        assertEquals(listOf("fs_read", "mcp__demo__search"), registry.specs().map { it.name })
        assertTrue(registry.find("mcp__demo__search") != null)
        // 静态工具优先，动态同名不会覆盖
        assertEquals(static, registry.find("fs_read"))
        // 动态列表现取：MCP 服务器变更后不用重建 registry
        val later = listOf(tool("mcp__demo__other", "x"))
        val registry2 = ToolRegistry(listOf(static), dynamicTools = { later })
        assertEquals(listOf("fs_read", "mcp__demo__other"), registry2.specs().map { it.name })
        assertTrue(registry2.find("mcp__demo__search") == null)
    }
}
