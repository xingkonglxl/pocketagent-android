package app.pocketagent.android.agent

import app.pocketagent.android.tools.Workspace

/**
 * 按桌面 Codex 的层级读取 AGENTS.md：
 * 从工作区根目录向上找父目录，根目录到叶子依次拼接；同一目录里
 * `AGENTS.override.md` 优先于 `AGENTS.md`。
 *
 * 只读取文件名明确为 AGENTS.md / AGENTS.override.md 的文件，避免把
 * 工作区外的任意文件带进系统提示。
 */
object WorkspaceInstructions {
    const val MAX_CHARS = 20_000
    private const val MAX_PARENT_LEVELS = 3
    private const val MAX_FILE_BYTES = 64 * 1024

    suspend fun load(workspace: Workspace): String? {
        val root = workspace.rootPath ?: return fallback(workspace)
        val dirs = when {
            root.startsWith("/") -> ancestorDirs(root, MAX_PARENT_LEVELS)
            root == "~" || root.startsWith("~/") -> tildeDirs(root, MAX_PARENT_LEVELS)
            else -> return fallback(workspace)
        }
        val chunks = mutableListOf<String>()
        for (dir in dirs) {
            val overridePath = "$dir/AGENTS.override.md"
            val normalPath = "$dir/AGENTS.md"
            val override = read(workspace, overridePath)
            val text = override ?: read(workspace, normalPath) ?: continue
            val path = if (override != null) overridePath else normalPath
            chunks += "### $path\n$text"
        }
        return chunks.joinToString("\n\n").take(MAX_CHARS).ifBlank { null }
    }

    internal fun ancestorDirs(root: String, levels: Int): List<String> {
        val dirs = mutableListOf<String>()
        var current = root.trimEnd('/')
        var remaining = levels
        while (remaining-- >= 0 && current.isNotBlank() && current != "/") {
            dirs += current
            val parent = current.substringBeforeLast('/', "")
            if (parent.isBlank() || parent == "/") break
            current = parent
        }
        return dirs.asReversed()
    }

    /** `~/Projects/app` 这种远端默认工作区也要能读到 `~/AGENTS.md`。 */
    internal fun tildeDirs(root: String, levels: Int): List<String> {
        val parts = if (root == "~") {
            emptyList()
        } else {
            root.removePrefix("~/").split('/').filter { it.isNotBlank() }
        }
        val dirs = mutableListOf("~")
        var current = "~"
        for (part in parts.take(levels)) {
            current = "$current/$part"
            dirs += current
        }
        if (dirs.last() != root.trimEnd('/')) dirs += root.trimEnd('/')
        return dirs
    }

    private suspend fun read(workspace: Workspace, path: String): String? =
        runCatching { workspace.readInstruction(path) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** 相对路径/`~` 工作区没有可靠的父目录，退回只读工作区根目录。 */
    private suspend fun fallback(workspace: Workspace): String? {
        val override = runCatching {
            workspace.read("AGENTS.override.md", 0, MAX_FILE_BYTES)
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (override != null) return override
        return runCatching {
            workspace.read("AGENTS.md", 0, MAX_FILE_BYTES)
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}
