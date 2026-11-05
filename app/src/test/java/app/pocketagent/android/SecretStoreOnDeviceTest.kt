package app.pocketagent.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pocketagent.android.security.KeystoreSecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

/**
 * 用注入的 AES key 跑通「Keystore 加密 + 落盘」这条线；
 * 真正的 AndroidKeyStore 硬件保护在 androidTest 里的 KeystoreSecretStoreTest 验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretStoreOnDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun storesOnlyCiphertext() {
        val store = KeystoreSecretStore(context) { key }
        val secret = "sk-test-abcdef123456"

        store.put("p1", secret)
        assertEquals(secret, store.get("p1"))

        val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
        val raw = prefs.all.values.joinToString("|") { it.toString() }
        assertTrue(raw.isNotEmpty())
        assertFalse("落盘内容不能包含明文", raw.contains(secret))

        store.remove("p1")
        assertNull(store.get("p1"))
    }

    @Test
    fun sameValueReencryptsWithFreshIv() {
        val store = KeystoreSecretStore(context) { key }
        store.put("p2", "same")
        val first = context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("p2", null)
        store.put("p2", "same")
        val second = context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("p2", null)
        assertNotEquals(first, second)
        assertEquals("same", store.get("p2"))
        store.remove("p2")
    }
}
