package app.pocketagent.android.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ShellCommandsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private data class ShResult(val exit: Int, val stdout: String, val stderr: String)

    /** 只在本机跑 /bin/sh 验证拼装结果，不碰网络。 */
    private fun sh(command: String): ShResult {
        val process = ProcessBuilder("/bin/sh", "-c", command)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = process.errorStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(30, TimeUnit.SECONDS)) { "sh 卡住了：$command" }
        return ShResult(process.exitValue(), out, err)
    }

    @Test
    fun `单引号转义保留引号换行中文和美元符`() {
        assumeTrue(File("/bin/sh").exists())
        val raw = "it's \"quoted\" \$HOME `whoami` 中文\n第二行\t结束"
        val quoted = shellQuote(raw)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        assertTrue("单引号要变成 '\\''", quoted.contains("'\\''"))

        val r = sh("printf %s $quoted")
        assertEquals(0, r.exit)
        assertEquals(raw, r.stdout)
    }

    @Test
    fun `空串和只有单引号`() {
        assumeTrue(File("/bin/sh").exists())
        assertEquals("", sh("printf %s " + shellQuote("")).stdout)
        assertEquals("'", sh("printf %s " + shellQuote("'")).stdout)
        assertEquals("'''", sh("printf %s " + shellQuote("'''")).stdout)
    }

    @Test
    fun `tilde 走 HOME 而不是被单引号吃掉`() {
        assertEquals("\"\$HOME\"", shellPath("~"))
        assertEquals("\"\$HOME\"/'Projects'", shellPath("~/Projects"))
        assertEquals("\"\$HOME\"/'a b/c.txt'", shellPath("~/a b/c.txt"))
        assertEquals("~deploy/'x y'", shellPath("~deploy/x y"))
        assertEquals("'/tmp/a b'", shellPath("/tmp/a b"))
        assertEquals("'./rel ative'", shellPath("./rel ative"))
        assertEquals("''", shellPath(""))
    }

    @Test
    fun `workDir 包成子 shell`() {
        assertEquals("pwd", wrapWithWorkDir("pwd", null))
        assertEquals("pwd", wrapWithWorkDir("pwd", "  "))
        assertEquals("(cd \"\$HOME\"/'proj' && {\npwd\n})", wrapWithWorkDir("pwd", "~/proj"))
        assertEquals(
            "(cd '/data/kpfs' && {\nls -1A -- '.'\n})",
            wrapWithWorkDir("ls -1A -- '.'", "/data/kpfs"),
        )
    }

    @Test
    fun `workDir 里的多行脚本和结尾注释不会被括号吃掉`() {
        assumeTrue(File("/bin/sh").exists())
        val dir = tmp.newFolder()
        val script = "echo one\n# 注释不能吃掉右括号\necho two"
        val r = sh(wrapWithWorkDir(script, dir.absolutePath))
        assertEquals("stderr=${r.stderr}", 0, r.exit)
        assertEquals("one\ntwo\n", r.stdout)

        // cd 失败就不该继续执行，并且要非 0
        val bad = sh(wrapWithWorkDir("echo should-not-run", File(dir, "missing").absolutePath))
        assertTrue("cd 失败要非 0：${bad.exit}", bad.exit != 0)
        assertEquals("", bad.stdout)
    }

    @Test
    fun `writeFileCommand 原样落盘`() {
        assumeTrue(File("/bin/sh").exists())
        val dir = tmp.newFolder()
        val target = File(dir, "a b'c 中文.txt")
        val content = "第一行\nsecond 'line' with \$HOME and `cmd`\n反斜杠 \\ 结尾无换行"

        val r = sh(writeFileCommand(target.absolutePath, content))
        assertEquals("stderr=${r.stderr}", 0, r.exit)
        assertEquals(content, target.readText())
    }

    @Test
    fun `writeFileCommand 建父目录并保留空文件和末尾换行`() {
        assumeTrue(File("/bin/sh").exists())
        val dir = tmp.newFolder()
        val nested = File(dir, "x/y/z.txt")

        assertEquals(0, sh(writeFileCommand(nested.absolutePath, "abc\n")).exit)
        assertEquals("abc\n", nested.readText())

        assertEquals(0, sh(writeFileCommand(File(dir, "empty.txt").absolutePath, "")).exit)
        assertEquals("", File(dir, "empty.txt").readText())
    }

    @Test
    fun `readFileCommand 能读回写进去的内容并多要一字节`() {
        assumeTrue(File("/bin/sh").exists())
        val dir = tmp.newFolder()
        val target = File(dir, "中文 名.txt")
        val content = "你好\nworld"
        assertEquals(0, sh(writeFileCommand(target.absolutePath, content)).exit)

        val cmd = readFileCommand(target.absolutePath, maxBytes = 64)
        assertTrue(cmd.startsWith("head -c 65 -- "))
        assertEquals(content, sh(cmd).stdout)
    }

    @Test
    fun `readFileCommand 支持从字节 offset 续读`() {
        assumeTrue(File("/bin/sh").exists())
        val dir = tmp.newFolder()
        val target = File(dir, "log.txt")
        target.writeText("0123456789")

        val cmd = readFileCommand(target.absolutePath, maxBytes = 3, offsetBytes = 4)
        assertTrue(cmd.contains("tail -c +5"))
        assertEquals("4567", sh(cmd).stdout)
    }

    @Test
    fun `list 命令列隐藏文件`() {
        assumeTrue(File("/bin/sh").exists())
        val dir = tmp.newFolder()
        File(dir, "a.txt").writeText("x")
        File(dir, ".hidden").writeText("y")
        File(dir, "sub").mkdir()

        val r = sh(listCommand(dir.absolutePath))
        assertEquals(0, r.exit)
        assertEquals(listOf(".hidden", "a.txt", "sub"), parseListing(r.stdout))
    }

    @Test
    fun `parseListing 忽略空行和 CRLF`() {
        assertEquals(listOf("a", "b c"), parseListing("a\r\nb c\r\n\r\n"))
        assertEquals(emptyList<String>(), parseListing(""))
    }
}
