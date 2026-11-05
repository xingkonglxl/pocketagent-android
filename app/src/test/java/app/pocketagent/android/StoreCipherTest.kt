package app.pocketagent.android

import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.ConversationStore
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.StoreCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class StoreCipherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `encrypt then decrypt roundtrip`() {
        val cipher = StoreCipher(key())
        val plain = "会话正文 🔐 with text".toByteArray()
        val sealed = cipher.encrypt(plain)
        assertTrue(sealed.size > plain.size)
        assertEquals(String(plain), String(cipher.decrypt(sealed)!!))
    }

    @Test
    fun `writes plaintext but can still read old ciphertext`() {
        val dir = tmp.newFolder()
        val cipher = StoreCipher(key())

        // 模拟老版本写下的密文
        val old = Conversation(
            title = "老密文",
            messages = listOf(ChatMessage(role = Role.USER, text = "hunter2")),
        )
        val json = kotlinx.serialization.builtins.ListSerializer(Conversation.serializer())
            .let { app.pocketagent.android.data.AppJson.encodeToString(it, listOf(old)) }
        java.io.File(dir, "conversations.json").writeBytes(cipher.encrypt(json.toByteArray()))

        val store = ConversationStore(dir, cipher = cipher)
        assertEquals("老密文", store.load().single().title)

        // 再写一次 → 变回明文，之后即使没有密钥也能读
        store.upsert(store.load().single().copy(title = "迁移后"))
        val raw = java.io.File(dir, "conversations.json").readBytes()
        assertTrue("新写入必须是明文", String(raw, Charsets.UTF_8).contains("迁移后"))
        assertEquals("迁移后", ConversationStore(dir, cipher = null).load().single().title)
    }

    @Test
    fun `old plaintext file still loads`() {
        val dir = tmp.newFolder()
        val plainStore = ConversationStore(dir)
        plainStore.upsert(Conversation(title = "老数据", messages = listOf(ChatMessage(role = Role.USER, text = "hi"))))

        val cipher = StoreCipher(key())
        val store = ConversationStore(dir, cipher = cipher)
        assertEquals("老数据", store.load().first().title)
    }
}
