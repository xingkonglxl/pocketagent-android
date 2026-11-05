package app.pocketagent.android.tools

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.Writer

/**
 * PDF 文本层提取结果。
 *
 * [text] 为空且 [pageCount] 大于 0 时，[needsImageFallback] 为 true，调用方应把页面渲染成图片再做 OCR/多模态读取。
 * [pageLimited] 表示只检查了前若干页；[textTruncated] 表示文本因大小上限被截断。
 */
data class PdfExtraction(
    val text: String,
    val pageCount: Int,
    val pageFrom: Int,
    val pageTo: Int,
    val pagesScanned: Int,
    val pagesWithText: Int,
    val textTruncated: Boolean,
    val pageLimited: Boolean,
) {
    val needsImageFallback: Boolean get() = pageCount > 0 && pagesWithText == 0
    val hasPagesWithoutText: Boolean get() = pagesScanned > pagesWithText
    val complete: Boolean get() = !textTruncated && !pageLimited && !hasPagesWithoutText
}

class PdfReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 只提取 PDF 的文本层；扫描件没有文本层时返回空串，由调用方转图片。
 *
 * PDFBox 必须先由 Application 调用 PDFBoxResourceLoader.init()。Android 上遇到大文件时还会创建临时 scratch
 * 文件，因此这里同时限制输入大小、检查页数和输出大小，避免一份异常 PDF 把进程内存/附件处理拖死。
 */
object PdfTextExtractor {
    const val DEFAULT_MAX_CHARS = 60_000
    const val DEFAULT_MAX_BYTES = 60 * 1024
    const val DEFAULT_MAX_PAGES = 100
    const val MAX_INPUT_BYTES = 40 * 1024 * 1024L

    private const val SCRATCH_MEMORY_BYTES = 8L * 1024 * 1024

    /** 兼容原有调用；[maxChars] 仍按 UTF-16 字符数限制，并额外受 UTF-8 字节数限制。 */
    fun extract(bytes: ByteArray, maxChars: Int = DEFAULT_MAX_CHARS): String =
        inspect(bytes, maxChars = maxChars).text

    fun extract(file: File, maxChars: Int = DEFAULT_MAX_CHARS): String =
        inspect(file, maxChars = maxChars).text

    fun inspect(
        bytes: ByteArray,
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxPages: Int = DEFAULT_MAX_PAGES,
        startPage: Int = 1,
    ): PdfExtraction {
        validateLimits(maxChars, maxBytes, maxPages, startPage)
        checkInputSize(bytes.size.toLong())
        return parseSafely {
            ByteArrayInputStream(bytes).use { input ->
                PDDocument.load(input, "", scratchFileSettings()).use { document ->
                    inspectDocument(document, maxChars, maxBytes, maxPages, startPage)
                }
            }
        }
    }

    fun inspect(
        file: File,
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        maxPages: Int = DEFAULT_MAX_PAGES,
        startPage: Int = 1,
    ): PdfExtraction {
        validateLimits(maxChars, maxBytes, maxPages, startPage)
        if (!file.exists()) throw PdfReadException("PDF 文件不存在")
        if (!file.isFile) throw PdfReadException("PDF 路径不是普通文件")
        checkInputSize(file.length())
        return parseSafely {
            PDDocument.load(file, "", scratchFileSettings()).use { document ->
                inspectDocument(document, maxChars, maxBytes, maxPages, startPage)
            }
        }
    }

    private fun inspectDocument(
        document: PDDocument,
        maxChars: Int,
        maxBytes: Int,
        maxPages: Int,
        startPage: Int,
    ): PdfExtraction {
        val pageCount = document.numberOfPages
        if (pageCount <= 0) throw PdfReadException("PDF 没有可读取的页面")
        if (startPage > pageCount) {
            throw PdfReadException("起始页 $startPage 超出范围；这份 PDF 共 $pageCount 页")
        }

        val firstPage = startPage.coerceAtLeast(1)
        val lastPage = minOf(pageCount, firstPage + maxPages - 1)
        val sink = LimitedTextWriter(maxChars, maxBytes)
        val stripper = BoundedTextStripper(sink).apply {
            this.startPage = firstPage
            endPage = lastPage
        }

        try {
            stripper.writeText(document, sink)
        } catch (_: TextLimitReached) {
            // 已经拿到足够文本，停止解析剩余页面。
        } finally {
            sink.finishPage()
        }

        val pageLimited = firstPage > 1 || pageCount > lastPage
        val text = composeText(
            text = sink.text.toString().trim(),
            pageCount = pageCount,
            pageFrom = firstPage,
            pageTo = lastPage,
            textTruncated = sink.truncated,
            pageLimited = pageLimited,
            maxChars = maxChars,
            maxBytes = maxBytes,
        )

        return PdfExtraction(
            text = text,
            pageCount = pageCount,
            pageFrom = firstPage,
            pageTo = lastPage,
            pagesScanned = minOf(lastPage, stripper.pagesStarted),
            pagesWithText = sink.pagesWithText,
            textTruncated = sink.truncated,
            pageLimited = pageLimited,
        )
    }

    private fun composeText(
        text: String,
        pageCount: Int,
        pageFrom: Int,
        pageTo: Int,
        textTruncated: Boolean,
        pageLimited: Boolean,
        maxChars: Int,
        maxBytes: Int,
    ): String {
        if (text.isBlank()) return ""
        val suffix = buildString {
            if (textTruncated) {
                append("\n…（PDF 文本超过 ${maxBytes / 1024}KB，已截断）")
            }
            if (pageFrom > 1 || pageTo < pageCount) {
                append("\n…（PDF 共 $pageCount 页，本次读取第 $pageFrom–$pageTo 页；")
                append("可继续用 start_page=$pageTo+1 读取后续页）")
            }
        }
        if (suffix.isEmpty()) return fitLimits(text, maxChars, maxBytes)

        val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
        val prefix = fitLimits(
            text = text,
            maxChars = (maxChars - suffix.length).coerceAtLeast(0),
            maxBytes = (maxBytes - suffixBytes).coerceAtLeast(0),
        ).trimEnd()
        return fitLimits(prefix + suffix, maxChars, maxBytes)
    }

    private fun checkInputSize(size: Long) {
        if (size <= 0L) throw PdfReadException("PDF 文件为空")
        if (size > MAX_INPUT_BYTES) {
            throw PdfReadException(
                "PDF 文件过大（${formatSize(size)}），当前上限是 ${formatSize(MAX_INPUT_BYTES)}",
            )
        }
    }

    private fun validateLimits(maxChars: Int, maxBytes: Int, maxPages: Int, startPage: Int) {
        require(maxChars > 0) { "maxChars 必须大于 0" }
        require(maxBytes > 0) { "maxBytes 必须大于 0" }
        require(maxPages > 0) { "maxPages 必须大于 0" }
        require(startPage > 0) { "startPage 必须大于 0" }
    }

    private fun scratchFileSettings(): MemoryUsageSetting =
        MemoryUsageSetting.setupMixed(SCRATCH_MEMORY_BYTES)

    private inline fun <T> parseSafely(block: () -> T): T {
        if (!PDFBoxResourceLoader.isReady()) {
            throw PdfReadException("PDF 引擎尚未初始化，请重新启动 App")
        }
        return try {
            block()
        } catch (e: PdfReadException) {
            throw e
        } catch (e: InvalidPasswordException) {
            throw PdfReadException("PDF 已加密，需要密码；当前无法直接提取文本", e)
        } catch (e: IOException) {
            throw PdfReadException("PDF 解析失败：${e.message ?: "文件损坏或格式不受支持"}", e)
        } catch (e: RuntimeException) {
            throw PdfReadException("PDF 解析失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) {
            "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
        } else {
            "${bytes / 1024} KB"
        }

    private class BoundedTextStripper(
        private val sink: LimitedTextWriter,
    ) : PDFTextStripper() {
        var pagesStarted: Int = 0
            private set

        override fun startPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
            pagesStarted++
            sink.startPage()
            super.startPage(page)
        }

        override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
            super.endPage(page)
            sink.finishPage()
        }
    }

    /**
     * PDFTextStripper 是流式写出的；限制 Writer 比先 getText() 再 take() 更早阻止超大文本进入内存。
     */
    private class LimitedTextWriter(
        private val maxChars: Int,
        private val maxBytes: Int,
    ) : Writer() {
        val text = StringBuilder()
        var truncated: Boolean = false
            private set
        var pagesWithText: Int = 0
            private set

        private var usedBytes: Int = 0
        private var pageOpen: Boolean = false
        private var pageHasText: Boolean = false

        fun startPage() {
            finishPage()
            pageOpen = true
            pageHasText = false
        }

        fun finishPage() {
            if (!pageOpen) return
            if (pageHasText) pagesWithText++
            pageOpen = false
            pageHasText = false
        }

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            var index = off
            val end = off + len
            while (index < end) {
                val codePoint = Character.codePointAt(cbuf, index, end)
                val chars = Character.charCount(codePoint)
                val bytes = utf8Bytes(codePoint)
                val nonWhitespace = !Character.isWhitespace(codePoint)

                if (text.length + chars <= maxChars && usedBytes + bytes <= maxBytes) {
                    text.append(cbuf, index, chars)
                    usedBytes += bytes
                    if (nonWhitespace) pageHasText = true
                    index += chars
                } else {
                    if (nonWhitespace) pageHasText = true
                    truncated = text.isNotEmpty()
                    throw TextLimitReached()
                }
            }
        }

        override fun write(c: Int) {
            write(charArrayOf(c.toChar()), 0, 1)
        }

        override fun write(str: String) {
            write(str.toCharArray(), 0, str.length)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private class TextLimitReached : IOException()

    private fun utf8Bytes(codePoint: Int): Int = when {
        codePoint <= 0x7F -> 1
        codePoint <= 0x7FF -> 2
        codePoint <= 0xFFFF -> 3
        else -> 4
    }

    private fun fitLimits(text: String, maxChars: Int, maxBytes: Int): String {
        if (maxChars <= 0 || maxBytes <= 0) return ""
        var chars = 0
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val codePoint = Character.codePointAt(text, end)
            val width = Character.charCount(codePoint)
            val byteWidth = utf8Bytes(codePoint)
            if (chars + width > maxChars) break
            if (bytes + byteWidth > maxBytes) break
            chars += width
            bytes += byteWidth
            end += width
        }
        return text.substring(0, end).trimEnd()
    }
}
