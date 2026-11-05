package app.pocketagent.android

import app.pocketagent.android.ui.components.MdBlock
import app.pocketagent.android.ui.components.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableTest {

    @Test
    fun `ordered list right after a colon paragraph is kept`() {
        // 历史截断消息正好停在「补充两点实际使用上的细节：」，后面是编号列表。
        // 这里确认解析层不会把这种结构吃掉。
        val src = "补充两点实际使用上的细节：\n\n" +
            "1. 也可以直接用自然语言让我停，比如「暂停这个 goal」。\n" +
            "2. 我查了当前这个会话，没有活动的 goal。"
        val blocks = parseMarkdown(src)
        val bullets = blocks.filterIsInstance<MdBlock.Bullets>()
        assertEquals(1, bullets.size)
        assertTrue(bullets[0].ordered)
        assertEquals(2, bullets[0].items.size)
        assertTrue(bullets[0].items[1].contains("没有活动的 goal"))
    }

    @Test
    fun `parses a standard markdown table`() {
        val src = """
            | 项目 | 删除前 | 现在 |
            |---|---|---|
            | home | 3.2 TB | 1.8 TB |
            | 合计 | 3.35 TB | 1.94 TB |
        """.trimIndent()
        val blocks = parseMarkdown(src)
        assertEquals(1, blocks.size)
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("项目", "删除前", "现在"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("home", "3.2 TB", "1.8 TB"), table.rows[0])
    }

    @Test
    fun `parses table with alignment markers and without leading pipe`() {
        val src = """
            指标 | 数值
            :--|--:
            a | 1
        """.trimIndent()
        val table = parseMarkdown(src).single() as MdBlock.Table
        assertEquals(listOf("指标", "数值"), table.header)
        assertEquals(listOf("a", "1"), table.rows.single())
    }

    @Test
    fun `text after the table becomes a paragraph`() {
        val src = "| a | b |\n|---|---|\n| 1 | 2 |\n\n结论：行了。"
        val blocks = parseMarkdown(src)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MdBlock.Table)
        assertEquals("结论：行了。", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `a line with pipe but no separator is not a table`() {
        val blocks = parseMarkdown("a | b 只是文本")
        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `escaped pipes and inline code pipes stay inside their cells`() {
        val src = """
            | 命令 | 说明 |
            |---|---|
            | `a|b` | 管道符要转义：a \| b |
            | c \\| d | 双反斜杠后仍然是分隔符 |
        """.trimIndent()
        val table = parseMarkdown(src).single() as MdBlock.Table
        assertEquals(listOf("`a|b`", "管道符要转义：a | b"), table.rows[0])
        assertEquals(listOf("c \\", "d", "双反斜杠后仍然是分隔符"), table.rows[1])
    }

    @Test
    fun `long receipt keeps every code line table row and trailing paragraph`() {
        val codeLines = (1..6000).joinToString("\n") { "line-${it.toString().padStart(4, '0')} ${"x".repeat(24)}" }
        val tableRows = (1..500).joinToString("\n") { "| row-$it | ${"v".repeat(200)} |" }
        val src = buildString {
            append("开头\n\n```kotlin\n")
            append(codeLines)
            append("\n\n```\n\n| key | value |\n|---|---|\n")
            append(tableRows)
            append("\n\n最后一段")
        }.replace("\n", "\r\n")

        val blocks = parseMarkdown(src)
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals(6001, code.code.lines().size)
        assertTrue(code.code.contains("line-6000 "))
        assertTrue(code.code.endsWith("\n"))
        assertFalse(code.code.contains('\r'))

        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(500, table.rows.size)
        assertEquals("row-500", table.rows.last()[0])
        assertEquals("最后一段", (blocks.last() as MdBlock.Paragraph).text)
    }
}
