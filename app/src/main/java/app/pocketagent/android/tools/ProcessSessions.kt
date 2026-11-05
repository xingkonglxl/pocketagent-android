package app.pocketagent.android.tools

import java.io.File
import java.util.UUID

data class ProcessSnapshot(
    val sessionId: String,
    val output: String,
    val running: Boolean,
    val exitCode: Int?,
    val truncated: Boolean,
)

/**
 * 手机本地后台进程会话。`shell_exec` 是同步等待；这里让模型可以启动长任务、
 * 轮询增量输出、向 stdin 写数据或终止，和桌面 Codex 的 exec_command/write_stdin 对齐。
 */
class ProcessSessionManager(
    private val root: File,
    private val maxSessions: Int = 6,
    private val maxBufferChars: Int = 200_000,
) {
    private data class Session(
        val id: String,
        val process: Process,
        val buffer: StringBuilder = StringBuilder(),
        val lock: Object = Object(),
        var readOffset: Int = 0,
        var exitCode: Int? = null,
        var overflowed: Boolean = false,
        var readerDone: Boolean = false,
        var lastAccess: Long = System.currentTimeMillis(),
    )

    private val sessions = LinkedHashMap<String, Session>()
    private val shell = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"

    @Synchronized
    fun start(
        command: String,
        yieldMs: Long = 1_000,
        maxChars: Int = 12_000,
    ): ProcessSnapshot {
        require(command.isNotBlank()) { "command 不能为空" }
        cleanupLocked()
        require(sessions.size < maxSessions) {
            "并发会话已达上限（$maxSessions），请先 kill 旧会话"
        }
        val process = ProcessBuilder(shell, "-c", command)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val session = Session("proc_" + UUID.randomUUID().toString().take(8), process)
        sessions[session.id] = session
        startReader(session)
        startWaiter(session)
        return await(session, yieldMs, maxChars)
    }

    @Synchronized
    fun write(
        sessionId: String,
        chars: String,
        yieldMs: Long = 1_000,
        maxChars: Int = 12_000,
    ): ProcessSnapshot {
        cleanupLocked()
        val session = sessions[sessionId]
            ?: throw IllegalArgumentException("找不到会话 $sessionId；可能已退出并被清理")
        if (chars.isNotEmpty()) {
            if (!session.process.isAlive) {
                throw IllegalStateException("会话已退出，不能再写 stdin")
            }
            if (chars == "\u0003" || chars == "\\u0003") {
                session.process.destroy()
            } else {
                session.process.outputStream.write(chars.toByteArray(Charsets.UTF_8))
                session.process.outputStream.flush()
            }
        }
        return await(session, yieldMs, maxChars)
    }

    @Synchronized
    fun kill(sessionId: String): ProcessSnapshot {
        val session = sessions[sessionId]
            ?: throw IllegalArgumentException("找不到会话 $sessionId")
        session.process.destroyForcibly()
        return await(session, 500, 4_000)
    }

    @Synchronized
    fun closeAll() {
        sessions.values.forEach { runCatching { it.process.destroyForcibly() } }
        sessions.clear()
    }

    private fun startReader(session: Session) {
        Thread {
            runCatching {
                session.process.inputStream.bufferedReader().use { reader ->
                    val chars = CharArray(4_096)
                    while (true) {
                        val count = reader.read(chars)
                        if (count < 0) break
                        synchronized(session.lock) {
                            if (session.buffer.length + count <= maxBufferChars) {
                                session.buffer.append(chars, 0, count)
                            } else {
                                session.overflowed = true
                            }
                            session.lock.notifyAll()
                        }
                    }
                }
            }
            synchronized(session.lock) {
                session.readerDone = true
                session.lock.notifyAll()
            }
        }.apply {
            isDaemon = true
            name = "pocketagent-proc-${session.id}"
            start()
        }
    }

    private fun startWaiter(session: Session) {
        Thread {
            val code = runCatching { session.process.waitFor() }.getOrElse { -1 }
            synchronized(session.lock) {
                session.exitCode = code
                session.lock.notifyAll()
            }
        }.apply {
            isDaemon = true
            name = "pocketagent-proc-wait-${session.id}"
            start()
        }
    }

    private fun await(session: Session, yieldMs: Long, maxChars: Int): ProcessSnapshot {
        val deadline = System.currentTimeMillis() + yieldMs.coerceIn(0, 30_000)
        synchronized(session.lock) {
            while (true) {
                // 进程退出和 reader 读到 EOF 是两个线程；先等 reader 把最后一段输出收完，
                // 否则短命令会返回空 output，调用方还得再轮询一次。
                if (session.exitCode != null && !session.readerDone) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    session.lock.wait(remaining.coerceAtMost(50))
                    continue
                }
                if (session.buffer.length > session.readOffset ||
                    session.exitCode != null ||
                    session.overflowed
                ) {
                    break
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                session.lock.wait(remaining.coerceAtMost(250))
            }
            val available = session.buffer.length - session.readOffset
            val take = available.coerceIn(0, maxChars.coerceIn(1, 30_000))
            val text = if (take > 0) {
                session.buffer.substring(session.readOffset, session.readOffset + take)
            } else {
                ""
            }
            session.readOffset += take
            session.lastAccess = System.currentTimeMillis()
            val truncated = available > take || session.overflowed
            return ProcessSnapshot(
                sessionId = session.id,
                output = text,
                running = session.exitCode == null,
                exitCode = session.exitCode,
                truncated = truncated,
            )
        }
    }

    private fun cleanupLocked() {
        val cutoff = System.currentTimeMillis() - 10 * 60_000
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next().value
            if (session.exitCode != null && session.lastAccess < cutoff) {
                iterator.remove()
            }
        }
    }
}
