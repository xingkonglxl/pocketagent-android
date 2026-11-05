package app.pocketagent.android.agent

/**
 * 对话框里的斜杠命令。和终端里的 codex 一样：`/` 开头走命令层，不发给模型。
 * 这里只做纯解析，真正的动作在 ChatViewModel 里。
 */
object SlashCommands {

    data class Spec(val name: String, val usage: String, val description: String)

    val specs = listOf(
        Spec("/model", "/model [名字]", "列出模型和思考档位，点选切换"),
        Spec("/reasoning", "/reasoning [none|low|medium|high|max]", "调当前模型的思考强度"),
        Spec("/thinking", "/thinking [on|off]", "远端工作区中显示/隐藏远端 harness 的思考过程（默认关）"),
        Spec("/goal", "/goal [目标|done]", "设定/查看/完成本轮目标"),
        Spec("/fast", "/fast", "快速服务档：service_tier=priority，工具仍开启；再输一次关闭"),
        Spec("/tools", "/tools on|off", "开关当前来源的工具（harness）"),
        Spec("/workspace", "/workspace", "选工作区：手机或某台远端机器（文件直接存那边）"),
        Spec("/approvals", "/approvals [ask|auto]", "每次问 / 自动批准工具"),
        Spec("/permissions", "/permissions", "按类别选择哪些工具不用再逐个确认"),
        Spec("/mcp", "/mcp [refresh]", "查看/刷新 MCP 服务器和工具（增删在设置里）"),
        Spec("/status", "/status", "看当前模型、档位、工具和上下文状态"),
        Spec("/context", "/context", "查看上下文预算、已用 token 和历史裁剪情况"),
        Spec("/compact", "/compact", "把前面的对话压成摘要，只留最近几轮"),
        Spec("/export", "/export", "把当前对话导出成 markdown 存到工作区"),
        Spec("/mirror", "/mirror [on|off]", "每轮结束后把会话镜像到远端工作区 sessions/（默认关）"),
        Spec("/init", "/init", "在工作区生成 AGENTS.md（项目约定）"),
        Spec("/remember", "/remember 文字", "记一条长期笔记到工作区 NOTES.md"),
        Spec("/skills", "/skills", "列出当前可用的工具"),
        Spec("/ps", "/ps", "看当前是否在生成"),
        Spec("/stop", "/stop", "停止正在生成的回答"),
        Spec("/undo", "/undo", "撤回上一轮问答"),
        Spec("/new", "/new", "清空当前会话，重新开始"),
        Spec("/help", "/help", "显示所有命令"),
        Spec("/test", "/test", "逐个来源发一次最小请求，检查能不能用"),
    )

    fun isCommand(text: String): Boolean = text.trim().startsWith("/")

    /** 输入框里打 `/` 时给出候选；已经带空格（在填参数）就不再提示。 */
    fun match(prefix: String): List<Spec> {
        val p = prefix.trim()
        if (!p.startsWith("/") || p.contains(' ')) return emptyList()
        return specs.filter { it.name.startsWith(p, ignoreCase = true) }
    }

    fun helpText(): String = buildString {
        append("可用命令：\n")
        specs.forEach { append("${it.usage}  —  ${it.description}\n") }
        append("\n例：/model 2 切到第 2 个来源；/fast 使用服务端快速档。")
    }

    data class Parsed(val name: String, val arg: String)

    /** `/model 2` → Parsed("model", "2") */
    fun parse(raw: String): Parsed {
        val body = raw.trim().removePrefix("/")
        val name = body.substringBefore(' ').lowercase()
        val arg = body.substringAfter(' ', "").trim()
        return Parsed(name, arg)
    }
}
