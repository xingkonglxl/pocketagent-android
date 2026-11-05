package app.pocketagent.android.remote

/**
 * 用 exec 实现的远端文件读写：读 `head -c`，写 `base64 -d`，列目录 `ls -1A`。
 * 相对路径落在 [RemoteShell] 绑定的 workDir 下。
 */
class ShellRemoteFs(
    private val shell: RemoteShell,
    private val timeoutMs: Long = 60_000,
) : RemoteFs {

    override suspend fun read(path: String, maxBytes: Int, offsetBytes: Long): String {
        require(maxBytes in 1..MAX_READ_BYTES) {
            "maxBytes 必须在 1..$MAX_READ_BYTES 之间：$maxBytes"
        }
        require(offsetBytes >= 0) { "offsetBytes 不能为负数：$offsetBytes" }
        val result = shell.exec(
            readFileCommand(path, maxBytes, offsetBytes),
            timeoutMs,
        ).requireOk("读取 $path")
        val bytes = result.stdout.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes && !(bytes.isEmpty() && offsetBytes > 0)) return result.stdout
        if (bytes.isEmpty()) {
            return "（offset=$offsetBytes 之后没有内容，可能已到文件末尾）"
        }
        val clipped = String(bytes, 0, maxBytes, Charsets.UTF_8).trimEnd('\uFFFD')
        val next = offsetBytes + maxBytes
        return "$clipped\n…（ssh_read 已截断：本次 offset=$offsetBytes..$next，next_offset=$next；" +
            "继续读取时保持 path 不变并传 offset=$next）"
    }

    override suspend fun write(path: String, content: String) {
        // 64KiB 原始数据编码后约 86KiB，低于 SSH 请求包和 shell 参数长度上限。
        for (command in writeFileCommands(path, content)) {
            shell.exec(command, timeoutMs).requireOk("写入 $path")
        }
    }

    /** 同目录暂存，完整写入并校验长度后原子替换，断线不会破坏原来的会话。 */
    suspend fun writeAtomic(path: String, content: String) {
        val temporary = "$path.upload-${java.util.UUID.randomUUID()}.tmp"
        val temp = shellPath(temporary)
        val target = shellPath(path)
        if (shell is RemoteInputShell) {
            val original = content.toByteArray(Charsets.UTF_8)
            val compressed = java.io.ByteArrayOutputStream().also { bytes ->
                java.util.zip.GZIPOutputStream(bytes).use { it.write(original) }
            }.toByteArray()
            val useCompression = compressed.size < original.size
            val input = if (useCompression) compressed else original
            val receive = if (useCompression) "gzip -dc" else "cat"
            val cleanup = "rm -f -- $temp"
            val command = "umask 077; mkdir -p -- \"\$(dirname -- $target)\" || exit 1; " +
                "(set -C; : > $temp) || exit 1; " +
                "trap ${shellQuote(cleanup)} EXIT; " +
                "$receive > $temp && test \"\$(wc -c < $temp | tr -d '[:space:]')\" = ${original.size} && " +
                "chmod 600 $temp && mv -f -- $temp $target"
            shell.execWithInput(command, input, maxOf(timeoutMs, 300_000))
                .requireOk("流式上传完整会话")
            return
        }
        var committed = false
        try {
            shell.exec(
                "umask 077; mkdir -p -- \"\$(dirname -- $target)\" && (set -C; : > $temp)",
                timeoutMs,
            ).requireOk("创建同步暂存文件")
            for (command in writeFileCommands(temporary, content, makeParents = false)) {
                shell.exec(command, timeoutMs).requireOk("上传会话分块")
            }
            val expectedBytes = content.toByteArray(Charsets.UTF_8).size
            shell.exec(
                "test \"\$(wc -c < $temp | tr -d '[:space:]')\" = $expectedBytes && " +
                    "chmod 600 $temp && mv -f -- $temp $target",
                timeoutMs,
            ).requireOk("提交完整会话")
            committed = true
        } finally {
            if (!committed) {
                // 清理失败也不能掩盖原始异常；残留的暂存文件为 0600，清单不读取它。
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { shell.exec("rm -f -- $temp", 5_000) }
                }
            }
        }
    }

    override suspend fun list(path: String): List<String> {
        val result = shell.exec(listCommand(path), timeoutMs).requireOk("列出 $path")
        return parseListing(result.stdout)
    }
}
