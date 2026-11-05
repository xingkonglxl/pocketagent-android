package app.pocketagent.android

import app.pocketagent.android.ui.components.parseMarkdown
import org.junit.Test

class MarkdownPerfProbeTest {
    @Test
    fun `probe parse cost for a long answer`() {
        val para = "宇树科技（688836）最近一个交易日收盘价为 647.05 元，较前收盘下跌 2.32%，" +
            "成交额 39.88 亿元。数据来自腾讯行情接口与东方财富日 K 线接口，时间为 2026-10-30 16:14:50。" +
            "这里再补充一段较长的说明，用来模拟真实回答中常见的连续中文文本，长度接近实际段落。\n\n"
        val table = "| 项目 | 数值 |\n|---|---|\n" +
            (1..12).joinToString("") { "| 指标$it | ${it * 37}.25 亿 |\n" } + "\n"
        val code = "```kotlin\nfun main() {\n    println(\"hello\")\n}\n```\n\n"
        val src = buildString {
            repeat(40) { append(para) }
            repeat(6) { append(table).append(code) }
        }
        // 预热
        repeat(5) { parseMarkdown(src) }
        val t0 = System.nanoTime()
        repeat(20) { parseMarkdown(src) }
        val per = (System.nanoTime() - t0) / 20 / 1_000_000.0
        println("PERF chars=${src.length} perParseMs=$per")
    }
}
