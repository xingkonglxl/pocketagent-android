package app.pocketagent.android

import app.pocketagent.android.agent.WorkspaceInstructions
import app.pocketagent.android.tools.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceInstructionsTest {
    private class FakeWorkspace(
        override val rootPath: String?,
        private val files: Map<String, String>,
    ) : Workspace {
        override val label: String = "fake"

        override suspend fun list(path: String): String = ""

        override suspend fun read(path: String, offsetBytes: Long, maxBytes: Int): String =
            files[path] ?: error("missing $path")

        override suspend fun write(path: String, content: String) = Unit

        override suspend fun delete(path: String) = Unit

        override suspend fun readInstruction(path: String): String? = files[path]
    }

    @Test
    fun `reads parent instructions and prefers override in the same directory`() = runBlocking {
        val workspace = FakeWorkspace(
            rootPath = "/repo/project",
            files = mapOf(
                "/repo/AGENTS.md" to "repo rule",
                "/repo/project/AGENTS.md" to "project rule",
                "/repo/project/AGENTS.override.md" to "override rule",
            ),
        )

        val text = WorkspaceInstructions.load(workspace).orEmpty()

        assertTrue(text.indexOf("repo rule") < text.indexOf("override rule"))
        assertTrue(text.contains("/repo/AGENTS.md"))
        assertTrue(text.contains("/repo/project/AGENTS.override.md"))
        assertFalse(text.contains("project rule"))
    }

    @Test
    fun `caps the merged instruction text`() = runBlocking {
        val workspace = FakeWorkspace(
            rootPath = "/repo",
            files = mapOf("/repo/AGENTS.md" to "x".repeat(30_000)),
        )

        assertEquals(WorkspaceInstructions.MAX_CHARS, WorkspaceInstructions.load(workspace)!!.length)
    }

    @Test
    fun `relative roots fall back to workspace root files`() = runBlocking {
        val workspace = FakeWorkspace(
            rootPath = null,
            files = mapOf(
                "AGENTS.md" to "relative rule",
                "AGENTS.override.md" to "relative override",
            ),
        )

        assertEquals("relative override", WorkspaceInstructions.load(workspace))
    }

    @Test
    fun `ancestor directories are root first and stop at slash`() {
        assertEquals(
            listOf(
                "/Users",
                "/Users/example",
                "/Users/example/Projects",
                "/Users/example/Projects/app",
            ),
            WorkspaceInstructions.ancestorDirs("/Users/example/Projects/app", 3),
        )
        assertEquals(listOf("/repo"), WorkspaceInstructions.ancestorDirs("/repo", 3))
    }

    @Test
    fun `tilde roots include home before nested workspace`() {
        assertEquals(
            listOf("~", "~/Projects", "~/Projects/app"),
            WorkspaceInstructions.tildeDirs("~/Projects/app", 3),
        )
        assertEquals(listOf("~"), WorkspaceInstructions.tildeDirs("~", 3))
    }
}
