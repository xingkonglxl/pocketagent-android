package app.pocketagent.android

import app.pocketagent.android.ui.chat.composeWithAttachments
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderingTest {

    @Test
    fun `attachment content larger than old 60k cutoff is kept whole`() {
        val body = "第一行\n" + "完整附件内容".repeat(20_000) + "\n最后一行"
        val composed = composeWithAttachments("看附件", listOf("rows.csv" to body))
        assertTrue(composed.contains(body))
        assertTrue(composed.endsWith("\n```"))
    }
}
