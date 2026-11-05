package app.pocketagent.android.agent

import app.pocketagent.android.net.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 手机 harness 的子 agent：把一项互不依赖的调研交给只读子任务，主 agent 继续干别的。
 *
 * 和桌面 Codex 的 spawn_agent 相比刻意收窄：
 * - 子 agent 只有只读工具，不能写文件、删东西、执行命令，也不再有 delegate_task（不递归）；
 * - 步数、并发、超时、返回长度都有硬上限，子 agent 花的是同一份 API 额度；
 * - 子 agent 的中间过程不进主上下文，只回一段结论，主 agent 的窗口不会被它撑爆。
 */
object Subagents {
    const val TOOL_NAME = "delegate_task"
    const val MAX_STEPS = 10
    const val MAX_PARALLEL = 2
    const val MAX_CALLS_PER_TURN = 4
    const val TIMEOUT_MS = 240_000L
    const val MAX_RESULT_CHARS = 12_000

    /** 子 agent 能拿到的工具：全部只读，不改变任何状态。 */
    val readOnlyTools = setOf(
        "fs_list",
        "fs_read",
        "fs_glob",
        "fs_search",
        "pdf_read",
        "view_image",
        "ssh_read",
        "ssh_ls",
        "http_get",
        "web_search",
        "skill_read",
    )

    val spec = ToolSpec(
        name = TOOL_NAME,
        description = "把一个独立的只读调研交给子 agent（可以并行），它自己读文件、搜索、抓网页、看远端日志，" +
            "只回一段结论。适合同时查多个问题、扫多个目录。子 agent 不能改文件或执行命令，" +
            "需要动手改动时由你自己做；结论要自己核对后再用。",
        parametersJson = """
            {"type":"object","properties":{
              "task":{"type":"string","description":"交给子 agent 的具体任务，写清楚要回答什么"},
              "context":{"type":"string","description":"可选背景：已知路径、命令、约束、上一轮结论"}
            },"required":["task"]}
        """,
    )

    val systemPrompt = """
        You are a read-only subagent launched by PocketAgent. Work on the task you were given,
        use the read-only tools available, and then return a concise report.

        Rules:
        - Do not ask the user questions; nobody will answer them.
        - Do not attempt edits, deletes or shell commands; you do not have them.
        - Verify with real files or pages instead of guessing. Give exact paths, line numbers,
          commands and values that matter.
        - If something cannot be verified, say so explicitly and say what you tried.
        - Finish with: findings (bullets), evidence (path:line or URL), and open questions.
        - Keep the whole report under 400 words unless the task needs a table.
    """.trimIndent()

    data class Call(val task: String, val context: String)

    /** 解析 `delegate_task` 的参数；task 为空返回 null。 */
    fun parse(argumentsJson: String): Call? {
        val obj = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull() ?: return null
        val task = obj["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (task.isBlank()) return null
        val context = obj["context"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return Call(task = task, context = context)
    }

    fun prompt(call: Call): String = buildString {
        append("Task:\n").append(call.task)
        if (call.context.isNotBlank()) {
            append("\n\nContext from the main agent:\n").append(call.context)
        }
    }

    fun clip(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) {
            text
        } else {
            text.take(MAX_RESULT_CHARS) + "\n…（子 agent 结论过长，已截断）"
        }
}
