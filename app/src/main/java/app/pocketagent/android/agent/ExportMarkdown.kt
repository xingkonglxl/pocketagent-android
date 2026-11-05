package app.pocketagent.android.agent

import app.pocketagent.android.data.ChatMessage

/**
 * 把会话导出成 markdown。之前只写正文，工具参数/结果/思考过程全丢，
 * 导出的文件回看不出来为什么得出那个结论，和桌面 Codex 的 transcript 差很远。
 * 纯函数，方便单测。
 */
object ExportMarkdown {

    fun build(
        title: String,
        messages: List<ChatMessage>,
        exportedAt: String,
    ): String = buildString {
        append("# ").append(title.ifBlank { "PocketAgent 对话" }).append("\n\n")
        append("- 导出时间：").append(exportedAt).append('\n')
        append("- 消息数：").append(messages.size).append("\n\n")

        messages.forEach { m ->
            append("## ").append(m.role.name.lowercase()).append("\n\n")
            if (m.model != null) append("- 模型：").append(m.model).append('\n')
            if (m.tokensIn + m.tokensOut > 0) {
                append("- tokens：in ").append(m.tokensIn).append(" / out ").append(m.tokensOut).append('\n')
            }
            if (m.stopped) append("- 已停止\n")
            if (m.images.isNotEmpty()) append("- 图片：").append(m.images.size).append(" 张（未导出二进制）\n")
            if (m.model != null || m.tokensIn + m.tokensOut > 0 || m.stopped || m.images.isNotEmpty()) append('\n')

            if (m.reasoning.isNotBlank()) {
                append("<details><summary>思考过程</summary>\n\n")
                append(fence(m.reasoning.trim())).append("\n\n</details>\n\n")
            }
            if (m.text.isNotBlank()) append(m.text.trim()).append("\n\n")

            m.toolCalls.forEach { c ->
                append("### 工具 ").append(c.name).append("（").append(c.status).append("）\n\n")
                if (c.durationMs > 0) append("耗时 ").append(c.durationMs).append(" ms\n\n")
                if (c.arguments.isNotBlank()) {
                    append("参数：\n\n").append(fence(c.arguments.trim())).append("\n\n")
                }
                val result = c.result
                if (!result.isNullOrBlank()) {
                    append("结果：\n\n").append(fence(result)).append("\n\n")
                }
            }
        }
    }

    /** 用比内容里最长的连续反引号更长的围栏，避免把内容截断或提前闭合。 */
    private fun fence(content: String): String {
        val longest = Regex("`+").findAll(content).maxOfOrNull { it.value.length } ?: 0
        val ticks = "`".repeat(maxOf(3, longest + 1))
        return "$ticks\n$content\n$ticks"
    }
}
