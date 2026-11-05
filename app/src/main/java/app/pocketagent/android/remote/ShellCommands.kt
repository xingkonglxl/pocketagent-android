package app.pocketagent.android.remote

import java.util.Base64

/**
 * 远端命令拼装。全部是纯函数，方便单测；不碰网络、不碰文件系统。
 *
 * 目标两端：Linux（remote-host，GNU coreutils）和 macOS（BSD）。用到的
 * `cat` / `head -c` / `ls -1A` / `base64 -d` / `dirname` 两边都支持。
 */

/** POSIX 单引号转义：`'` → `'\''`，内容里的引号、换行、中文、`$` 全部原样保留。 */
fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

private val TILDE_USER = Regex("[A-Za-z0-9._-]+")

/**
 * 把路径变成 shell 里安全的表达式，并处理 `~` / `~user` 展开。
 *
 * 单引号里的 `~` 不会被 shell 展开，所以 `~/x` 会拼成 `"$HOME"/'x'`。
 */
fun shellPath(path: String): String {
    if (path.isEmpty()) return "''"
    if (path == "~") return "\"\$HOME\""
    if (path.startsWith("~/")) return "\"\$HOME\"/" + shellQuote(path.substring(2))
    if (path[0] == '~') {
        val slash = path.indexOf('/')
        val user = if (slash < 0) path.substring(1) else path.substring(1, slash)
        if (TILDE_USER.matches(user)) {
            return if (slash < 0) "~$user" else "~$user/" + shellQuote(path.substring(slash + 1))
        }
    }
    return shellQuote(path)
}

/**
 * 把命令放进工作目录执行：`(cd <workDir> && { ... })`。
 *
 * 用子 shell 是为了 exit code 还是命令本身的、且 cd 失败时不会继续跑；
 * `{ }` 让多行脚本、结尾注释也安全（`#` 不会把右括号吃掉）。
 */
fun wrapWithWorkDir(command: String, workDir: String?): String {
    if (workDir.isNullOrBlank()) return command
    return "(cd ${shellPath(workDir)} && {\n$command\n})"
}

/**
 * 读文件片段：多读 1 字节，让上层能判断是否被截断。
 * 从中间续读时先检查是否仍是普通可读文件，避免 pipeline 吞掉 tail 的错误。
 */
fun readFileCommand(
    path: String,
    maxBytes: Int = DEFAULT_MAX_READ_BYTES,
    offsetBytes: Long = 0,
): String {
    require(maxBytes > 0) { "maxBytes 必须为正数：$maxBytes" }
    require(offsetBytes >= 0 && offsetBytes < Long.MAX_VALUE) {
        "offsetBytes 必须在 0..${Long.MAX_VALUE - 1}：$offsetBytes"
    }
    val target = shellPath(path)
    if (offsetBytes == 0L) return "head -c ${maxBytes + 1} -- $target"
    return "test -f $target && test -r $target || { " +
        "printf '不是可读的普通文件: %s\\n' $target >&2; exit 1; }; " +
        "tail -c +${offsetBytes + 1} -- $target | head -c ${maxBytes + 1}"
}

/**
 * 写文件：内容先 base64，再 `printf %s` 进 `base64 -d`，绕开引号、换行、中文、
 * `$`、反引号所有转义问题。默认先 `mkdir -p` 父目录。
 */
fun writeFileCommand(path: String, content: String, makeParents: Boolean = true): String {
    val payload = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
    val target = shellPath(path)
    val write = "printf %s ${shellQuote(payload)} | base64 -d > $target"
    if (!makeParents) return write
    return "mkdir -p -- \"\$(dirname -- $target)\" && $write"
}

/**
 * 大文件分块写。
 *
 * 以前整段内容 base64 后塞进一条命令：会话有几 MB 时单条命令超过 shell 的
 * ARG_MAX；更早还会碰到 OpenSSH 约 256KiB 的单个请求包上限，直接断开连接。
 * 这里按字节切片，每片单独 base64，第一条 `>`、之后 `>>`，逐条执行。
 */
fun writeFileCommands(
    path: String,
    content: String,
    chunkBytes: Int = 64 * 1024,
    makeParents: Boolean = true,
): List<String> {
    require(chunkBytes in 1..64 * 1024) { "上传分块必须在 1..65536 字节之间" }
    val bytes = content.toByteArray(Charsets.UTF_8)
    val target = shellPath(path)
    val mkdir = "mkdir -p -- \"\$(dirname -- $target)\""
    if (bytes.size <= chunkBytes) {
        val payload = Base64.getEncoder().encodeToString(bytes)
        val write = "printf %s ${shellQuote(payload)} | base64 -d > $target"
        return listOf(if (makeParents) "$mkdir && $write" else write)
    }
    val commands = mutableListOf<String>()
    if (makeParents) commands += mkdir
    var offset = 0
    var first = true
    while (offset < bytes.size) {
        val end = minOf(offset + chunkBytes, bytes.size)
        val payload = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))
        val op = if (first) ">" else ">>"
        commands += "printf %s ${shellQuote(payload)} | base64 -d $op $target"
        first = false
        offset = end
    }
    return commands
}

/** 列目录：一行一个名字，含隐藏文件，不含 `.` / `..`。 */
fun listCommand(path: String): String = "ls -1A -- ${shellPath(path)}"

/** 把 `ls -1A` 的输出切成名字列表。 */
fun parseListing(stdout: String): List<String> =
    stdout.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
