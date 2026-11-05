package app.pocketagent.android

import app.pocketagent.android.agent.CompactPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPlannerTest {

    @Test
    fun `short transcript stays one chunk`() {
        val chunks = CompactPlanner.split("user: 你好\nassistant: 你好")
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("你好"))
    }

    @Test
    fun `long transcript is split into bounded chunks`() {
        val line = "user: " + "字".repeat(200)
        val text = (1..200).joinToString("\n") { line }
        val chunks = CompactPlanner.split(text, chunkChars = 2_000, maxChunks = 8)
        assertTrue(chunks.size <= 9)
        chunks.filterNot { it.startsWith("〔中间省略") }.forEach { assertTrue(it.length <= 2_100) }
    }

    @Test
    fun `keeps the opening goal and the most recent chunks`() {
        val text = (1..50).joinToString("\n") { "第${it}行" + "x".repeat(500) }
        val chunks = CompactPlanner.split(text, chunkChars = 1_000, maxChunks = 3)
        assertEquals(4, chunks.size)
        assertTrue("开头目标应保留", chunks.first().contains("第1行"))
        assertTrue("最后一段应包含最新内容", chunks.last().contains("第50行"))
        assertTrue("中间省略段应有标记", chunks.any { it.startsWith("〔中间省略") })
    }

    @Test
    fun `single huge line is hard split`() {
        val chunks = CompactPlanner.split("x".repeat(5_000), chunkChars = 1_000, maxChunks = 10)
        assertEquals(5, chunks.size)
    }
}
