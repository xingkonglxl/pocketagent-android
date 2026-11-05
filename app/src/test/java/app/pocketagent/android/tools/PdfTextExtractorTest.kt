package app.pocketagent.android.tools

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfTextExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUpPdfBox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun `generated mixed Chinese and English PDF extracts its text layer`() {
        val pdf = createTextPdf(
            "PocketAgent PDF extraction",
            "中英文混排：你好，世界！",
        )

        val result = PdfTextExtractor.inspect(pdf)

        assertEquals(1, result.pageCount)
        assertEquals(1, result.pagesWithText)
        assertFalse(result.needsImageFallback)
        assertTrue(result.text.contains("PocketAgent PDF extraction"))
        assertTrue(result.text.contains("中英文混排：你好，世界！"))
        assertEquals(result.text, PdfTextExtractor.extract(pdf))
    }

    @Test
    fun `real three page PDF is readable without network`() {
        val result = PdfTextExtractor.inspect(resource("/pdfs/basicapi-3pages.pdf"))

        assertEquals(3, result.pageCount)
        assertEquals(3, result.pagesScanned)
        assertTrue(result.pagesWithText >= 2)
        assertFalse(result.needsImageFallback)
        assertTrue(result.text.contains("Table Of Content"))
        assertTrue(result.text.contains("Paragraph 1.1"))
    }

    @Test
    fun `page range reads only the requested page`() {
        val result = PdfTextExtractor.inspect(
            resource("/pdfs/basicapi-3pages.pdf"),
            startPage = 2,
            maxPages = 1,
        )

        assertEquals(2, result.pageFrom)
        assertEquals(2, result.pageTo)
        assertTrue(result.pageLimited)
        assertTrue(result.text.contains("page 2 / 3"))
        assertFalse(result.text.contains("Table Of Content"))
        assertFalse(result.text.contains("page 1 / 3"))
    }

    @Test
    fun `blank PDF has no text and requests image fallback`() {
        val pdf = createBlankPdf(2)

        val result = PdfTextExtractor.inspect(pdf)

        assertEquals(2, result.pageCount)
        assertEquals(0, result.pagesWithText)
        assertEquals("", result.text)
        assertTrue(result.needsImageFallback)
    }

    @Test
    fun `text is bounded by UTF-8 bytes and reports truncation`() {
        val longLine = "Readable PDF text. ".repeat(120)
        val pdf = createTextPdf(longLine, longLine)

        val result = PdfTextExtractor.inspect(pdf, maxBytes = 1024)

        assertTrue(result.textTruncated)
        assertTrue(result.text.toByteArray(Charsets.UTF_8).size <= 1024)
        assertTrue(result.text.contains("已截断"))
    }

    @Test
    fun `password protected PDF returns an actionable error instead of blank text`() {
        val pdf = createTextPdf(
            "secret",
            ownerPassword = "owner-password",
            userPassword = "user-password",
        )

        val error = runCatching { PdfTextExtractor.inspect(pdf) }.exceptionOrNull()

        assertTrue(
            "expected PdfReadException, got ${error?.javaClass?.name}: ${error?.message}",
            error is PdfReadException,
        )
        assertTrue(error?.message.orEmpty().contains("已加密"))
    }

    @Test
    fun `owner protected PDF with empty user password still extracts`() {
        val pdf = createTextPdf(
            "PocketAgent encrypted but readable",
            ownerPassword = "owner-password",
            userPassword = "",
        )

        val result = PdfTextExtractor.inspect(pdf)

        assertFalse(result.needsImageFallback)
        assertTrue(result.text.contains("encrypted but readable"))
    }

    @Test
    fun `pdf tool reads a text PDF and identifies a scan fallback`() = runTest {
        val root = tmp.newFolder()
        File(root, "text.pdf").writeBytes(resource("/pdfs/basicapi-3pages.pdf"))
        File(root, "scan.pdf").writeBytes(createBlankPdf(2))
        val tool = PdfReadTool(root)

        val text = tool.execute("""{"path":"text.pdf"}""")
        val scan = tool.execute("""{"path":"scan.pdf"}""")

        assertTrue(text is ToolOutcome.Ok)
        assertTrue((text as ToolOutcome.Ok).text.contains("Paragraph 1.1"))
        assertTrue(scan is ToolOutcome.Error)
        assertTrue((scan as ToolOutcome.Error).message.contains("渲染成图片"))
    }

    @Test
    fun `pdf tool supports page ranges and actionable out of range errors`() = runTest {
        val root = tmp.newFolder()
        File(root, "text.pdf").writeBytes(resource("/pdfs/basicapi-3pages.pdf"))
        val tool = PdfReadTool(root)

        val page2 = tool.execute(
            """{"path":"text.pdf","start_page":2,"end_page":2,"max_kb":8}""",
        )
        val tooFar = tool.execute("""{"path":"text.pdf","start_page":99}""")

        assertTrue(page2 is ToolOutcome.Ok)
        val text = (page2 as ToolOutcome.Ok).text
        assertTrue(text.contains("page 2 / 3"))
        assertFalse(text.contains("page 1 / 3"))
        assertTrue(tooFar is ToolOutcome.Error)
        assertTrue((tooFar as ToolOutcome.Error).message.contains("起始页 99 超出范围"))
    }

    private fun createTextPdf(
        vararg lines: String,
        ownerPassword: String? = null,
        userPassword: String = "",
    ): ByteArray {
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val fontStream = resourceStream("/pdf-assets/NotoSansSC-TestSubset.ttf")
            val font = fontStream.use { PDType0Font.load(document, it, false) }
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(font, 14f)
                content.newLineAtOffset(48f, 790f)
                lines.forEachIndexed { index, line ->
                    if (index > 0) content.newLineAtOffset(0f, -22f)
                    content.showText(line)
                }
                content.endText()
            }
            if (ownerPassword != null) {
                val policy = StandardProtectionPolicy(
                    ownerPassword,
                    userPassword,
                    AccessPermission(),
                ).apply {
                    encryptionKeyLength = 128
                    setPreferAES(true)
                }
                document.protect(policy)
            }
            return save(document)
        }
    }

    private fun createBlankPdf(pageCount: Int): ByteArray {
        PDDocument().use { document ->
            repeat(pageCount) { document.addPage(PDPage(PDRectangle.A4)) }
            return save(document)
        }
    }

    private fun save(document: PDDocument): ByteArray =
        ByteArrayOutputStream().use { output ->
            document.save(output)
            output.toByteArray()
        }

    private fun resource(path: String): ByteArray = resourceStream(path).use { it.readBytes() }

    private fun resourceStream(path: String) =
        requireNotNull(javaClass.getResourceAsStream(path)) { "缺少测试资源：$path" }
}
