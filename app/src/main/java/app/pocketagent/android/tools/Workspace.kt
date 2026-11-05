package app.pocketagent.android.tools

import app.pocketagent.android.data.SettingsStore
import app.pocketagent.android.remote.RemoteFs
import app.pocketagent.android.remote.RemoteManager
import app.pocketagent.android.remote.RemoteCommandException
import app.pocketagent.android.remote.RemoteResult
import app.pocketagent.android.remote.RemoteShell
import app.pocketagent.android.remote.shellPath
import app.pocketagent.android.remote.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * 文件工具的后端：手机本地目录，或者远端主机（remote-host / remote-host-b / Mac）上的目录。
 * 远端模式下代码、文档都落在远端，手机只留对话记录，不占空间。
 */
interface Workspace {
    val label: String
    /** 工作区绝对根目录；未知（例如相对路径或纯远端别名）返回 null。 */
    val rootPath: String? get() = null
    suspend fun list(path: String): String
    suspend fun read(
        path: String,
        offsetBytes: Long = 0,
        maxBytes: Int = DEFAULT_WORKSPACE_READ_BYTES,
    ): String
    /**
     * 读原始字节（PDF 等二进制）。默认实现按文本读，具体工作区应覆盖。
     * 这样 `pdf_read` 能跟随 /workspace：切到远端就读远端上的 PDF。
     */
    suspend fun readBytes(
        path: String,
        maxBytes: Int = MAX_WORKSPACE_PDF_BYTES,
    ): ByteArray = read(path, 0, maxBytes).toByteArray(Charsets.UTF_8)
    /**
     * 本地工作区直接给出文件句柄，避免把几十 MB 的 PDF 先读进内存：
     * 之前的 8MB readBytes 上限会把正常报告判成「文件太大」。远端返回 null。
     */
    suspend fun openLocalFile(path: String): File? = null
    suspend fun write(path: String, content: String)
    suspend fun delete(path: String)
    /**
     * 读取工作区外的 Codex 指令文件（AGENTS.md / AGENTS.override.md）。
     * 只用于指令层级发现，不是通用文件读取接口；默认不支持。
     */
    suspend fun readInstruction(path: String): String? = null
    /** 按 glob 匹配相对路径（find / rg --files 的等价物）；null = 该工作区不支持 */
    suspend fun glob(pattern: String, maxResults: Int = 200): List<String>? = null
    /** 全文搜索（rg 的等价物）；null = 该工作区不支持 */
    suspend fun searchText(
        query: String,
        regex: Boolean = false,
        maxResults: Int = 100,
    ): List<TextHit>? = null
}

data class TextHit(val path: String, val line: Int, val text: String)

private const val MAX_SEARCH_FILES = 5_000
private const val MAX_SEARCH_FILE_BYTES = 2L * 1024 * 1024

const val DEFAULT_WORKSPACE_READ_BYTES: Int = 256 * 1024
const val MAX_WORKSPACE_READ_BYTES: Int = 1024 * 1024
/** pdf_read 一次最多取多少字节；远端要留出 base64 膨胀的余量 */
const val MAX_WORKSPACE_PDF_BYTES: Int = 32 * 1024 * 1024
private const val REMOTE_BINARY_CHUNK_BYTES: Int = 3 * 1024 * 1024
private const val MAX_LIST_ENTRIES = 300

class LocalWorkspace(private val root: File) : Workspace {

    override val label: String get() = "手机本地 ${root.absolutePath}"
    override val rootPath: String get() = root.absolutePath

    override suspend fun list(path: String): String = withContext(Dispatchers.IO) {
        val dir = resolveInside(root, path)
        require(dir.exists()) { "目录不存在：$path" }
        require(dir.isDirectory) { "不是目录：$path" }
        val entries = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        if (entries.isEmpty()) return@withContext "（空目录）"
        return@withContext entries.take(MAX_LIST_ENTRIES).joinToString("\n") { f ->
            val kind = if (f.isDirectory) "DIR " else "FILE"
            val size = if (f.isDirectory) "-" else f.length().toString()
            "$kind\t$size\t${f.name}"
        } + if (entries.size > MAX_LIST_ENTRIES) {
            "\n…（只显示前 $MAX_LIST_ENTRIES 项，另有 ${entries.size - MAX_LIST_ENTRIES} 项；请缩小 path）"
        } else {
            ""
        }
    }

    override suspend fun read(path: String, offsetBytes: Long, maxBytes: Int): String =
        withContext(Dispatchers.IO) {
            val file = resolveInside(root, path)
            require(file.exists()) { "文件不存在：$path" }
            require(!file.isDirectory) { "这是目录，不是文件：$path" }
            require(offsetBytes >= 0) { "offset 不能为负数：$offsetBytes" }
            require(maxBytes in 1..MAX_WORKSPACE_READ_BYTES) {
                "limit 必须在 1..$MAX_WORKSPACE_READ_BYTES 之间：$maxBytes"
            }

            val total = file.length()
            require(offsetBytes <= total) {
                "offset=$offsetBytes 超出文件末尾（文件共 $total 字节）"
            }
            if (offsetBytes == total) {
                return@withContext if (total == 0L) "" else "（offset=$offsetBytes 已到文件末尾，共 $total 字节）"
            }

            val count = minOf(maxBytes.toLong(), total - offsetBytes).toInt()
            val bytes = ByteArray(count)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offsetBytes)
                raf.readFully(bytes)
            }
            val next = offsetBytes + count
            val text = String(bytes, Charsets.UTF_8)
            if (next >= total) {
                text
            } else {
                "$text\n…（fs_read 已截断：文件共 $total 字节，本次 offset=$offsetBytes..$next，" +
                    "next_offset=$next。继续读取时保持 path 不变并传 offset=$next）"
            }
        }

    override suspend fun readInstruction(path: String): String? = withContext(Dispatchers.IO) {
        if (!isInstructionPath(path)) return@withContext null
        val file = File(path)
        if (!file.isFile) return@withContext null
        file.inputStream().use { input ->
            val buffer = ByteArray(MAX_INSTRUCTION_BYTES)
            val count = input.read(buffer)
            if (count <= 0) "" else String(buffer, 0, count, Charsets.UTF_8)
        }
    }

    override suspend fun write(path: String, content: String) = withContext(Dispatchers.IO) {
        val file = resolveInside(root, path)
        require(!file.isDirectory) { "目标是目录：$path" }
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override suspend fun readBytes(path: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val file = resolveInside(root, path)
        require(file.exists()) { "文件不存在：$path" }
        require(file.isFile) { "这不是普通文件：$path" }
        val size = file.length()
        require(size <= maxBytes) {
            "文件太大（$path：$size 字节，上限 $maxBytes）；更大的文件请在远端用 ssh_exec 处理"
        }
        file.readBytes()
    }

    override suspend fun openLocalFile(path: String): File? = withContext(Dispatchers.IO) {
        val file = resolveInside(root, path)
        file.takeIf { it.exists() && it.isFile }
    }

    override suspend fun glob(pattern: String, maxResults: Int): List<String> = withContext(Dispatchers.IO) {
        val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern")
        root.walkTopDown()
            .onEnter { dir -> dir == root || !dir.name.startsWith(".") }
            .filter { it.isFile }
            .take(MAX_SEARCH_FILES)
            .mapNotNull { file ->
                val rel = root.toPath().relativize(file.toPath()).toString()
                if (matcher.matches(java.nio.file.Paths.get(rel))) rel else null
            }
            .take(maxResults)
            .toList()
    }

    override suspend fun searchText(query: String, regex: Boolean, maxResults: Int): List<TextHit> =
        withContext(Dispatchers.IO) {
            val compiled = if (regex) Regex(query) else null
            val files = root.walkTopDown()
                .onEnter { dir -> dir == root || !dir.name.startsWith(".") }
                .filter { it.isFile && it.length() in 1..(2L * 1024 * 1024) }
                .take(MAX_SEARCH_FILES)
                .toList()
            val hits = mutableListOf<TextHit>()
            outer@ for (file in files) {
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                // 跳过二进制：前 4KB 出现 NUL 字节就认为不是文本
                if (bytes.take(4_096).any { it == 0.toByte() }) continue
                val rel = root.toPath().relativize(file.toPath()).toString()
                var lineNo = 0
                for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
                    lineNo++
                    val hit = if (compiled != null) compiled.containsMatchIn(line) else line.contains(query, ignoreCase = true)
                    if (hit) {
                        hits += TextHit(rel, lineNo, line.trim().take(300))
                        if (hits.size >= maxResults) break@outer
                    }
                }
            }
            hits
        }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val file = resolveInside(root, path)
        require(file.exists()) { "文件不存在：$path" }
        require(!file.isDirectory) { "拒绝删除目录：$path" }
        require(file.delete()) { "删除失败：$path" }
    }
}

/** 远端工作区：所有路径都在 [baseDir] 下，`..` 一律拒绝。 */
class RemoteWorkspace(
    private val shell: RemoteShell,
    private val fs: RemoteFs,
    private val baseDir: String,
    private val hostLabel: String,
) : Workspace {

    override val label: String get() = "$hostLabel:$baseDir"
    override val rootPath: String get() = baseDir

    private fun resolve(path: String): String {
        val rel = path.trim().removePrefix("./").trimStart('/')
        require(rel.split('/').none { it == ".." }) { "路径越界：$path" }
        val base = baseDir.trimEnd('/')
        return if (rel.isEmpty() || rel == ".") base else "$base/$rel"
    }

    override suspend fun list(path: String): String {
        val entries = fs.list(resolve(path))
        if (entries.isEmpty()) return "（空目录）"
        return entries.take(MAX_LIST_ENTRIES).joinToString("\n") +
            if (entries.size > MAX_LIST_ENTRIES) {
                "\n…（只显示前 $MAX_LIST_ENTRIES 项，另有 ${entries.size - MAX_LIST_ENTRIES} 项；" +
                    "请缩小 path 或改用 ssh_exec 的 find/rg）"
            } else {
                ""
            }
    }

    override suspend fun read(path: String, offsetBytes: Long, maxBytes: Int): String =
        fs.read(resolve(path), maxBytes, offsetBytes)

    override suspend fun readInstruction(path: String): String? {
        if (!isInstructionPath(path)) return null
        val quoted = instructionShellPath(path)
        val result = shell.exec(
            "if [ -f $quoted ]; then cat -- $quoted; fi",
            20_000,
        )
        return result.stdout.takeIf { result.ok && it.isNotBlank() }
    }

    override suspend fun readBytes(path: String, maxBytes: Int): ByteArray {
        return readRemoteBytesChunked(
            shell = shell,
            path = resolve(path),
            maxBytes = maxBytes,
            chunkBytes = REMOTE_BINARY_CHUNK_BYTES,
        )
    }

    override suspend fun glob(pattern: String, maxResults: Int): List<String>? {
        val base = shellPath(resolve("."))
        val cmd = "cd $base && find . -type f -path ${shellQuote("./$pattern")} 2>/dev/null | head -n $maxResults"
        val result = shell.exec(cmd, 60_000)
        if (!result.ok && result.stdout.isBlank()) return null
        return result.stdout.lineSequence()
            .map { it.trim().removePrefix("./") }
            .filter { it.isNotBlank() }
            .toList()
    }

    override suspend fun searchText(query: String, regex: Boolean, maxResults: Int): List<TextHit>? {
        val base = shellPath(resolve("."))
        val flag = if (regex) "E" else "F"
        val cmd = "cd $base && grep -rIn$flag --exclude-dir=.git --exclude-dir=node_modules " +
            "-e ${shellQuote(query)} . 2>/dev/null | head -n $maxResults"
        val result = shell.exec(cmd, 120_000)
        if (!result.ok && result.stdout.isBlank()) return null
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.split(':', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            TextHit(
                path = parts[0].trim().removePrefix("./"),
                line = parts[1].toIntOrNull() ?: 0,
                text = parts[2].trim().take(300),
            )
        }.toList()
    }


    override suspend fun write(path: String, content: String) {
        fs.write(resolve(path), content)
    }

    override suspend fun delete(path: String) {
        val target = resolve(path)
        val quoted = shellQuote(target)
        val command = "test ! -d $quoted || { printf '拒绝删除目录: %s\\n' $quoted >&2; exit 2; }; " +
            "test -e $quoted || { printf '文件不存在: %s\\n' $quoted >&2; exit 1; }; " +
            "rm -f -- $quoted"
        val result = shell.exec(command, 30_000)
        require(result.ok) { "删除失败：${result.summary(limit = 500)}" }
    }
}

/**
 * 远端 PDF/二进制分块读取。
 *
 * SSH 通道对单次 stdout 有上限，不能直接 `base64 < file` 拉几十 MB。这里先用 `wc -c`
 * 得到大小，再按块执行 `tail | head | base64`，每块约 3MB，base64 膨胀后仍低于传输上限。
 */
internal suspend fun readRemoteBytesChunked(
    shell: RemoteShell,
    path: String,
    maxBytes: Int = MAX_WORKSPACE_PDF_BYTES,
    chunkBytes: Int = REMOTE_BINARY_CHUNK_BYTES,
): ByteArray {
    require(maxBytes > 0) { "maxBytes 必须大于 0" }
    require(chunkBytes in 1..4 * 1024 * 1024) { "chunkBytes 必须在 1..4MiB 之间" }
    val target = shellPath(path)
    val sizeResult = shell.exec("wc -c < $target", 30_000).requireOk("读取远端文件大小")
    val size = sizeResult.stdout.trim().toLongOrNull()
        ?: throw RemoteCommandException(
            "读取远端文件大小",
            RemoteResult(1, sizeResult.stdout, "无法解析文件大小"),
        )
    if (size <= 0L) return ByteArray(0)
    require(size <= maxBytes) {
        "远端文件太大（$size 字节），上限 $maxBytes；请先在远端处理或拆分"
    }

    val out = ByteArrayOutputStream(size.toInt())
    var offset = 0L
    while (offset < size) {
        val count = minOf(chunkBytes.toLong(), size - offset).toInt()
        val command = "tail -c +${offset + 1} -- $target | head -c $count | base64 | tr -d '\\n'"
        val result = shell.exec(command, 120_000).requireOk("分块读取远端文件")
        val body = result.stdout.filterNot { it.isWhitespace() }
        val bytes = runCatching { java.util.Base64.getDecoder().decode(body) }
            .getOrElse {
                throw RemoteCommandException(
                    "解码远端文件块",
                    RemoteResult(1, result.stdout, it.message ?: it.javaClass.simpleName),
                )
            }
        require(bytes.size == count) {
            "远端文件在读取过程中发生变化：期望 $count 字节，实际 ${bytes.size} 字节"
        }
        out.write(bytes)
        offset += count
    }
    return out.toByteArray()
}

/**
 * 按设置决定走本地还是远端。设置改了立刻生效，不用重建工具。
 */
class WorkspaceSwitcher(
    private val settings: SettingsStore,
    private val local: LocalWorkspace,
    private val remote: RemoteManager,
) : Workspace {

    private fun current(): Workspace {
        val hostId = settings.remoteWorkspaceHostId ?: return local
        val host = remote.host(hostId) ?: return local
        val shell = app.pocketagent.android.remote.LazyRemoteShell(remote, hostId)
        return RemoteWorkspace(
            shell = shell,
            fs = app.pocketagent.android.remote.ShellRemoteFs(shell),
            // 优先用这台主机自己配的工作区目录，老配置回落到全局设置
            baseDir = host.workspaceDir.ifBlank {
                settings.remoteWorkspaceDir.ifBlank { "~/pocketagent" }
            },
            hostLabel = host.name,
        )
    }

    override val label: String get() = current().label
    override val rootPath: String? get() = current().rootPath
    override suspend fun list(path: String) = current().list(path)
    override suspend fun read(path: String, offsetBytes: Long, maxBytes: Int) =
        current().read(path, offsetBytes, maxBytes)
    override suspend fun readBytes(path: String, maxBytes: Int) = current().readBytes(path, maxBytes)
    override suspend fun openLocalFile(path: String) = current().openLocalFile(path)
    override suspend fun glob(pattern: String, maxResults: Int) = current().glob(pattern, maxResults)
    override suspend fun searchText(query: String, regex: Boolean, maxResults: Int) =
        current().searchText(query, regex, maxResults)
    override suspend fun write(path: String, content: String) = current().write(path, content)
    override suspend fun delete(path: String) = current().delete(path)
    override suspend fun readInstruction(path: String): String? = current().readInstruction(path)
}

private const val MAX_INSTRUCTION_BYTES = 64 * 1024

private fun isInstructionPath(path: String): Boolean {
    val name = path.trimEnd('/').substringAfterLast('/')
    return name == "AGENTS.md" || name == "AGENTS.override.md"
}

private fun instructionShellPath(path: String): String = when {
    path == "~" -> "\"\$HOME\""
    path.startsWith("~/") -> "\"\$HOME\"/" + shellQuote(path.removePrefix("~/"))
    else -> shellQuote(path)
}
