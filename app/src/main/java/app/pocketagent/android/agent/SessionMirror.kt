package app.pocketagent.android.agent

import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Role

/**
 * 会话镜像的解析与合并。
 *
 * 手机上每轮结束会把会话写成 sessions/<id>.md；在电脑上接着改／接着聊之后，
 * 文件里会多出新的 `## user` / `## assistant` 段落。这里把文件解析回消息，
 * 只导入手机里还没有的那些，避免重复，也避免把整个文件重新灌一遍。
 */
object SessionMirror {

    data class Parsed(val role: Role, val text: String)

    /** 解析 ExportMarkdown 产出的文件；认不出角色的小节直接跳过。 */
    fun parse(markdown: String): List<Parsed> {
        val out = mutableListOf<Parsed>()
        var currentRole: Role? = null
        val buffer = StringBuilder()

        fun flush() {
            val role = currentRole ?: return
            val body = cleanBlock(buffer.toString())
            if (body.isNotBlank()) out += Parsed(role, body)
            buffer.setLength(0)
        }

        markdown.lineSequence().forEach { line ->
            val role = roleOf(line)
            if (role != null) {
                flush()
                currentRole = role
            } else if (currentRole != null) {
                buffer.appendLine(line)
            }
        }
        flush()
        return out
    }

    private fun roleOf(line: String): Role? = when (line.trim()) {
        "## user" -> Role.USER
        "## assistant" -> Role.ASSISTANT
        else -> null
    }

    /** 去掉导出时附带的元信息行（模型、tokens、已停止、图片），只留正文。 */
    private fun cleanBlock(raw: String): String = raw
        .lineSequence()
        .filterNot { it.startsWith("- 模型：") || it.startsWith("- tokens：") ||
            it == "- 已停止" || it.startsWith("- 图片：") }
        .joinToString("\n")
        .trim()

    /**
     * 把远端文件里的消息并进手机会话：只保留本地还没有的（按正文去重）。
     * 返回合并后的完整列表和新增条数。
     */
    fun merge(local: List<ChatMessage>, remote: List<Parsed>): Pair<List<ChatMessage>, Int> {
        val seen = local.map { normalize(it.text) }.toMutableSet()
        val added = mutableListOf<ChatMessage>()
        remote.forEach { item ->
            val key = normalize(item.text)
            if (key.isBlank() || !seen.add(key)) return@forEach
            added += ChatMessage(role = item.role, text = item.text, model = "remote")
        }
        return (local + added) to added.size
    }

    /**
     * 远端是否含有手机里没有的消息。
     *
     * 每轮结束写镜像时，手机是整段覆盖写。如果电脑那边也往同一个文件里追加过内容，
     * 直接覆盖就把电脑上的记录冲掉了。写之前先问一句：远端有没有手机还没有的东西。
     */
    fun hasRemoteOnly(local: List<ChatMessage>, remote: List<Parsed>): Boolean {
        val seen = local.map { normalize(it.text) }.toMutableSet()
        return remote.any { normalize(it.text).let { key -> key.isNotBlank() && key !in seen } }
    }

    private fun normalize(text: String): String =
        text.replace(Regex("\\s+"), " ").trim().lowercase()
}
