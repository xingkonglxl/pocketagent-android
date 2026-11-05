package app.pocketagent.android

import app.pocketagent.android.remote.writeFileCommands
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 大会话分块必须同时满足 SSH 单包和 shell 参数的大小上限。
 * 这里验证分块写：多条命令、`>` 后 `>>`、拼起来和原文逐字节一致。
 */
class ShellChunkedWriteTest {

    private fun payloadOf(command: String): String {
        val start = command.indexOf("printf %s '") + "printf %s '".length
        val end = command.indexOf("' | base64 -d")
        return command.substring(start, end)
    }

    @Test
    fun `small content stays in a single command`() {
        val commands = writeFileCommands("sessions/a.json", """{"hello":"世界"}""")
        assertEquals(1, commands.size)
        assertTrue(commands[0].contains("mkdir -p"))
    }

    @Test
    fun `large content is chunked and reassembles byte for byte`() {
        val content = "会话正文".repeat(120_000) // 约 1.4MB UTF-8
        val commands = writeFileCommands("sessions/big.json", content)

        assertTrue("应该拆成多条命令，实际 ${commands.size}", commands.size > 5)
        assertTrue(commands[0].contains("mkdir -p"))
        // 第一条覆盖写，其余追加
        assertTrue(commands[1].contains(" > "))
        assertTrue(commands[2].contains(" >> "))

        val rebuilt = commands.drop(1)
            .flatMap { Base64.getDecoder().decode(payloadOf(it)).toList() }
            .toByteArray()
        assertArrayEquals(content.toByteArray(Charsets.UTF_8), rebuilt)
        assertTrue("默认分块编码后也必须远低于 256KiB", commands.all { it.toByteArray().size < 128 * 1024 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized chunks are rejected before an SSH connection can be broken`() {
        writeFileCommands("x", "test", chunkBytes = 192 * 1024)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero chunk cannot cause an endless loop`() {
        writeFileCommands("x", "test", chunkBytes = 0)
    }
}
