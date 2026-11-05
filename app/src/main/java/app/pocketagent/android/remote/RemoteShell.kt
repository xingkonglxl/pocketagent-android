package app.pocketagent.android.remote

/**
 * 一次远程命令的结果。
 *
 * [exitCode] 为 [RemoteResult.TIMEOUT_EXIT_CODE] 表示执行超时，此时 [timedOut] 为 true；
 * 连接中断或远端没有返回退出码时用 255，不能伪装成成功。
 */
data class RemoteResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** 给工具层 / UI 用的摘要。 */
    fun summary(limit: Int = 4000): String = buildString {
        append("exit=").append(exitCode)
        if (timedOut) append("（超时）")
        if (stdout.isNotBlank()) append('\n').append(clipKeepingEnds(stdout, limit, "stdout"))
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append("stderr: ").append(clipKeepingEnds(stderr, limit, "stderr"))
        }
    }.trim()

    /** 非 0 退出码直接抛 [RemoteCommandException]，给上层工具返回错误文本。 */
    fun requireOk(what: String): RemoteResult {
        if (!ok) throw RemoteCommandException(what, this)
        return this
    }

    companion object {
        const val TIMEOUT_EXIT_CODE: Int = -1

        fun timeout(timeoutMs: Long, stdout: String = "", stderr: String = ""): RemoteResult =
            RemoteResult(
                exitCode = TIMEOUT_EXIT_CODE,
                stdout = stdout,
                stderr = stderr.ifBlank { "命令超时（${timeoutMs} ms）" },
                timedOut = true,
            )
    }
}

class RemoteCommandException(
    val what: String,
    val result: RemoteResult,
) : Exception(
    buildString {
        append(what)
        append(" 失败：exit=")
        append(result.exitCode)
        val detail = result.stderr.trim().ifEmpty { result.stdout.trim() }
        if (detail.isNotEmpty()) {
            append("，")
            append(clipKeepingEnds(detail, 600, "错误详情"))
        }
    },
)

class RemoteConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 远端 shell：一条命令一个 exec，内部自己管连接和超时。 */
interface RemoteShell {
    suspend fun exec(command: String, timeoutMs: Long = 60_000): RemoteResult

    suspend fun close()
}

/** 文件正文走有流量控制的 SSH stdin，命令请求只携带短小的操作指令。 */
interface RemoteInputShell : RemoteShell {
    suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long): RemoteResult
}

/**
 * 能逐行回传 stdout 的 shell。远端 Codex 的 `--json` 是 JSONL，边跑边解析才能把
 * “正在执行命令 / 正在改文件 / 已收到回答”实时显示在手机上，而不是整轮结束才更新。
 */
interface RemoteStreamingShell : RemoteInputShell {
    suspend fun execStreaming(
        command: String,
        input: ByteArray,
        timeoutMs: Long,
        onStdoutLine: (String) -> Unit,
    ): RemoteResult
}

/** 持续写入 stdin 的双向会话，用于 app-server JSON-RPC；关闭 input 会发送 EOF。 */
interface RemoteDuplexShell : RemoteStreamingShell {
    suspend fun execDuplex(
        command: String,
        timeoutMs: Long,
        input: kotlinx.coroutines.channels.ReceiveChannel<ByteArray>,
        onStdoutLine: (String) -> Unit,
    ): RemoteResult
}

/** 工具层只需要这组能力；单测可离线替身，不必真的建 SSH 连接。 */
interface RemoteOperations {
    fun hosts(): List<SshHost>
    fun host(id: String? = null): SshHost?
    suspend fun shell(id: String? = null): RemoteShell
    suspend fun exec(
        command: String,
        timeoutMs: Long = 60_000,
        hostId: String? = null,
    ): RemoteResult
}

/** 远端文件读写，全部走 [RemoteShell.exec]（cat / base64），不额外开 SFTP。 */
interface RemoteFs {
    suspend fun read(
        path: String,
        maxBytes: Int = DEFAULT_MAX_READ_BYTES,
        offsetBytes: Long = 0,
    ): String

    suspend fun write(path: String, content: String)

    suspend fun list(path: String): List<String>
}

const val DEFAULT_MAX_READ_BYTES: Int = 256 * 1024
const val MAX_READ_BYTES: Int = 1024 * 1024

/**
 * 长输出不能只留开头：编译失败和 traceback 的有效信息通常在尾部。
 * 这里保留开头和结尾，并明确告诉调用方省略了多少内容。
 */
fun clipKeepingEnds(text: String, maxChars: Int, label: String): String {
    require(maxChars > 0) { "maxChars 必须为正数" }
    if (text.length <= maxChars) return text
    val headChars = maxChars * 3 / 10
    val tailChars = maxChars - headChars
    val omitted = text.length - maxChars
    val marker = "\n…（$label 已截断：原 ${text.length} 字符，省略 $omitted 字符；" +
        "保留前 $headChars 和后 $tailChars 字符）\n"
    return text.take(headChars) + marker + text.takeLast(tailChars)
}
