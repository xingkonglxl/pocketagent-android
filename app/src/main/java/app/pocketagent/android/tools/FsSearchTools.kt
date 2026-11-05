package app.pocketagent.android.tools

import app.pocketagent.android.net.ToolSpec

/**
 * 本地/远端通用检索：对应桌面 Codex 的 rg / find。
 * 手机上以前只能逐层 fs_list + fs_read，找一处代码要来回十几次，模型很容易在半路放弃。
 */
class FsGlobTool(private val workspace: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "fs_glob",
        description = "按 glob 匹配工作区里的文件相对路径（find / rg --files 的等价物）。" +
            "示例：**/*.kt、src/**/*.md、*.log。比逐层 fs_list 快得多。",
        parametersJson = ToolSchemas.FS_GLOB,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val pattern = arg(argumentsJson, "pattern")?.trim().orEmpty()
        if (pattern.isBlank()) return ToolOutcome.Error("缺少 pattern，例如 {\"pattern\":\"**/*.kt\"}")
        val max = (argInt(argumentsJson, "max") ?: 200).coerceIn(1, 500)
        val hits = workspace.glob(pattern, max)
            ?: return ToolOutcome.Error("当前工作区不支持 fs_glob；远端请改用 ssh_exec 执行 find/rg")
        if (hits.isEmpty()) ToolOutcome.Ok("没有匹配 $pattern 的文件") else ToolOutcome.Ok(hits.joinToString("\n"))
    }.getOrElse { ToolOutcome.Error("glob 失败：${it.message ?: it.javaClass.simpleName}") }
}

class FsSearchTool(private val workspace: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "fs_search",
        description = "在工作区文件内容里搜索文本或正则（rg 的等价物），返回 path:line: 片段。" +
            "查日志、找调用点、定位报错都先用它，不要逐文件 fs_read。",
        parametersJson = ToolSchemas.FS_SEARCH,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val query = arg(argumentsJson, "query")?.trim().orEmpty()
        if (query.isBlank()) return ToolOutcome.Error("缺少 query")
        val regex = arg(argumentsJson, "regex")?.toBooleanStrictOrNull() ?: false
        val max = (argInt(argumentsJson, "max") ?: 100).coerceIn(1, 300)
        if (regex) {
            runCatching { Regex(query) }
                .getOrElse { return ToolOutcome.Error("正则不合法：${it.message}") }
        }
        val hits = workspace.searchText(query, regex, max)
            ?: return ToolOutcome.Error("当前工作区不支持 fs_search；远端请改用 ssh_exec 执行 rg")
        if (hits.isEmpty()) return ToolOutcome.Ok("没有匹配 \"$query\" 的内容")
        ToolOutcome.Ok(hits.joinToString("\n") { "${it.path}:${it.line}: ${it.text}" })
    }.getOrElse { ToolOutcome.Error("搜索失败：${it.message ?: it.javaClass.simpleName}") }
}

/**
 * 精确局部替换：对应桌面 Codex 的 apply_patch。
 * 手机上没有 shell，整文件 fs_write 容易把无关内容改坏，也让模型多输出一大段。
 */
class FsEditTool(private val workspace: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "fs_edit",
        description = "在工作区文件里做精确局部替换（apply_patch 的等价物），不需要重写整个文件。" +
            "find 必须与原文逐字一致（含缩进）；默认要求只匹配一处，多处时显式传 all=true。",
        parametersJson = ToolSchemas.FS_EDIT,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty()
        if (path.isBlank()) return ToolOutcome.Error("缺少 path")
        val find = arg(argumentsJson, "find") ?: return ToolOutcome.Error("缺少 find（要被替换的原文）")
        if (find.isEmpty()) return ToolOutcome.Error("find 不能为空")
        val replace = arg(argumentsJson, "replace") ?: ""
        val all = arg(argumentsJson, "all")?.toBooleanStrictOrNull() ?: false

        val original = workspace.read(path, 0, MAX_WORKSPACE_READ_BYTES)
        if (original.contains("fs_read 已截断")) {
            return ToolOutcome.Error("文件超过 ${MAX_WORKSPACE_READ_BYTES} 字节，局部替换不安全；请分段处理或用 fs_write")
        }
        val occurrences = countOccurrences(original, find)
        if (occurrences == 0) {
            return ToolOutcome.Error(
                "在 $path 里没找到要替换的原文（必须逐字一致，包括空格和换行）。先用 fs_read 看一下当前内容。",
            )
        }
        if (occurrences > 1 && !all) {
            return ToolOutcome.Error("find 在 $path 里匹配到 $occurrences 处；请给更长、唯一的上下文，或传 all=true。")
        }
        val updated = if (all) original.replace(find, replace) else original.replaceFirst(find, replace)
        workspace.write(path, updated)
        ToolOutcome.Ok("已修改 $path：替换 $occurrences 处")
    }.getOrElse { ToolOutcome.Error("编辑失败：${it.message ?: it.javaClass.simpleName}") }
}

private fun countOccurrences(text: String, needle: String): Int {
    var count = 0
    var index = text.indexOf(needle)
    while (index >= 0) {
        count++
        index = text.indexOf(needle, index + needle.length)
    }
    return count
}

/** Codex skills 的等价物：skills/ 与 .agents/skills/，按需读取完整步骤。 */
class SkillReadTool(private val workspace: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "skill_read",
        description = "读取工作区 skills/<name>/SKILL.md 或 .agents/skills/<name>/SKILL.md 的完整内容，" +
            "按里面的步骤执行。同名时优先 skills/，也可传 path 精确指定。",
        parametersJson = ToolSchemas.SKILL_READ,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val name = arg(argumentsJson, "name")?.trim().orEmpty()
        val path = arg(argumentsJson, "path")?.trim()?.takeIf { it.isNotBlank() }
        if (name.isBlank() && path == null) {
            return ToolOutcome.Error(
                "缺少 name 或 path，例如 {\"name\":\"deploy\"}",
            )
        }
        ToolOutcome.Ok(SkillCatalog.read(workspace, name, path))
    }.getOrElse { ToolOutcome.Error("读取 skill 失败：${it.message ?: it.javaClass.simpleName}") }
}
