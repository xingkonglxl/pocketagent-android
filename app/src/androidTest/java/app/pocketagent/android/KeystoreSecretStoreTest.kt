package app.pocketagent.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pocketagent.android.security.KeystoreSecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun roundtripThroughAndroidKeystore() {
        val store = KeystoreSecretStore(context)
        val secret = "sk-live-probe-" + System.nanoTime()

        store.put("probe", secret)
        assertEquals(secret, store.get("probe"))

        store.put("probe", secret + "-v2")
        assertEquals(secret + "-v2", store.get("probe"))
        assertNotEquals(secret, store.get("probe"))

        store.remove("probe")
        assertNull(store.get("probe"))
    }

    @Test
    fun plaintextNeverReachesSharedPreferences() {
        val store = KeystoreSecretStore(context)
        val secret = "sk-plaintext-${System.nanoTime()}"
        store.put("disk", secret)

        val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
        val dumped = prefs.all.values.joinToString("|") { it.toString() }
        assertTrue("应该写入了密文", dumped.isNotEmpty())
        assertFalse("不能出现明文", dumped.contains(secret))
        assertNotNull(store.get("disk"))

        store.remove("disk")
    }

    @Test
    fun differentWritesProduceDifferentCiphertext() {
        val store = KeystoreSecretStore(context)
        store.put("a", "same-value")
        val first = context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("a", null)
        store.put("a", "same-value")
        val second = context.getSharedPreferences("secrets", Context.MODE_PRIVATE).getString("a", null)
        assertNotEquals(first, second)
        assertEquals("same-value", store.get("a"))
        store.remove("a")
    }
}
