package app.pocketagent.android.tools

import app.pocketagent.android.net.ToolSpec
import kotlinx.coroutines.CancellationException
import java.io.File

sealed interface ToolOutcome {
    /** [images] 是 data URL（`data:image/png;base64,...`），会作为图片一起回灌给模型。 */
    data class Ok(val text: String, val images: List<String> = emptyList()) : ToolOutcome
    data class Error(val message: String) : ToolOutcome
}

interface Tool {
    val spec: ToolSpec
    suspend fun execute(argumentsJson: String): ToolOutcome
}

/** `runCatching` 会吞掉协程取消；工具调用统一用它，保证“停止”真的能停下来。 */
internal inline fun <T> runToolCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/**
 * 静态工具 + 运行时可变的扩展工具（目前是用户配置的 MCP 服务器）。
 *
 * 扩展工具在 [specs] / [find] 时现取，所以 MCP 服务器重连、禁用或新增工具后
 * 下一轮对话立即生效，不需要重建 AgentRunner。同名时静态工具优先。
 */
class ToolRegistry(
    val tools: List<Tool>,
    private val dynamicTools: () -> List<Tool> = { emptyList() },
) {
    private val byName = tools.associateBy { it.spec.name }

    init {
        require(byName.size == tools.size) {
            val duplicates = tools.groupBy { it.spec.name }.filterValues { it.size > 1 }.keys
            "工具名不能重复：${duplicates.joinToString()}"
        }
    }

    fun find(name: String): Tool? =
        byName[name] ?: dynamicTools().firstOrNull { it.spec.name == name }

    fun specs(): List<ToolSpec> {
        val extra = dynamicTools()
            .filterNot { it.spec.name in byName }
            .distinctBy { it.spec.name }
        return (tools + extra).map { it.spec }
    }
}

/** 所有路径都限制在 workspace 根目录内，挡住 ../ 之类的越界。 */
fun resolveInside(root: File, relative: String): File {
    val rootCanonical = root.canonicalFile
    val target = File(rootCanonical, relative).canonicalFile
    val rootPath = rootCanonical.path
    require(target.path == rootPath || target.path.startsWith(rootPath + File.separator)) {
        "路径越界：$relative"
    }
    return target
}

object ToolSchemas {
    const val FS_LIST = """
        {"type":"object","properties":{
          "path":{"type":"string","default":".","description":"相对工作区根目录的目录路径，默认 ."}
        }}
    """

    const val FS_READ = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"要读取的相对路径"}
        },"required":["path"]}
    """

    const val FS_READ_PAGED = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"要读取的相对路径，例如 logs/build.txt"},
          "offset":{"type":"integer","minimum":0,"description":"起始字节偏移（不是行号）。首次读取省略或填 0；需要续读时填上一条结果里的 next_offset"},
          "limit":{"type":"integer","minimum":1,"maximum":1048576,"default":262144,"description":"本次最多读取的字节数，默认 262144（256KB），最大 1048576（1MB）"}
        },"required":["path"]}
    """

    const val FS_WRITE = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"要写入的相对路径"},
          "content":{"type":"string","description":"完整文件内容，会覆盖原文件；修改已有文件前先 fs_read，再提交完整新内容"}
        },"required":["path","content"]}
    """

    const val FS_DELETE = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"要删除的相对文件路径；只删单个文件，不删目录"}
        },"required":["path"]}
    """

    const val FS_GLOB = """
        {"type":"object","properties":{
          "pattern":{"type":"string","description":"glob 模式，例如 **/*.kt、src/**/*.md、*.log"},
          "max":{"type":"integer","minimum":1,"maximum":500,"default":200,"description":"最多返回多少条"}
        },"required":["pattern"]}
    """

    const val FS_SEARCH = """
        {"type":"object","properties":{
          "query":{"type":"string","description":"要搜索的文本；regex=true 时按正则解释"},
          "regex":{"type":"boolean","default":false,"description":"是否按正则匹配"},
          "max":{"type":"integer","minimum":1,"maximum":300,"default":100,"description":"最多返回多少条命中"}
        },"required":["query"]}
    """

    const val FS_EDIT = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"要修改的相对路径"},
          "find":{"type":"string","description":"要被替换的原文，必须与文件里的内容逐字一致；默认要求只匹配到一处"},
          "replace":{"type":"string","default":"","description":"替换成的新内容，空字符串表示删除"},
          "all":{"type":"boolean","default":false,"description":"为 true 时替换所有匹配"}
        },"required":["path","find"]}
    """

    const val APPLY_PATCH = """
        {"type":"object","properties":{
          "patch":{"type":"string","minLength":1,"description":"Codex apply_patch 格式：*** Begin Patch ... *** End Patch；支持 Add File、Update File 的 @@ hunk 和 *** End of File、Delete File，可一次修改多个文件；不支持 *** Move to"}
        },"required":["patch"]}
    """

    const val SHELL_EXEC = """
        {"type":"object","properties":{
          "command":{"type":"string","description":"要执行的 shell 命令；cwd 是工作区根目录"},
          "timeout":{"type":"integer","minimum":1,"maximum":300,"default":30,"description":"超时秒数"}
        },"required":["command"]}
    """

    const val EXEC_COMMAND = """
        {"type":"object","properties":{
          "command":{"type":"string","description":"要执行的手机本地 shell 命令；cwd 是工作区根目录；可以包含管道、重定向和后台任务"},
          "yield_time_ms":{"type":"integer","minimum":0,"maximum":30000,"default":1000,"description":"启动后最多等待多久再返回当前输出；0 表示立即返回"},
          "max_output_chars":{"type":"integer","minimum":100,"maximum":30000,"default":12000,"description":"本次最多返回的字符数"}
        },"required":["command"]}
    """

    const val WRITE_STDIN = """
        {"type":"object","properties":{
          "session_id":{"type":"string","description":"exec_command 返回的 session_id"},
          "chars":{"type":"string","default":"","description":"写入 stdin 的文本；空字符串表示只轮询输出；Ctrl-C 可写 \\u0003"},
          "yield_time_ms":{"type":"integer","minimum":0,"maximum":30000,"default":1000,"description":"等待新输出或退出的毫秒数"},
          "max_output_chars":{"type":"integer","minimum":100,"maximum":30000,"default":12000},
          "kill":{"type":"boolean","default":false,"description":"true 时强制终止该会话"}
        },"required":["session_id"]}
    """

    const val SKILL_READ = """
        {"type":"object","properties":{
          "name":{"type":"string","description":"skill 名，例如 deploy；会在 skills/ 和 .agents/skills/ 中查找"},
          "path":{"type":"string","description":"可选，精确的 SKILL.md 相对路径，例如 .agents/skills/deploy/SKILL.md"}
        }}
    """

    const val HTTP_GET = """
        {"type":"object","properties":{
          "url":{"type":"string","description":"http/https 地址"}
        },"required":["url"]}
    """

    const val PDF_READ = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"当前工作区里的 PDF 路径"},
          "start_page":{"type":"integer","minimum":1,"default":1,"description":"起始页，1 开始"},
          "end_page":{"type":"integer","minimum":1,"description":"结束页，包含；省略时从 start_page 起最多读 100 页"},
          "max_kb":{"type":"integer","minimum":8,"maximum":256,"default":60,"description":"本次最多返回的文本大小（KiB）"}
        },"required":["path"]}
    """

    const val VIEW_IMAGE = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"当前工作区里的图片路径（png/jpg/webp/gif）"}
        },"required":["path"]}
    """

    const val SSH_EXEC = """
        {"type":"object","properties":{
          "command":{"type":"string","description":"非交互式 shell 命令。每次调用都是独立 shell，cd/export 不会跨调用保留；需要时在同一命令里用 &&。stdin 会立即 EOF，不要运行编辑器或等待输入的 sudo"},
          "timeout_ms":{"type":"integer","minimum":1000,"maximum":600000,"default":120000,"description":"命令执行阶段的超时毫秒，默认 120000，范围 1000..600000；建连另有 15 秒上限"},
          "host":{"type":"string","description":"主机 id 或名字，省略时用第一台启用的主机"}
        },"required":["command"]}
    """

    const val SSH_READ = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"远端文件路径，相对路径落在该主机 workDir 下"},
          "offset":{"type":"integer","minimum":0,"description":"起始字节偏移（不是行号），默认 0；续读时填上一条结果里的 next_offset"},
          "limit":{"type":"integer","minimum":1,"maximum":1048576,"default":262144,"description":"本次最多读取的字节数，默认 262144（256KB），最大 1048576（1MB）"},
          "host":{"type":"string","description":"主机 id 或名字，可省略"}
        },"required":["path"]}
    """

    const val SSH_WRITE = """
        {"type":"object","properties":{
          "path":{"type":"string","description":"远端文件路径，相对路径落在该主机 workDir 下"},
          "content":{"type":"string","description":"完整文件内容，会覆盖原文件"},
          "host":{"type":"string","description":"主机 id 或名字，可省略"}
        },"required":["path","content"]}
    """

    const val SSH_LS = """
        {"type":"object","properties":{
          "path":{"type":"string","default":".","description":"远端目录，默认 ."},
          "host":{"type":"string","description":"主机 id 或名字，可省略"}
        }}
    """

    const val WEB_SEARCH = """
        {"type":"object","properties":{
          "query":{"type":"string","description":"搜索关键词，例如 宇树科技 股票 最新"}
        },"required":["query"]}
    """

    const val REMOTE_CODEX = """
        {"type":"object","properties":{
          "prompt":{"type":"string","description":"交给远端 Codex 的完整任务说明；可包含必要上下文和验收标准"},
          "cwd":{"type":"string","description":"远端工作目录；省略时用当前 /workspace 的远端目录"},
          "host":{"type":"string","description":"主机 id 或名字；省略时用当前 /workspace 的远端主机"},
          "resume":{"type":"string","description":"可选的远端 Codex session id；继续已有远端会话时填写"},
          "model":{"type":"string","description":"可选的模型 id；省略时使用远端 Codex 当前配置"},
          "reasoning_effort":{"type":"string","description":"可选的思考档位，例如 low/high/max"},
          "timeout_ms":{"type":"integer","minimum":10000,"maximum":1800000,"default":600000,"description":"远端 Codex 整轮超时，默认 10 分钟"}
        },"required":["prompt"]}
    """
}
