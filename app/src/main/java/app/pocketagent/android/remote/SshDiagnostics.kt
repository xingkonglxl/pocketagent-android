package app.pocketagent.android.remote

/**
 * SSH 失败时留下的阶段记录：连跳板 → 认证 → 开会话 → 跑命令。
 *
 * 手机上的 logcat 经常被厂商裁掉，用户只能看到一句「同步失败」。
 * 这里保留最近若干条（不含命令正文，避免把会话内容写进诊断），
 * 由同步页的「上次结果」面板展示，用户可以直接复制发给别人排查。
 */
object SshDiagnostics {

    private const val MAX_LINES = 60
    private val lines = ArrayDeque<String>()

    fun record(event: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        synchronized(lines) {
            lines.addLast("$ts $event")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun tail(count: Int = 12): String = synchronized(lines) {
        lines.toList().takeLast(count).joinToString("\n")
    }

    fun clear() = synchronized(lines) { lines.clear() }

    /** 把异常链拼成一行，sshj 真正的原因常常在 cause 里。 */
    fun describeChain(e: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 5) {
            val msg = cur.message?.takeIf { it.isNotBlank() } ?: cur.javaClass.simpleName
            if (parts.none { it == msg }) parts += msg
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" ← ")
    }
}
