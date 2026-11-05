package app.pocketagent.android

import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.ViewImageTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 和桌面 Codex 的 view_image 对齐：图片要真的进模型上下文，而不是回一段文字。 */
class ViewImageToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 1x1 透明 PNG。 */
    private val png = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

    @Test
    fun `reads an image into a data url`() = runTest {
        val root = tmp.newFolder()
        File(root, "shots").mkdirs()
        File(root, "shots/home.png").writeBytes(png)

        val result = ViewImageTool(root).execute("""{"path":"shots/home.png"}""")
        assertTrue(result is ToolOutcome.Ok)
        val ok = result as ToolOutcome.Ok
        assertTrue(ok.text.contains("shots/home.png"))
        assertTrue(ok.images.single().startsWith("data:image/png;base64,"))
    }

    @Test
    fun `jpeg extension maps to jpeg mime`() = runTest {
        val root = tmp.newFolder()
        File(root, "chart.JPG").writeBytes(png)

        val ok = ViewImageTool(root).execute("""{"path":"chart.JPG"}""") as ToolOutcome.Ok
        assertTrue(ok.images.single().startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `rejects non image files and missing path`() = runTest {
        val root = tmp.newFolder()
        File(root, "notes.txt").writeText("hi")
        assertTrue(ViewImageTool(root).execute("""{"path":"notes.txt"}""") is ToolOutcome.Error)
        assertTrue(ViewImageTool(root).execute("""{}""") is ToolOutcome.Error)
    }

    @Test
    fun `rejects images over the size cap`() = runTest {
        val root = tmp.newFolder()
        File(root, "huge.png").writeBytes(ByteArray(ViewImageTool.MAX_IMAGE_BYTES) { 7 })
        assertTrue(ViewImageTool(root).execute("""{"path":"huge.png"}""") is ToolOutcome.Error)
    }
}
