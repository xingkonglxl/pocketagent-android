package app.pocketagent.android.agent

import kotlinx.serialization.json.*

/**
 * 工具权限分组。桌面 Codex 也是按“只读 / 写入 / 执行”分级审批，
 * 而不是每个具体工具单独配置；这里沿用同样的粒度，允许用户一次性放行某一类。
 */
object ToolPermissions {

    const val WEB = "web"
    const val READ = "read"
    const val WRITE = "write"
    const val DELETE = "delete"
    const val SHELL = "shell"
    const val REMOTE = "remote"
    const val MCP = "mcp"

    /** 展示顺序：越靠前越常用、越安全。 */
    val all = listOf(WEB, READ, WRITE, DELETE, SHELL, REMOTE, MCP)

    /** 默认放行：搜索/抓网页、读文件与列目录——这些不会改变任何状态。 */
    val defaults: Set<String> = setOf(WEB, READ)

    fun categoryOf(tool: String): String? = when {
        tool.startsWith("mcp__") -> MCP
        else -> staticCategoryOf(tool)
    }

    private fun staticCategoryOf(tool: String): String? = when (tool) {
        "web_search", "http_get" -> WEB
        "fs_read", "fs_list", "fs_glob", "fs_search", "pdf_read", "view_image",
        "ssh_read", "ssh_ls", "update_plan" -> READ
        "fs_write", "fs_edit", "apply_patch" -> WRITE
        "fs_delete" -> DELETE
        "shell_exec", "exec_command", "write_stdin" -> SHELL
        "ssh_exec", "ssh_write", "remote_codex" -> REMOTE
        else -> null
    }

    fun label(category: String): String = when (category) {
        WEB -> "搜索与抓网页"
        READ -> "读文件与列目录"
        WRITE -> "写文件"
        DELETE -> "删除文件"
        SHELL -> "本机命令"
        REMOTE -> "远端命令与写入"
        MCP -> "MCP 工具"
        else -> category
    }

    fun describe(category: String): String = when (category) {
        WEB -> "web_search / http_get，不改变任何数据"
        READ -> "fs_read / fs_list / fs_glob / pdf_read / view_image / ssh_read"
        WRITE -> "fs_write / fs_edit / apply_patch，会覆盖工作区文件"
        DELETE -> "fs_delete，删掉后只能从回收站找回"
        SHELL -> "shell_exec / exec_command / write_stdin，在手机上执行命令或操作后台进程"
        REMOTE -> "ssh_exec / ssh_write / remote_codex，执行、写入或调用远端 Codex harness"
        MCP -> "用户配置的 MCP 服务器提供的工具，可在服务器端做任意操作，默认逐次确认"
        else -> ""
    }

    /** 启用后仍然每次都要确认的类别：默认关闭，且不加“记住本次会话”的后门。 */
    val risky = setOf(DELETE, SHELL, REMOTE, MCP)

    /**
     * 即使放行了整个类别或开了自动批准，也必须逐次确认的工具。
     * `ssh_write` 以前漏在这里：`/approvals auto` 后远端文件能被静默覆盖。
     */
    val alwaysConfirm = setOf(
        "fs_delete", "shell_exec", "exec_command", "ssh_exec", "ssh_write", "remote_codex", "apply_patch",
    )

    /**
     * 明显是凭据的路径。读文件默认放行，但如果模型去读私钥/密钥文件，
     * 单靠“读是只读操作”就不够了：网页提示注入可以让它读走再发出去。
     * 读写这类路径一律绕回审批，不提供“本会话不再问”。
     */
    private val sensitiveMarkers = listOf(
        ".ssh", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
        ".aws", ".azure", ".kube", ".docker/config.json",
        ".config/gcloud", ".netrc", ".pgpass", ".gnupg",
        ".env", "secrets/", "credentials",
        ".codex/auth.json", ".codex/config.toml", ".config/pocketagent-codex", ".npmrc", ".pypirc",
        ".pem", ".p12", ".pfx", ".keystore", ".jks",
    )

    /** 读写类工具的参数里是否点名了凭据文件或目录。 */
    fun needsExtraApproval(tool: String, argumentsJson: String): Boolean {
        if (categoryOf(tool) !in setOf(READ, WRITE)) return false
        // Decode JSON escapes before matching, so \u002e cannot hide a credential path.
        val decoded = runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull()
            ?: return true // malformed arguments must not bypass approval
        if (decoded !is JsonObject) return true
        fun strings(value: JsonElement): String = when (value) {
            is JsonObject -> value.values.joinToString(" ") { strings(it) }
            is JsonArray -> value.joinToString(" ") { strings(it) }
            is JsonPrimitive -> value.content
        }
        val lower = strings(decoded).lowercase()
        return sensitiveMarkers.any { lower.contains(it) }
    }

    /** 统一判定：危险工具或敏感路径，不能被“本次会话都允许”覆盖。 */
    fun requiresPerCallApproval(tool: String, argumentsJson: String): Boolean =
        tool in alwaysConfirm || needsExtraApproval(tool, argumentsJson)

    /**
     * 只有不改变状态且不涉及敏感路径的调用，才允许用户选择“本次会话都允许”。
     * 写文件虽然不在 [alwaysConfirm] 里，但会覆盖用户数据，仍应逐次确认。
     */
    fun isSessionAllowable(tool: String, argumentsJson: String): Boolean =
        categoryOf(tool) in setOf(WEB, READ) && !requiresPerCallApproval(tool, argumentsJson)
}
