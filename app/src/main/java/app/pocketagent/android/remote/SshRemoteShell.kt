package app.pocketagent.android.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用 sshj 实现的远程 shell：一个 [SshRemoteShell] 复用一个 SSH 连接，每次 exec 开一个
 * session channel，stdout/stderr 并发读（避免管道写满把远端卡死），超时就掐断连接。
 *
 * 必须显式传入 [hostKeyVerifier]，不能遗漏校验而默认信任任何服务器。
 */
class SshRemoteShell(
    private val host: SshHost,
    private val keyLoader: SshKeyLoader = SshKeyLoader.Pem,
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectTimeoutMs: Int = 15_000,
    // 整组会话正文可能很大（几百 KB～几 MB），上限放宽；同步要求不截断
    private val maxOutputBytes: Int = 8 * 1024 * 1024,
) : RemoteDuplexShell {

    private val connectLock = Mutex()

    /** 一个连接的命令按顺序执行，断线清理不会打断另一个活动命令。 */
    private val execLock = Mutex()

    private val io: ExecutorService = Executors.newCachedThreadPool(object : ThreadFactory {
        private val seq = AtomicInteger()
        override fun newThread(r: Runnable): Thread =
            Thread(r, "sshj-io-${seq.incrementAndGet()}").apply { isDaemon = true }
    })

    @Volatile
    private var client: SSHClient? = null

    /** 走 ProxyJump 时的跳板连接，和 [client] 一起关。 */
    @Volatile
    private var jump: SSHClient? = null

    @Volatile
    private var closed = false

    override suspend fun exec(command: String, timeoutMs: Long): RemoteResult = execute(command, timeoutMs, null)

    override suspend fun execWithInput(command: String, input: ByteArray, timeoutMs: Long): RemoteResult =
        execute(command, timeoutMs, input)

    override suspend fun execStreaming(
        command: String,
        input: ByteArray,
        timeoutMs: Long,
        onStdoutLine: (String) -> Unit,
    ): RemoteResult = execute(command, timeoutMs, input, onStdoutLine)

    override suspend fun execDuplex(
        command: String,
        timeoutMs: Long,
        input: ReceiveChannel<ByteArray>,
        onStdoutLine: (String) -> Unit,
    ): RemoteResult = withContext(Dispatchers.IO) {
        check(!closed) { "SshRemoteShell 已关闭，请重新创建" }
        execLock.withLock {
            val ssh = clientOrConnect()
            val session = channelOpen("打开 SSH 双向会话", connectTimeoutMs.toLong()) { ssh.startSession() }
            try {
                runDuplex(session, wrapWithWorkDir(command, host.workDir), timeoutMs, input, onStdoutLine)
            } finally {
                withContext(NonCancellable) { closeSessionQuietly(session) }
            }
        }
    }

    private suspend fun runDuplex(
        session: Session,
        command: String,
        timeoutMs: Long,
        input: ReceiveChannel<ByteArray>,
        onStdoutLine: (String) -> Unit,
    ): RemoteResult = coroutineScope {
        val cmd = channelOpen("启动远端双向命令", connectTimeoutMs.toLong()) { session.exec(command) }
        val stdout = io.submit(Callable { drainLines(cmd.inputStream, onStdoutLine) })
        val stderr = io.submit(Callable { drain(cmd.errorStream) })
        val writer = async(Dispatchers.IO) {
            cmd.outputStream.use { output ->
                for (chunk in input) {
                    currentCoroutineContext().ensureActive()
                    var offset = 0
                    while (offset < chunk.size) {
                        val count = minOf(32 * 1024, chunk.size - offset)
                        output.write(chunk, offset, count)
                        offset += count
                    }
                    output.flush()
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
        try {
            // SSHJ join(timeout) 超时会抛 ConnectionException，不能当作 Thread.join 轮询。
            // 只等待协程 delay，再读取 channel 状态；正常长任务不会在第一个 100ms 被掐断。
            while (cmd.isOpen && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                delay(100)
            }
            val timedOut = cmd.isOpen
            if (timedOut) abort()
            writer.cancel()
            val out = stdout.awaitText()
            val err = stderr.awaitText()
            if (timedOut) RemoteResult.timeout(timeoutMs, out, err)
            else RemoteResult(cmd.exitStatus ?: 255, out, err)
        } finally {
            // 先关 channel 解除被 SSH 窗口阻塞的 writer，再等待子协程退出。
            writer.cancel()
            withContext(NonCancellable) {
                if (cmd.isOpen) abort()
                closeSessionQuietly(session)
            }
            stdout.cancel(true)
            stderr.cancel(true)
        }
    }

    private suspend fun execute(
        command: String,
        timeoutMs: Long,
        input: ByteArray?,
        onStdoutLine: ((String) -> Unit)? = null,
    ): RemoteResult = withContext(Dispatchers.IO) {
        check(!closed) { "SshRemoteShell 已关闭，请重新创建" }
        execLock.withLock {
            val full = wrapWithWorkDir(command, host.workDir)
            // 仅记长度，命令可能带完整正文、base64 文件内容或凭证。
            val commandBytes = full.toByteArray(Charsets.UTF_8).size
            try {
                execOnce(full, timeoutMs, input, onStdoutLine).also {
                    SshDiagnostics.record("exec exit=${it.exitCode} requestBytes=$commandBytes inputBytes=${input?.size ?: 0}")
                }
            } catch (e: RemoteConnectionException) {
                // exec 请求的确认也可能丢失，不能自动重放追加写入/删除等命令。
                SshDiagnostics.record("exec 连接失败 requestBytes=$commandBytes")
                dropAll()
                throw e
            }
        }
    }

    private suspend fun execOnce(
        full: String,
        timeoutMs: Long,
        input: ByteArray?,
        onStdoutLine: ((String) -> Unit)?,
    ): RemoteResult {
        val ssh = clientOrConnect()
        val session = try {
            channelOpen("打开 SSH 会话", connectTimeoutMs.toLong()) { ssh.startSession() }
        } catch (e: Exception) {
            dropClient(ssh)
            throw RemoteConnectionException("在 ${host.target} 上打开会话失败：${describe(e)}", e)
        }
        try {
            return runCommand(session, full, timeoutMs, input, onStdoutLine)
        } catch (e: RemoteConnectionException) {
            // 连接层失败：把半死的连接丢掉，下一次调用重建，而不是让缓存的死连接
            // 一直报 "Broken transport; encountered EOF"。
            SshDiagnostics.record(
                "命令阶段连接失败：${e.javaClass.simpleName}",
            )
            dropClient(ssh)
            throw e
        } finally {
            closeSessionQuietly(session)
        }
    }

    override suspend fun close() {
        closed = true
        withContext(Dispatchers.IO) {
            val (current, hop) = connectLock.withLock {
                val c = client
                val j = jump
                client = null
                jump = null
                c to j
            }
            disconnectQuietly(current, hop)
            io.shutdownNow()
        }
    }

    private suspend fun clientOrConnect(): SSHClient = connectLock.withLock {
        client?.let { if (it.isConnected && it.isAuthenticated) return@withLock it }
        dropAll()

        val provider = try {
            keyLoader.load(host)
        } catch (e: Exception) {
            throw RemoteConnectionException(
                "${host.target} 的私钥不可用：${describe(e)}。检查 PEM/PPK 格式、口令，以及这台主机的私钥是否已导入。",
                e,
            )
        }

        val fresh = SSHClient()
        try {
            configure(fresh)
            val hopHost = host.jumpHost
            if (hopHost.isNullOrBlank()) {
                fresh.connect(host.host, host.port)
            } else {
                val hop = SSHClient()
                configure(hop)
                try {
                    hop.connect(hopHost, host.jumpPort)
                    hop.authPublickey(host.jumpUser ?: host.user, provider)
                    fresh.connectVia(hop.newDirectConnection(host.host, host.port))
                } catch (e: Exception) {
                    disconnectQuietly(hop)
                    throw e
                }
                jump = hop
            }
            fresh.authPublickey(host.user, provider)
            // 建连/认证阶段必须有有限超时；认证完成后的长命令由每次 exec 的 deadline 管。
            fresh.setTimeout(0)
            jump?.setTimeout(0)
        } catch (e: Exception) {
            disconnectQuietly(fresh, jump)
            jump = null
            SshDiagnostics.record(
                "连接/认证失败：${e.javaClass.simpleName}",
            )
            throw RemoteConnectionException(
                "连接或认证 ${host.target} 失败（连接超时 ${connectTimeoutMs}ms）：${describe(e)}。" +
                    "检查主机/端口/跳板是否可达、用户名和私钥权限；主机指纹变化也会在这里失败。",
                e,
            )
        }
        SshDiagnostics.record(
            "连接/认证成功（跳板=${!host.jumpHost.isNullOrBlank()}）",
        )
        client = fresh
        fresh
    }

    private fun configure(ssh: SSHClient) {
        ssh.setConnectTimeout(connectTimeoutMs)
        // sshj 的 socket timeout 同时覆盖握手/认证；若在这里设 0，TCP 连上但服务端不回包会永久挂起。
        ssh.setTimeout(connectTimeoutMs)
        ssh.addHostKeyVerifier(hostKeyVerifier)
    }

    private fun runCommand(
        session: Session,
        fullCommand: String,
        timeoutMs: Long,
        input: ByteArray?,
        onStdoutLine: ((String) -> Unit)?,
    ): RemoteResult {
        val cmd = channelOpen("启动远端命令", connectTimeoutMs.toLong()) { session.exec(fullCommand) }
        val stdoutF = io.submit(Callable { drainLines(cmd.inputStream, onStdoutLine) })
        val stderrF = io.submit(Callable { drain(cmd.errorStream) })
        // 流式传文件，SSH 自动按窗口切分；不把正文放进不受流控的 exec 请求包。
        val inputF = io.submit(Callable {
            cmd.outputStream.use { output ->
                if (input != null) {
                    var offset = 0
                    while (offset < input.size) {
                        val count = minOf(32 * 1024, input.size - offset)
                        output.write(input, offset, count)
                        offset += count
                    }
                    output.flush()
                }
            }
        })

        var joinFailure: Exception? = null
        val stillOpen = try {
            cmd.join(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            cmd.isOpen
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            abort()
            throw e
        } catch (e: Exception) {
            joinFailure = e
            true
        }
        if (stillOpen) abort()

        val stdout = stdoutF.awaitText()
        val stderr = stderrF.awaitText()
        if (joinFailure != null) {
            val detail = "等待远端命令时连接异常：${describe(joinFailure!!)}"
            return RemoteResult(
                exitCode = 255,
                stdout = stdout,
                stderr = listOf(stderr.trim(), detail).filter { it.isNotEmpty() }.joinToString("\n"),
            )
        }
        if (stillOpen) return RemoteResult.timeout(timeoutMs, stdout, stderr)

        if (input != null) {
            try {
                inputF.get(1, TimeUnit.SECONDS)
            } catch (e: Exception) {
                return RemoteResult(255, stdout, "上传正文中断：${describe(e.cause ?: e)}")
            }
        }

        val exit = cmd.exitStatus
            ?: return RemoteResult(
                exitCode = 255,
                stdout = stdout,
                stderr = listOf(stderr.trim(), "命令已结束但未收到远端退出码（连接可能中断）")
                    .filter { it.isNotEmpty() }
                    .joinToString("\n"),
            )
        return RemoteResult(exitCode = exit, stdout = stdout, stderr = stderr)
    }

    /**
     * `session.exec()` 本身也是网络操作；socket timeout 在认证后设成 0 时，网络黑洞可能卡在这里，
     * 永远走不到命令级 deadline。给 channel 建立单独加有限超时，超时就断开整条连接让下一步重连。
     */
    private fun <T> channelOpen(what: String, timeoutMs: Long, action: () -> T): T {
        val future = io.submit(Callable { action() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            abort()
            throw RemoteConnectionException("$what 超时（${timeoutMs}ms），已断开连接；检查网络/跳板后再重试", e)
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            if (cause is RemoteConnectionException) throw cause
            throw RemoteConnectionException("$what 失败：${describe(cause)}", cause)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            abort()
            throw RemoteConnectionException("$what 被中断", e)
        }
    }

    /** 超时后直接掐断连接（channel close 会等对方回包，网络挂了就会一起卡住）。 */
    private fun abort() {
        val current = client
        val hop = jump
        client = null
        jump = null
        if (current == null && hop == null) return
        disconnectQuietly(current, hop)
    }

    private fun dropClient(ssh: SSHClient) {
        if (client === ssh) {
            client = null
            disconnectQuietly(jump)
            jump = null
        }
        disconnectQuietly(ssh)
    }

    private fun dropAll() {
        val current = client
        val hop = jump
        client = null
        jump = null
        disconnectQuietly(current, hop)
    }

    /** sshj 的 disconnect 也是阻塞网络调用；用独立线程硬设 5 秒上限，避免清理阶段再次卡死。 */
    private fun disconnectQuietly(vararg clients: SSHClient?) {
        val targets = clients.filterNotNull()
        if (targets.isEmpty()) return
        runCatching {
            io.submit(
                Callable {
                    targets.forEach { ssh -> runCatching { ssh.disconnect() } }
                },
            ).get(5, TimeUnit.SECONDS)
        }
    }

    private fun closeSessionQuietly(session: Session) {
        runCatching {
            io.submit(Callable { runCatching { session.close() } }).get(5, TimeUnit.SECONDS)
        }
    }

    /** 读到 EOF 为止；超过上限仍会读空以避免远端管道阻塞，只在内存里保留有界头尾。 */
    private fun drain(input: InputStream): String {
        val captured = BoundedCommandOutput(maxOutputBytes)
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                captured.append(buf, 0, n)
            }
        } catch (_: IOException) {
            // 通道被关掉（通常是超时/断线），已读到的部分照常返回
        }
        return captured.render()
    }

    /**
     * 与 [drain] 一样保留有界头尾，同时把完整行交给回调。只用于 stdout；stderr 仍整体收集。
     * 回调里的异常不能影响读取，否则一个 UI 更新失败会把整条 SSH 流打断。
     */
    private fun drainLines(input: InputStream, onLine: ((String) -> Unit)?): String {
        val captured = BoundedCommandOutput(maxOutputBytes)
        val buf = ByteArray(16 * 1024)
        val line = ByteArrayOutputStream()
        var discarding = false
        fun emit() {
            if (onLine == null || line.size() == 0) {
                line.reset()
                discarding = false
                return
            }
            val text = String(line.toByteArray(), Charsets.UTF_8).trimEnd('\r')
            line.reset()
            discarding = false
            if (text.isNotBlank()) runCatching { onLine(text) }
        }
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                captured.append(buf, 0, n)
                if (onLine == null) continue
                for (i in 0 until n) {
                    val byte = buf[i]
                    if (byte == '\n'.code.toByte()) {
                        emit()
                    } else if (!discarding) {
                        if (line.size() < MAX_STREAM_LINE_BYTES) {
                            line.write(byte.toInt())
                        } else {
                            // 极端长的单行不占无限内存；该行解析会失败，但后续行仍能继续。
                            discarding = true
                        }
                    }
                }
            }
            if (onLine != null) emit()
        } catch (_: IOException) {
            // 同 drain：通道被关闭时返回已读部分。
        }
        return captured.render()
    }

    private fun Future<String>.awaitText(): String = try {
        get(10, TimeUnit.SECONDS)
    } catch (_: Exception) {
        ""
    }

    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private companion object {
        const val MAX_STREAM_LINE_BYTES = 4 * 1024 * 1024
    }
}

/**
 * 有界头尾缓冲：总内存不超过 [maxBytes]，但超过上限时同时保留真正的开头和结尾。
 * 编译器报错、traceback 和最终失败原因通常在结尾，不能只保存前几 MB。
 */
internal class BoundedCommandOutput(private val maxBytes: Int) {
    init {
        require(maxBytes >= 2) { "maxBytes 必须至少为 2" }
    }

    private val headLimit = maxBytes / 3
    private val tailLimit = maxBytes - headLimit
    private val head = ByteArrayOutputStream(headLimit)
    private val tail = ByteArray(tailLimit)
    private var tailWrite = 0
    private var tailSize = 0
    private var total = 0L

    @Synchronized
    fun append(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        if (length == 0) return

        val headRoom = headLimit - head.size()
        if (headRoom > 0) {
            head.write(bytes, offset, minOf(headRoom, length))
        }
        appendTail(bytes, offset, length)
        total += length
    }

    @Synchronized
    fun render(): String {
        if (total <= maxBytes.toLong()) {
            if (total <= tailLimit.toLong()) return decode(tailBytes())
            val headBytes = head.toByteArray()
            val tailBytes = tailBytes()
            val overlap = (headLimit + tailLimit - total).toInt()
            val keptHead = headBytes.copyOfRange(0, (headBytes.size - overlap).coerceAtLeast(0))
            return decode(keptHead + tailBytes)
        }

        val omitted = total - maxBytes
        return decode(head.toByteArray()) +
            "\n…（输出超过 $maxBytes 字节，原始 $total 字节，省略 $omitted 字节；已保留开头和结尾）\n" +
            decode(tailBytes())
    }

    private fun appendTail(bytes: ByteArray, offset: Int, length: Int) {
        if (length >= tailLimit) {
            bytes.copyInto(tail, destinationOffset = 0, startIndex = offset + length - tailLimit, endIndex = offset + length)
            tailWrite = 0
            tailSize = tailLimit
            return
        }

        var remaining = length
        var source = offset
        while (remaining > 0) {
            val n = minOf(remaining, tailLimit - tailWrite)
            bytes.copyInto(tail, tailWrite, source, source + n)
            tailWrite = (tailWrite + n) % tailLimit
            tailSize = minOf(tailLimit, tailSize + n)
            source += n
            remaining -= n
        }
    }

    private fun tailBytes(): ByteArray {
        if (tailSize < tailLimit) return tail.copyOf(tailSize)
        return ByteArray(tailLimit).also { ordered ->
            val first = tailLimit - tailWrite
            tail.copyInto(ordered, 0, tailWrite, tailLimit)
            tail.copyInto(ordered, first, 0, tailWrite)
        }
    }

    private fun decode(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)
}
