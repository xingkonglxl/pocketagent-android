package app.pocketagent.android

import app.pocketagent.android.data.ModelProbe
import app.pocketagent.android.data.ModelProbeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelProbeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `probe results persist and merge`() {
        val dir = tmp.newFolder()
        val store = ModelProbeStore(dir)
        assertTrue(store.load().isEmpty())

        store.put(
            listOf(
                ModelProbe("gpt-6-astra", ok = true),
                ModelProbe("gpt-5.5", ok = false, error = "Model is not supported"),
            ),
        )

        val reloaded = ModelProbeStore(dir).load()
        assertEquals(2, reloaded.size)
        assertTrue(reloaded.getValue("gpt-6-astra").ok)
        assertFalse(reloaded.getValue("gpt-5.5").ok)
        assertEquals("Model is not supported", reloaded.getValue("gpt-5.5").error)

        store.put(listOf(ModelProbe("gpt-5.5", ok = true)))
        assertTrue(ModelProbeStore(dir).load().getValue("gpt-5.5").ok)
    }
}
