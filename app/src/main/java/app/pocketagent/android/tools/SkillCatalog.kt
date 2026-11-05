package app.pocketagent.android.tools

/**
 * 手机工作区的 skill 发现器。
 *
 * Codex 的标准仓库位置是 `.agents/skills/<name>/SKILL.md`；旧版 PocketAgent 只读
 * `skills/<name>/SKILL.md`。两处都支持，避免同步过来的仓库在手机上突然“看不见 skill”。
 */
data class WorkspaceSkill(
    val name: String,
    val path: String,
    val description: String,
)

object SkillCatalog {
    val roots = listOf("skills", ".agents/skills")
    const val MAX_SKILLS = 40
    private const val MAX_HEAD_BYTES = 8 * 1024

    suspend fun discover(workspace: Workspace): List<WorkspaceSkill> {
        val found = mutableListOf<WorkspaceSkill>()
        val seen = mutableSetOf<String>()
        for (root in roots) {
            val listing = runCatching { workspace.list(root) }.getOrNull() ?: continue
            for (entry in directoryNames(listing)) {
                if (found.size >= MAX_SKILLS) return found
                val path = "$root/$entry/SKILL.md"
                val markdown = runCatching {
                    workspace.read(path, 0, MAX_HEAD_BYTES)
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add("$entry\u0000$path")) continue
                val description = descriptionOf(markdown)
                found += WorkspaceSkill(entry, path, description)
            }
        }
        return found
    }

    suspend fun read(workspace: Workspace, name: String, explicitPath: String? = null): String {
        if (!explicitPath.isNullOrBlank()) {
            val clean = explicitPath.trim().removePrefix("./")
            require(clean.endsWith("/SKILL.md")) { "skill path 必须指向 SKILL.md：$explicitPath" }
            require(roots.any { clean == "$it" || clean.startsWith("$it/") }) {
                "skill path 只能位于 ${roots.joinToString(" 或 ")} 下：$explicitPath"
            }
            require(clean.split('/').none { it == ".." }) { "路径越界：$explicitPath" }
            return workspace.read(clean, 0, 64 * 1024)
        }
        require(name.isNotBlank()) { "缺少 name" }
        require(name.all { it.isLetterOrDigit() || it in "-_" }) {
            "name 只能包含字母、数字、- 和 _：$name"
        }
        var last: Throwable? = null
        for (root in roots) {
            val path = "$root/$name/SKILL.md"
            val result = runCatching { workspace.read(path, 0, 64 * 1024) }
            result.getOrNull()?.let { return it }
            last = result.exceptionOrNull() ?: last
        }
        throw last ?: IllegalArgumentException("找不到 skill：$name")
    }

    private fun directoryNames(listing: String): List<String> {
        val local = listing.lineSequence().mapNotNull { line ->
            if (!line.startsWith("DIR")) return@mapNotNull null
            line.substringAfterLast('\t').trim().takeIf { it.isNotBlank() }
        }.toList()
        if (local.isNotEmpty()) return local
        // RemoteWorkspace.list intentionally returns plain names because `ls -1A` has no type column.
        return listing.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("…") && !it.startsWith("（") && it != "." && it != ".." }
            .toList()
    }

    private fun descriptionOf(markdown: String): String {
        val frontMatter = markdown.lineSequence()
            .dropWhile { it.trim() != "---" }
            .drop(1)
            .takeWhile { it.trim() != "---" }
            .toList()
        val described = frontMatter.firstOrNull { it.trimStart().startsWith("description:", true) }
            ?.substringAfter(':')?.trim()?.take(180)
            .orEmpty()
        if (described.isNotBlank()) return described
        return markdown.lineSequence()
            .firstOrNull { it.isNotBlank() && !it.trim().startsWith("---") }
            ?.trim()?.take(180)
            .orEmpty()
    }
}
