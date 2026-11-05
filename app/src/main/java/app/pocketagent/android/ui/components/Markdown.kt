package app.pocketagent.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketagent.android.ui.theme.MonoStyle
import app.pocketagent.android.ui.i18n.L10n

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val ordered: Boolean, val items: List<String>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    /** markdown 表格：`| a | b |` + `|---|---|` + 若干行 */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
}

private fun endsWithUnescapedPipe(value: String): Boolean {
    if (!value.endsWith('|')) return false
    var slashes = 0
    var i = value.length - 2
    while (i >= 0 && value[i] == '\\') {
        slashes++
        i--
    }
    return slashes % 2 == 0
}

private fun tableCells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (endsWithUnescapedPipe(s)) s = s.dropLast(1)

    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var inCode = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && (s[i + 1] == '|' || s[i + 1] == '\\') -> {
                current.append(s[i + 1])
                i += 2
            }
            c == '`' -> {
                inCode = !inCode
                current.append(c)
                i++
            }
            c == '|' && !inCode -> {
                cells += current.toString().trim()
                current.clear()
                i++
            }
            else -> {
                current.append(c)
                i++
            }
        }
    }
    cells += current.toString().trim()
    return cells
}

/** `|---|:--:|---|` 这种分隔行 */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.contains('-') || !t.contains('|')) return false
    return tableCells(t).all { cell ->
        cell.isNotEmpty() && cell.all { it == '-' || it == ':' }
    }
}

fun parseMarkdown(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    // Mac/Windows 同步来的正文常用 CRLF；不能把 \r 原样带进代码块和表格单元格。
    val lines = src.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) blocks += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flushPara()
                val lang = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append("\n")
                    i++
                }
                // 只去掉解析器自己补在最后一行后的换行，保留代码块里原有的结尾空行。
                val rawCode = code.toString()
                blocks += MdBlock.Code(lang, rawCode.removeSuffix("\n"))
            }
            line.startsWith("### ") -> { flushPara(); blocks += MdBlock.Heading(3, line.removePrefix("### ")) }
            line.startsWith("## ") -> { flushPara(); blocks += MdBlock.Heading(2, line.removePrefix("## ")) }
            line.startsWith("# ") -> { flushPara(); blocks += MdBlock.Heading(1, line.removePrefix("# ")) }
            line.trimStart().startsWith("> ") -> { flushPara(); blocks += MdBlock.Quote(line.trimStart().removePrefix("> ")) }
            line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1]) -> {
                flushPara()
                val header = tableCells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += tableCells(lines[i])
                    i++
                }
                blocks += MdBlock.Table(header, rows)
                continue
            }
            Regex("^\\s*[-*+]\\s+.*").matches(line) -> {
                flushPara()
                val items = mutableListOf<String>()
                while (i < lines.size && Regex("^\\s*[-*+]\\s+.*").matches(lines[i])) {
                    items += lines[i].replace(Regex("^\\s*[-*+]\\s+"), "")
                    i++
                }
                blocks += MdBlock.Bullets(false, items)
                continue
            }
            Regex("^\\s*\\d+[.)]\\s+.*").matches(line) -> {
                flushPara()
                val items = mutableListOf<String>()
                while (i < lines.size && Regex("^\\s*\\d+[.)]\\s+.*").matches(lines[i])) {
                    items += lines[i].replace(Regex("^\\s*\\d+[.)]\\s+"), "")
                    i++
                }
                blocks += MdBlock.Bullets(true, items)
                continue
            }
            line.isBlank() -> flushPara()
            else -> para.append(line).append("\n")
        }
        i++
    }
    flushPara()
    return blocks
}

internal fun inlineAnnotated(
    text: String,
    codeBg: Color,
    linkColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        var idx = 0
        val pattern = Regex(
            "(`[^`]+`|\\*\\*[^*]+\\*\\*|\\[[^\\]\\n]+\\]\\(https?://[^)\\s]+\\)|https?://[^\\s<>()]+)",
        )
        pattern.findAll(text).forEach { m ->
            if (m.range.first > idx) append(text.substring(idx, m.range.first))
            val token = m.value
            when {
                token.startsWith("`") -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, background = codeBg)) {
                        append(token.trim('`'))
                    }
                }
                token.startsWith("**") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(token.removePrefix("**").removeSuffix("**"))
                    }
                }
                token.startsWith("[") -> {
                    val split = token.indexOf("](")
                    val label = token.substring(1, split)
                    val url = token.substring(split + 2, token.length - 1)
                    appendLink(label, url, linkColor)
                }
                else -> {
                    val trailing = token.takeLastWhile { it in ".,;:!?，。；：！？" }
                    val url = token.dropLast(trailing.length)
                    appendLink(url, url, linkColor)
                    append(trailing)
                }
            }
            idx = m.range.last + 1
        }
        if (idx < text.length) append(text.substring(idx))
    }

private fun AnnotatedString.Builder.appendLink(label: String, url: String, linkColor: Color) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        append(label)
        return
    }
    withLink(
        LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ),
        ),
    ) {
        append(label)
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    // 同一文本的其它状态重组复用解析结果；流式文本变化时仍会重新解析完整 Markdown。
    val blocks = remember(text) { parseMarkdown(text) }
    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> Text(
                        text = inlineAnnotated(block.text, codeBg, linkColor),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    is MdBlock.Paragraph -> Text(
                        text = inlineAnnotated(block.text, codeBg, linkColor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        block.items.forEachIndexed { idx, item ->
                            Row(verticalAlignment = Alignment.Top) {
                                if (block.ordered) {
                                    Text(
                                        text = "${idx + 1}.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.width(22.dp).padding(end = 6.dp),
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .padding(start = 5.dp, top = 10.dp, end = 11.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.onSurfaceVariant),
                                    )
                                }
                                Text(
                                    text = inlineAnnotated(item, codeBg, linkColor),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    is MdBlock.Quote -> Text(
                        text = inlineAnnotated(block.text, codeBg, linkColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                    is MdBlock.Code -> CodeBlock(block.lang, block.code) { clipboard.setText(AnnotatedString(block.code)) }
                    is MdBlock.Table -> MarkdownTable(block, codeBg, linkColor)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lang.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy, modifier = Modifier.padding(0.dp)) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = L10n.t("复制代码"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
            Text(text = code, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MarkdownTable(block: MdBlock.Table, codeBg: Color, linkColor: Color) {
    val cols = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    val widths = (0 until cols).map { c ->
        val maxLen = (listOf(block.header.getOrElse(c) { "" }) + block.rows.map { it.getOrElse(c) { "" } })
            .maxOfOrNull { it.length } ?: 4
        (maxLen.coerceIn(4, 16) * 14 + 24).dp
    }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .horizontalScroll(scroll)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        ) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                repeat(cols) { c ->
                    Text(
                        text = inlineAnnotated(block.header.getOrElse(c) { "" }, codeBg, linkColor),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            block.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row {
                    repeat(cols) { c ->
                        Text(
                            text = inlineAnnotated(row.getOrElse(c) { "" }, codeBg, linkColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(widths[c]).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
