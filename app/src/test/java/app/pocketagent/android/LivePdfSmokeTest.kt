package app.pocketagent.android

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pocketagent.android.agent.AgentEvent
import app.pocketagent.android.agent.AgentInstructions
import app.pocketagent.android.agent.AgentRunner
import app.pocketagent.android.agent.AutoApproveGate
import app.pocketagent.android.agent.MessageMapper
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.Role
import app.pocketagent.android.tools.LocalWorkspace
import app.pocketagent.android.tools.PdfReadTool
import app.pocketagent.android.tools.ToolRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PDF 的端到端冒烟：让模型自己决定调用 pdf_read，读一份带标记的 PDF，再把标记答出来。
 * 没有 PA_LIVE_TOKEN 时直接返回，不跑网络。手动执行：
 *   PA_LIVE_TOKEN=<key> gradle :app:testDebugUnitTest --tests "*LivePdfSmokeTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LivePdfSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun initPdfBox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun `model reads a pdf through the tool`() = runBlocking {
        val token = System.getenv("PA_LIVE_TOKEN")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val marker = "POCKETAGENT-PDF-MARKER-42"
        val root = tmp.newFolder()
        createPdf(File(root, "report.pdf"), "Quarterly report\nMarker: $marker\nRevenue up 12%")

        val workspace = LocalWorkspace(root)
        val registry = ToolRegistry(listOf(PdfReadTool(workspace)))
        val baseProvider = Provider(
            name = "live",
            kind = ProviderKind.RESPONSES,
            baseUrl = "https://your-relay.example.com/v1",
            model = "deepseek-v4-flash",
            reasoningEffort = "max",
            maxSteps = 4,
            toolsEnabled = true,
        )
        val provider = baseProvider.copy(
            systemPrompt = AgentInstructions.build(
                provider = baseProvider,
                workspaceLabel = workspace.label,
                agentsMd = null,
                tools = registry.specs(),
                now = "2026-11-01 20:30 +08:00",
            ),
        )

        val history = MessageMapper.toApiMessages(
            listOf(
                ChatMessage(
                    role = Role.USER,
                    text = "工作区里有 report.pdf，读一下并原样告诉我里面的 Marker 字符串，不要猜。",
                ),
            ),
        )
        val result = AgentRunner(registry, AutoApproveGate).run(provider, token, history) { event ->
            if (event is AgentEvent.ToolFinished) {
                println("[tool] ${event.name} ok=${event.ok} ${event.output.take(160)}")
            }
        }

        println("[answer]\n${result.text}")
        assertTrue("模型没有答出 PDF 里的标记：${result.text}", result.text.contains(marker))
    }

    private fun createPdf(target: File, text: String) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 750f)
                text.lines().forEach { line ->
                    cs.showText(line)
                    cs.newLineAtOffset(0f, -18f)
                }
                cs.endText()
            }
            doc.save(target)
        }
    }
}
