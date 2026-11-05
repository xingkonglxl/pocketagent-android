package app.pocketagent.android

import app.pocketagent.android.security.SecretEnvelope
import app.pocketagent.android.security.maskSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class SecretEnvelopeTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `seal and open roundtrip`() {
        val plain = "sk-abcdef123456".toByteArray()
        val blob = SecretEnvelope.seal(key, plain)
        assertNotEquals(String(plain), String(blob, Charsets.ISO_8859_1))
        assertEquals("sk-abcdef123456", String(SecretEnvelope.open(key, blob)))
    }

    @Test
    fun `random iv means different ciphertext each time`() {
        val a = SecretEnvelope.seal(key, "same".toByteArray())
        val b = SecretEnvelope.seal(key, "same".toByteArray())
        assertFalse(a.contentEquals(b))
        assertEquals("same", String(SecretEnvelope.open(key, a)))
        assertEquals("same", String(SecretEnvelope.open(key, b)))
    }

    @Test(expected = Exception::class)
    fun `tampered ciphertext is rejected`() {
        val blob = SecretEnvelope.seal(key, "secret".toByteArray())
        blob[blob.lastIndex] = (blob.last() + 1).toByte()
        SecretEnvelope.open(key, blob)
    }

    @Test
    fun `mask only reveals tail`() {
        assertEquals("未配置", maskSecret(null))
        assertEquals("••••（已保存）", maskSecret("short"))
        val masked = maskSecret("sk-1234567890abcd")
        assertTrue(masked.startsWith("••••"))
        assertFalse("不能回显 Key 的任何片段：$masked", masked.contains("abcd"))
        assertFalse(masked.contains("1234567890"))
    }
}
