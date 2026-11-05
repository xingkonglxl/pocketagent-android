package app.pocketagent.android.tools

import app.pocketagent.android.net.ToolSpec
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Codex `apply_patch` 的手机/远端等价物。
 *
 * 解析整份补丁 → 读取并验证所有目标 → 计算完整新内容 → 统一提交。解析、路径、
 * 上下文或文件大小任一检查失败时不会写入；提交阶段出现 IO 错误时会尽力回滚已经
 * 完成的文件，避免留下“前几个文件已改、后几个没改”的半成品。
 *
 * 支持 Codex 语法里的 Add File / Update File / Delete File、多个 `@@` hunk、
 * `@@ section` 锚点和 `*** End of File`。`*** Move to` 暂不支持，会明确报错。
 */
class ApplyPatchTool(private val workspace: Workspace) : Tool {
    constructor(root: File) : this(LocalWorkspace(root))

    override val spec = ToolSpec(
        name = "apply_patch",
        description = "用 Codex 的 *** Begin Patch 格式修改一个或多个文件。" +
            "支持 Add File / Update File / Delete File、多个 @@ 上下文 hunk 和 *** End of File；" +
            "会先验证完整补丁再写入，失败时不会只改一半。优先用于多文件代码修改。",
        parametersJson = ToolSchemas.APPLY_PATCH,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val raw = arg(argumentsJson, "patch")
            ?: return ToolOutcome.Error("缺少 patch")
        if (raw.isBlank()) return ToolOutcome.Error("patch 不能为空")
        if (raw.length > MAX_PATCH_CHARS) {
            return ToolOutcome.Error("patch 太大（${raw.length} 字符，上限 $MAX_PATCH_CHARS）")
        }

        val operations = PatchParser.parse(raw)
        if (operations.size > MAX_FILES) {
            return ToolOutcome.Error("一次最多修改 $MAX_FILES 个文件，当前 ${operations.size} 个")
        }

        // 关键：先把所有文件读完并算出最终内容。任何校验失败都在这里抛错，
        // 此时工作区一个字节都还没动。
        val prepared = prepare(operations)
        commit(prepared)

        val writes = prepared.filterIsInstance<PreparedWrite>()
        val deletes = prepared.filterIsInstance<PreparedDelete>()
        val changed = writes.map { it.path } + deletes.map { it.path }
        ToolOutcome.Ok(
            "补丁已应用：写入 ${writes.size} 个文件，删除 ${deletes.size} 个文件。" +
                if (changed.isEmpty()) "" else "\n" + changed.joinToString("\n") { "· $it" },
        )
    }.getOrElse { ToolOutcome.Error("apply_patch 失败：${it.message ?: it.javaClass.simpleName}") }

    private suspend fun prepare(operations: List<PatchOperation>): List<PreparedChange> {
        val seen = mutableSetOf<String>()
        val prepared = mutableListOf<PreparedChange>()
        for (operation in operations) {
            if (!seen.add(operation.path)) {
                throw IllegalArgumentException("同一个文件在补丁里出现了多次：${operation.path}")
            }
            when (operation) {
                is PatchOperation.Add -> {
                    if (fileExists(operation.path)) {
                        throw IllegalArgumentException("Add File 目标已存在：${operation.path}")
                    }
                    requireAppliedSize(operation.path, operation.content)
                    prepared += PreparedWrite(
                        path = operation.path,
                        content = operation.content,
                        original = null,
                    )
                }

                is PatchOperation.Update -> {
                    val original = readOriginal(operation.path)
                    val updated = applyHunks(operation.path, original, operation.hunks)
                    requireAppliedSize(operation.path, updated)
                    prepared += PreparedWrite(
                        path = operation.path,
                        content = updated,
                        original = original,
                    )
                }

                is PatchOperation.Delete -> {
                    prepared += PreparedDelete(
                        path = operation.path,
                        original = readOriginal(operation.path),
                    )
                }
            }
        }
        return prepared
    }

    /** 先写、后删；每个操作成功后记下来，后续失败时按相反顺序恢复。 */
    private suspend fun commit(changes: List<PreparedChange>) {
        val completed = mutableListOf<PreparedChange>()
        var current: PreparedChange? = null
        try {
            for (change in changes) {
                current = change
                when (change) {
                    is PreparedWrite -> workspace.write(change.path, change.content)
                    is PreparedDelete -> workspace.delete(change.path)
                }
                completed += change
                current = null
            }
        } catch (failure: Exception) {
            // 当前操作可能已经改了一半，也纳入回滚：Update/Delete 写回原文，
            // Add 则删除可能刚创建出来的半成品。
            val toRollback = completed + listOfNotNull(current)
            rollback(toRollback, failure)
        }
    }

    private suspend fun rollback(
        completed: List<PreparedChange>,
        failure: Exception,
    ): Nothing {
        val failures = mutableListOf<String>()
        withContext(NonCancellable) {
            for (change in completed.asReversed()) {
                try {
                    when (change) {
                        is PreparedWrite -> {
                            val original = change.original
                            if (original == null) {
                                try {
                                    workspace.delete(change.path)
                                } catch (missing: Exception) {
                                    if (!isMissingFile(missing)) throw missing
                                }
                            } else {
                                workspace.write(change.path, original)
                            }
                        }

                        is PreparedDelete -> workspace.write(change.path, change.original)
                    }
                } catch (rollbackFailure: Exception) {
                    failures += "${change.path}（${rollbackFailure.message ?: "未知错误"}）"
                }
            }
        }

        if (failure is kotlinx.coroutines.CancellationException) throw failure
        val detail = buildString {
            append("提交补丁失败：")
            append(failure.message ?: failure.javaClass.simpleName)
            if (completed.isEmpty()) {
                append("；尚未写入任何文件")
            } else if (failures.isEmpty()) {
                append("；已回滚 ${completed.size} 个文件")
            } else {
                append("；已尝试回滚 ${completed.size} 个文件，但以下路径恢复失败：")
                append(failures.joinToString("、"))
            }
        }
        throw IllegalStateException(detail, failure)
    }

    private fun applyHunks(path: String, original: String, hunks: List<PatchHunk>): String {
        val document = TextDocument.parse(original)
        var lines = document.lines.toMutableList()
        var searchFrom = 0
        for ((hunkIndex, hunk) in hunks.withIndex()) {
            val match = findHunk(lines, hunk, searchFrom)
                ?: throw IllegalArgumentException(
                    "${path} 第 ${hunkIndex + 1} 个 hunk 找不到上下文" +
                        if (hunk.anchor != null) "（锚点：${hunk.anchor}）" else "" +
                            "；请先 fs_read 当前文件，并保留足够的上下文",
                )
            val tail = lines.subList(match.insertAt + hunk.oldLines.size, lines.size)
            lines = (lines.subList(0, match.insertAt) + hunk.newLines + tail).toMutableList()
            searchFrom = match.insertAt + hunk.newLines.size
        }
        return document.render(lines)
    }

    private fun findHunk(lines: List<String>, hunk: PatchHunk, from: Int): HunkMatch? {
        val anchor = hunk.anchor
        if (anchor != null) {
            var anchorAt = from.coerceAtLeast(0)
            while (anchorAt < lines.size) {
                if (anchorMatches(anchor, lines[anchorAt])) {
                    val insertAt = anchorAt + 1
                    if (hunk.oldLines.isEmpty()) {
                        if (!hunk.endOfFile || insertAt == lines.size) {
                            return HunkMatch(insertAt)
                        }
                    } else if (
                        matchesAt(lines, insertAt, hunk.oldLines) &&
                        (!hunk.endOfFile || insertAt + hunk.oldLines.size == lines.size)
                    ) {
                        return HunkMatch(insertAt)
                    }
                }
                anchorAt++
            }
            return null
        }

        if (hunk.oldLines.isEmpty()) {
            return when {
                hunk.endOfFile && from <= lines.size -> HunkMatch(lines.size)
                // 空文件没有上下文可匹配；这是唯一一处允许“只有新增行”的无锚点位置。
                lines.isEmpty() && from == 0 -> HunkMatch(0)
                else -> null
            }
        }
        if (lines.size < hunk.oldLines.size || from > lines.size - hunk.oldLines.size) return null
        for (index in from..(lines.size - hunk.oldLines.size)) {
            if (
                matchesAt(lines, index, hunk.oldLines) &&
                (!hunk.endOfFile || index + hunk.oldLines.size == lines.size)
            ) {
                return HunkMatch(index)
            }
        }
        return null
    }

    private fun anchorMatches(anchor: String, line: String): Boolean =
        line == anchor || line.trim() == anchor.trim()

    private fun matchesAt(lines: List<String>, index: Int, expected: List<String>): Boolean {
        if (index < 0 || index + expected.size > lines.size) return false
        for (offset in expected.indices) {
            if (lines[index + offset] != expected[offset]) return false
        }
        return true
    }

    private suspend fun fileExists(path: String): Boolean = try {
        workspace.read(path, 0, 1)
        true
    } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        if (isMissingFile(failure)) false else throw failure
    }

    private suspend fun readOriginal(path: String): String {
        val original = workspace.read(path, 0, MAX_FILE_BYTES)
        if (isTruncatedRead(original)) {
            throw IllegalArgumentException(
                "$path 超过 $MAX_FILE_BYTES 字节；apply_patch 为保证失败可恢复，不会修改未完整读取的文件。" +
                    "请分段处理或改用 fs_write",
            )
        }
        if (original.contains('\u0000')) {
            throw IllegalArgumentException(
                "$path 看起来是二进制文件；apply_patch 只处理文本，二进制请改用 fs_delete 或 ssh_exec",
            )
        }
        return original
    }

    private fun requireAppliedSize(path: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_FILE_BYTES) {
            throw IllegalArgumentException(
                "$path 应用后为 $bytes 字节，超过 $MAX_FILE_BYTES 上限；请拆分文件或改用 fs_write",
            )
        }
    }

    private fun isMissingFile(failure: Exception): Boolean {
        val message = failure.message.orEmpty()
        return listOf(
            "文件不存在",
            "No such file",
            "cannot open",
            "does not exist",
            "not found",
            "missing",
        ).any { message.contains(it, ignoreCase = true) }
    }

    private fun isTruncatedRead(text: String): Boolean =
        text.contains("（fs_read 已截断") || text.contains("（ssh_read 已截断")

    private data class HunkMatch(val insertAt: Int)

    private sealed interface PreparedChange {
        val path: String
    }

    private data class PreparedWrite(
        override val path: String,
        val content: String,
        /** null 表示 Add File，提交前已确认目标不存在。 */
        val original: String?,
    ) : PreparedChange

    private data class PreparedDelete(
        override val path: String,
        val original: String,
    ) : PreparedChange

    companion object {
        private const val MAX_PATCH_CHARS = 4 * 1024 * 1024
        private const val MAX_FILE_BYTES = MAX_WORKSPACE_READ_BYTES
        private const val MAX_FILES = 50
    }
}

private sealed interface PatchOperation {
    val path: String

    data class Add(override val path: String, val content: String) : PatchOperation
    data class Delete(override val path: String) : PatchOperation
    data class Update(override val path: String, val hunks: List<PatchHunk>) : PatchOperation
}

private data class PatchHunk(
    val anchor: String?,
    val oldLines: List<String>,
    val newLines: List<String>,
    val endOfFile: Boolean,
)

/**
 * 只解析、不碰文件系统。路径在这里就归一化并拒绝绝对路径 / `..` / 反斜杠，
 * 所以后续 Workspace 的 `resolveInside` 是第二道防线。
 */
private object PatchParser {
    fun parse(raw: String): List<PatchOperation> {
        val normalized = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        var first = 0
        while (first < lines.size && lines[first].isBlank()) first++
        var last = lines.lastIndex
        while (last >= 0 && lines[last].isBlank()) last--

        if (first < lines.size && lines[first].trimStart().startsWith("```")) {
            throw IllegalArgumentException(
                "patch 不能包在 Markdown 代码围栏里，请直接传 *** Begin Patch ... *** End Patch",
            )
        }
        require(first < last && lines[first].trim() == "*** Begin Patch") {
            "补丁必须以 *** Begin Patch 开始"
        }
        require(lines[last].trim() == "*** End Patch") {
            "补丁必须以 *** End Patch 结束"
        }
        for (index in (first + 1) until last) {
            if (lines[index].trim() == "*** End Patch") {
                throw IllegalArgumentException("*** End Patch 之后不能再有补丁内容")
            }
        }

        val operations = mutableListOf<PatchOperation>()
        var index = first + 1
        while (index < last) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }
            when {
                line.startsWith("*** Add File: ") -> {
                    val path = normalizePath(line.removePrefix("*** Add File: "))
                    val (content, next) = parseAddBody(lines, index + 1, last)
                    operations += PatchOperation.Add(path, content)
                    index = next
                }

                line.startsWith("*** Delete File: ") -> {
                    val path = normalizePath(line.removePrefix("*** Delete File: "))
                    operations += PatchOperation.Delete(path)
                    index++
                }

                line.startsWith("*** Update File: ") -> {
                    val path = normalizePath(line.removePrefix("*** Update File: "))
                    val (hunks, next) = parseUpdateBody(lines, index + 1, last, path)
                    operations += PatchOperation.Update(path, hunks)
                    index = next
                }

                line.startsWith("*** Move to: ") -> {
                    throw IllegalArgumentException("暂不支持 *** Move to；请用 Delete File + Add File 完成移动")
                }

                line.startsWith("*** ") -> {
                    throw IllegalArgumentException("未知补丁指令：$line")
                }

                else -> {
                    throw IllegalArgumentException(
                        "补丁行必须属于 Add File / Update File / Delete File 指令：$line",
                    )
                }
            }
        }
        require(operations.isNotEmpty()) { "补丁里没有任何文件操作" }
        return operations
    }

    private fun parseAddBody(lines: List<String>, start: Int, endExclusive: Int): Pair<String, Int> {
        val body = mutableListOf<String>()
        var index = start
        while (index < endExclusive && !isDirective(lines[index])) {
            val line = lines[index]
            if (line.isEmpty()) {
                if (onlyBlankUntilDirective(lines, index, endExclusive)) break
                throw IllegalArgumentException("Add File 的每一行都必须以 + 开头；空行请写成 +")
            }
            if (!line.startsWith("+")) {
                throw IllegalArgumentException("Add File 的新增行必须以 + 开头：$line")
            }
            body += line.substring(1)
            index++
        }
        val content = if (body.isEmpty()) "" else body.joinToString("\n") + "\n"
        return content to index
    }

    private fun parseUpdateBody(
        lines: List<String>,
        start: Int,
        endExclusive: Int,
        path: String,
    ): Pair<List<PatchHunk>, Int> {
        val hunks = mutableListOf<PatchHunk>()
        var index = start
        var anchor: String? = null
        var oldLines = mutableListOf<String>()
        var newLines = mutableListOf<String>()
        var hasChange = false
        var hunkStarted = false
        var endOfFile = false

        fun resetHunk() {
            anchor = null
            oldLines = mutableListOf()
            newLines = mutableListOf()
            hasChange = false
            hunkStarted = false
            endOfFile = false
        }

        fun finishHunk() {
            if (!hunkStarted) {
                if (anchor != null) {
                    throw IllegalArgumentException("$path 有一个只有 @@ 锚点、没有修改行的空 hunk")
                }
                return
            }
            if (!hasChange) {
                throw IllegalArgumentException("$path 的 hunk 没有 + 或 - 修改行")
            }
            hunks += PatchHunk(
                anchor = anchor,
                oldLines = oldLines.toList(),
                newLines = newLines.toList(),
                endOfFile = endOfFile,
            )
            resetHunk()
        }

        while (index < endExclusive) {
            val line = lines[index]
            if (line == "*** End of File") {
                if (!hunkStarted) {
                    throw IllegalArgumentException("*** End of File 必须放在一个非空 hunk 的末尾")
                }
                endOfFile = true
                finishHunk()
                index++
                break
            }
            if (line.startsWith("*** ")) {
                if (line.startsWith("*** Move to: ")) {
                    throw IllegalArgumentException("暂不支持 *** Move to；请用 Delete File + Add File 完成移动")
                }
                break
            }
            if (line.startsWith("@@")) {
                if (line != "@@" && !line.startsWith("@@ ")) {
                    throw IllegalArgumentException("@@ 锚点格式不对：$line")
                }
                finishHunk()
                anchor = if (line.length > 2) line.substring(3).takeIf { it.isNotBlank() } else null
                index++
                continue
            }
            if (line.isEmpty()) {
                if (onlyBlankUntilDirective(lines, index, endExclusive)) break
                throw IllegalArgumentException(
                    "$path 的 hunk 行必须以空格、+ 或 - 开头；空行请写成单个空格加内容",
                )
            }
            when {
                line.startsWith("+") -> {
                    hunkStarted = true
                    hasChange = true
                    newLines += line.substring(1)
                }

                line.startsWith("-") -> {
                    hunkStarted = true
                    hasChange = true
                    oldLines += line.substring(1)
                }

                line.startsWith(" ") -> {
                    hunkStarted = true
                    val text = line.substring(1)
                    oldLines += text
                    newLines += text
                }

                else -> {
                    throw IllegalArgumentException(
                        "$path 的 hunk 行必须以空格、+ 或 - 开头：$line",
                    )
                }
            }
            index++
        }
        finishHunk()
        if (hunks.isEmpty()) {
            throw IllegalArgumentException("$path 的 Update File 没有 hunk")
        }
        return hunks to index
    }

    private fun onlyBlankUntilDirective(
        lines: List<String>,
        start: Int,
        endExclusive: Int,
    ): Boolean {
        for (index in start until endExclusive) {
            if (lines[index].isNotEmpty()) return isDirective(lines[index])
        }
        return true
    }

    private fun isDirective(line: String): Boolean = line.startsWith("*** ")

    private fun normalizePath(raw: String): String {
        val path = raw.trim()
        require(path.isNotEmpty()) { "路径越界：文件路径不能为空" }
        require(!path.startsWith("/") && !path.startsWith("\\")) {
            "路径越界：只允许工作区内的相对路径：$raw"
        }
        require(!WINDOWS_DRIVE.matches(path)) { "路径越界：只允许工作区内的相对路径：$raw" }
        require('\\' !in path) { "路径越界：路径必须使用 /，不能包含反斜杠：$raw" }
        require('\u0000' !in path) { "路径不能包含 NUL 字符" }
        val segments = path.split('/')
        require(segments.none { it == ".." }) { "路径越界：$raw" }
        val normalized = segments
            .filter { it.isNotEmpty() && it != "." }
            .joinToString("/")
        require(normalized.isNotEmpty()) { "路径越界：不能指向工作区根目录：$raw" }
        return normalized
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/].*")
}

/**
 * 文件按逻辑行编辑，但保留原文件的换行风格和末尾是否有换行。
 * 远端 macOS/Linux 与手机本地都可能碰到 CRLF，不能直接按 `\n` 切分。
 */
private data class TextDocument(
    val lines: List<String>,
    val trailingNewline: Boolean,
    val newline: String,
) {
    fun render(newLines: List<String>): String {
        val body = newLines.joinToString("\n")
        // 空文件没有原换行风格可继承；首次写入按常规文本文件补一个末尾换行。
        val keepTrailing = if (lines.isEmpty()) {
            newLines.isNotEmpty()
        } else {
            trailingNewline && newLines.isNotEmpty()
        }
        val normalized = if (keepTrailing) "$body\n" else body
        return if (newline == "\n") normalized else normalized.replace("\n", newline)
    }

    companion object {
        fun parse(text: String): TextDocument {
            val newline = when {
                text.contains("\r\n") -> "\r\n"
                text.contains('\r') -> "\r"
                else -> "\n"
            }
            val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            val trailingNewline = normalized.endsWith("\n")
            val body = if (trailingNewline) normalized.dropLast(1) else normalized
            val lines = if (body.isEmpty() && !trailingNewline) {
                emptyList()
            } else {
                body.split('\n')
            }
            return TextDocument(lines, trailingNewline, newline)
        }
    }
}
