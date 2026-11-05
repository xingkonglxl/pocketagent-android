package app.pocketagent.android

import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.RemoteShell
import app.pocketagent.android.tools.detectRemoteCodex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Execute the actual discovery shell with isolated, synthetic installations; never calls an API. */
class HarnessDiscoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun shell(root: File) = object : RemoteShell {
        override suspend fun exec(command: String, timeoutMs: Long): RemoteResult {
            val isolated = command.replace("\$HOME", root.absolutePath)
                .replace("/opt/homebrew/bin", root.resolve("missing-brew").path)
                .replace("/usr/local/bin", root.resolve("missing-local").path)
            val process = ProcessBuilder("/bin/sh", "-c", isolated)
                .apply { environment()["PATH"] = "/usr/bin:/bin" }.start()
            check(process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { "discovery hung" }
            return RemoteResult(process.exitValue(), process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText())
        }
        override suspend fun close() {}
    }
    private fun install(root: File, path: String, compatible: Boolean = true): File {
        val executable = root.resolve(path)
        executable.parentFile.mkdirs()
        executable.writeText("#!/bin/sh\ncase \"\$*\" in\n--version) node ;;\n'exec --help') echo '${if (compatible) "--json" else "unrelated-command"}' ;;\n*) exit 2 ;;\nesac\n")
        executable.setExecutable(true)
        executable.parentFile.resolve("node").apply {
            writeText("#!/bin/sh\necho codex-cli-test\n"); setExecutable(true)
        }
        return executable
    }
    @Test fun `finds NVM fnm mise and local installs including sibling node`() = runBlocking {
        for (location in listOf(".local/bin/codex", ".nvm/versions/node/v22/bin/codex", ".local/share/fnm/node-versions/v22/installation/bin/codex", ".local/share/mise/installs/node/22/bin/codex")) {
            val root = tmp.newFolder()
            install(root, location)
            assertTrue(location, detectRemoteCodex(shell(root)))
        }
    }
    @Test fun `custom executable supports spaces and shell metacharacters as literal filename`() = runBlocking {
        val root = tmp.newFolder()
        val relative = "custom tools/codex; touch SHOULD_NOT_EXIST"
        install(root, relative)
        assertTrue(detectRemoteCodex(shell(root), executable = "~/$relative"))
        assertFalse(File("SHOULD_NOT_EXIST").exists())
    }
    @Test fun `missing auto install differs from broken explicit path or incompatible CLI`() = runBlocking {
        val root = tmp.newFolder()
        assertFalse(detectRemoteCodex(shell(root)))
        assertTrue(runCatching { detectRemoteCodex(shell(root), executable = "~/missing") }.isFailure)
        install(root, ".local/bin/codex", compatible = false)
        assertTrue(runCatching { detectRemoteCodex(shell(root)) }.isFailure)
    }
}
