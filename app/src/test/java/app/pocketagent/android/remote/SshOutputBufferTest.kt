package app.pocketagent.android.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshOutputBufferTest {

    @Test
    fun `小于上限时逐字节输入也能完整还原`() {
        val text = "第一行\nsecond line\n"
        val buffer = BoundedCommandOutput(maxBytes = text.toByteArray().size + 5)
        text.toByteArray().forEach { byte ->
            buffer.append(byteArrayOf(byte), 0, 1)
        }
        assertEquals(text, buffer.render())
    }

    @Test
    fun `恰好等于上限时头尾重叠不会重复或漏字节`() {
        val text = "0123456789abcdefghij"
        val bytes = text.toByteArray()
        val buffer = BoundedCommandOutput(maxBytes = bytes.size)
        buffer.append(bytes, 0, bytes.size)
        assertEquals(text, buffer.render())
    }

    @Test
    fun `超限时保留真正的开头和结尾并明确提示省略量`() {
        val buffer = BoundedCommandOutput(maxBytes = 60)
        val prefix = "BUILD-START\n"
        val suffix = "\nFINAL ERROR: missing semicolon"
        buffer.append(prefix.toByteArray(), 0, prefix.toByteArray().size)
        val middle = ByteArray(200) { 'x'.code.toByte() }
        buffer.append(middle, 0, middle.size)
        buffer.append(suffix.toByteArray(), 0, suffix.toByteArray().size)

        val rendered = buffer.render()
        assertTrue(rendered.startsWith(prefix))
        assertTrue(rendered.endsWith(suffix))
        assertTrue(rendered.contains("已保留开头和结尾"))
        assertTrue(rendered.contains("省略"))
    }
}
