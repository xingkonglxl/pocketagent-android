package app.pocketagent.android

import app.pocketagent.android.agent.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {

    @Test
    fun `parse name and argument`() {
        val p = SlashCommands.parse("/model 2")
        assertEquals("model", p.name)
        assertEquals("2", p.arg)
    }

    @Test
    fun `parse tolerates spaces and missing argument`() {
        val p = SlashCommands.parse("   /help   ")
        assertEquals("help", p.name)
        assertEquals("", p.arg)
    }

    @Test
    fun `parse keeps multi word argument`() {
        val p = SlashCommands.parse("/model DeepSeek 中转")
        assertEquals("model", p.name)
        assertEquals("DeepSeek 中转", p.arg)
    }

    @Test
    fun `isCommand only for leading slash`() {
        assertTrue(SlashCommands.isCommand(" /fast"))
        assertTrue(SlashCommands.isCommand("/model"))
        assertFalse(SlashCommands.isCommand("how do I use /model?"))
        assertFalse(SlashCommands.isCommand("https://example.com/a"))
    }

    @Test
    fun `match suggests by prefix and stops once arguments are typed`() {
        assertEquals(SlashCommands.specs.size, SlashCommands.match("/").size)
        assertEquals(listOf("/model"), SlashCommands.match("/mo").map { it.name })
        assertTrue(SlashCommands.match("/model 2").isEmpty())
        assertTrue(SlashCommands.match("hello").isEmpty())
    }

    @Test
    fun `help text lists every command`() {
        val help = SlashCommands.helpText()
        SlashCommands.specs.forEach { assertTrue("缺少 ${it.name}", help.contains(it.name)) }
    }

    @Test
    fun `every spec name is unique and lower case`() {
        val names = SlashCommands.specs.map { it.name }
        assertEquals(names.size, names.distinct().size)
        names.forEach { assertEquals(it.lowercase(), it) }
    }
}
