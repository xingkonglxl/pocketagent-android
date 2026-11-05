package app.pocketagent.android.tools

import app.pocketagent.android.net.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

class FsListTool(private val workspace: Workspace) : Tool {
    constructor(root: File) : this(LocalWorkspace(root))

    override val spec = ToolSpec(
        name = "fs_list",
        description = "列出当前工作区某个目录的直接子项（非递归），含类型和大小。" +
            "先用它确认路径，再 fs_read；目录项很多时结果会明确提示截断。",
        parametersJson = ToolSchemas.FS_LIST,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty().ifBlank { "." }
        ToolOutcome.Ok("工作区：${workspace.label}\n${workspace.list(path)}")
    }.getOrElse { ToolOutcome.Error("列出目录失败：${it.message ?: it.javaClass.simpleName}") }
}

class FsReadTool(private val workspace: Workspace) : Tool {
    constructor(root: File) : this(LocalWorkspace(root))

    override val spec = ToolSpec(
        name = "fs_read",
        description = "分页读取工作区里的文本文件：默认从开头读 256KB，最大 1MB。" +
            "offset 是字节偏移而不是行号；结果若被截断，会给出 next_offset，下一次保持 path 不变并传该 offset。",
        parametersJson = ToolSchemas.FS_READ_PAGED,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty()
        if (path.isBlank()) {
            return ToolOutcome.Error("缺少必填参数 path。示例：{\"path\":\"logs/build.txt\"}")
        }
        val offset = argLong(argumentsJson, "offset") ?: 0L
        if (offset < 0) return ToolOutcome.Error("offset 不能为负数：$offset")
        val limit = argInt(argumentsJson, "limit") ?: DEFAULT_WORKSPACE_READ_BYTES
        if (limit !in 1..MAX_WORKSPACE_READ_BYTES) {
            return ToolOutcome.Error("limit 必须在 1..$MAX_WORKSPACE_READ_BYTES 之间，收到 $limit")
        }
        ToolOutcome.Ok(workspace.read(path, offset, limit))
    }.getOrElse { ToolOutcome.Error("读取失败：${it.message ?: it.javaClass.simpleName}") }
}

class PdfReadTool(private val workspace: Workspace) : Tool {
    constructor(root: File) : this(LocalWorkspace(root))

    override val spec = ToolSpec(
        name = "pdf_read",
        description = "分页读取当前工作区（手机或 /workspace 选中的远端主机）PDF 的文本层。" +
            "用 start_page/end_page 指定页段，默认最多 60KB/100 页；长报告应继续读后续页段。" +
            "扫描版没有文本层时会提示改用聊天输入框的图片读取。",
        parametersJson = ToolSchemas.PDF_READ,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty()
        if (path.isBlank()) return ToolOutcome.Error("缺少必填参数 path")
        val startPage = argInt(argumentsJson, "start_page") ?: 1
        val endPage = argInt(argumentsJson, "end_page") ?: 0
        val maxKb = argInt(argumentsJson, "max_kb") ?: 60
        if (startPage < 1) return ToolOutcome.Error("start_page 必须从 1 开始")
        if (endPage != 0 && endPage < startPage) {
            return ToolOutcome.Error("end_page 不能小于 start_page（start_page=$startPage）")
        }
        if (maxKb !in 8..256) return ToolOutcome.Error("max_kb 必须在 8..256 之间")
        val maxPages = if (endPage == 0) {
            PdfTextExtractor.DEFAULT_MAX_PAGES
        } else {
            endPage - startPage + 1
        }
        val maxBytes = maxKb * 1024
        // 本地文件直接交给解析器（流式读，上限 40MB）；先 readBytes 再解析会被 8MB 上限判成「文件太大」
        val local = workspace.openLocalFile(path)
        val extraction = if (local != null) {
            PdfTextExtractor.inspect(
                file = local,
                maxChars = maxBytes,
                maxBytes = maxBytes,
                maxPages = maxPages,
                startPage = startPage,
            )
        } else {
            val bytes = workspace.readBytes(path)
            val tmp = File.createTempFile("pocketagent-pdf-", ".pdf")
            try {
                tmp.writeBytes(bytes)
                PdfTextExtractor.inspect(
                    file = tmp,
                    maxChars = maxBytes,
                    maxBytes = maxBytes,
                    maxPages = maxPages,
                    startPage = startPage,
                )
            } finally {
                tmp.delete()
            }
        }
        if (extraction.text.isNotBlank()) {
            ToolOutcome.Ok(extraction.text)
        } else {
            val scope = if (extraction.pageLimited) {
                "第 ${extraction.pageFrom}–${extraction.pageTo}/${extraction.pageCount} 页"
            } else {
                "全部 ${extraction.pageCount} 页"
            }
            ToolOutcome.Error(
                "PDF ${scope}没有可提取文本，可能是扫描版。" +
                    "当前 pdf_read 不做 OCR；请在聊天输入框用 + 号重新选择该 PDF，让 App 把前 8 页渲染成图片",
            )
        }
    }.getOrElse { ToolOutcome.Error(it.message ?: "读取 PDF 失败") }
}

class FsWriteTool(private val workspace: Workspace) : Tool {
    constructor(root: File) : this(LocalWorkspace(root))

    override val spec = ToolSpec(
        name = "fs_write",
        description = "把完整内容写入工作区文件（覆盖，不是局部 patch，目录会自动创建）。" +
            "修改已有文件前先 fs_read；提交的是整个文件的新内容。",
        parametersJson = ToolSchemas.FS_WRITE,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty()
        if (path.isBlank()) return ToolOutcome.Error("缺少必填参数 path。示例：{\"path\":\"notes/a.md\",\"content\":\"...\"}")
        val content = arg(argumentsJson, "content")
            ?: return ToolOutcome.Error("缺少必填参数 content（空文件也要显式传空字符串）")
        workspace.write(path, content)
        ToolOutcome.Ok("已写入 ${content.toByteArray().size} 字节到 $path")
    }.getOrElse { ToolOutcome.Error("写入失败：${it.message ?: it.javaClass.simpleName}") }
}

class FsDeleteTool(private val workspace: Workspace) : Tool {
    constructor(root: File) : this(LocalWorkspace(root))

    override val spec = ToolSpec(
        name = "fs_delete",
        description = "删除工作区里的单个文件；拒绝删除目录。调用前应确认路径，删除不可撤销。",
        parametersJson = ToolSchemas.FS_DELETE,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty()
        if (path.isBlank()) return ToolOutcome.Error("缺少必填参数 path。示例：{\"path\":\"tmp/output.txt\"}")
        workspace.delete(path)
        ToolOutcome.Ok("已删除 $path")
    }.getOrElse { ToolOutcome.Error("删除失败：${it.message ?: it.javaClass.simpleName}") }
}

private fun argumentsObject(json: String): JsonObject {
    val parsed = try {
        Json.parseToJsonElement(json)
    } catch (e: Exception) {
        throw IllegalArgumentException("工具参数不是合法 JSON：${e.message ?: "无法解析"}")
    }
    return parsed as? JsonObject
        ?: throw IllegalArgumentException("工具参数必须是 JSON 对象，例如 {\"path\":\"a.txt\"}")
}

internal fun arg(json: String, key: String): String? {
    val value = argumentsObject(json)[key] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("参数 $key 必须是字符串或数字，不能是对象/数组")
    return primitive.contentOrNull
}

internal fun argLong(json: String, key: String): Long? {
    val raw = arg(json, key) ?: return null
    return raw.toLongOrNull()
        ?: throw IllegalArgumentException("参数 $key 必须是整数，收到：$raw")
}

internal fun argInt(json: String, key: String): Int? {
    val raw = arg(json, key) ?: return null
    return raw.toIntOrNull()
        ?: throw IllegalArgumentException("参数 $key 必须是 32 位整数，收到：$raw")
}

internal fun argBoolean(json: String, key: String): Boolean? {
    val raw = arg(json, key) ?: return null
    return raw.toBooleanStrictOrNull()
        ?: throw IllegalArgumentException("参数 $key 必须是 true 或 false，收到：$raw")
}

/**
 * 和桌面 Codex 的 view_image 对齐：把工作区里的图片真正读进模型上下文。
 * 桌面那边的 view_image_tool 是把图片作为 image 内容块回灌；这里同样走 data URL，
 * 三种协议（responses / chat completions / anthropic）都支持 user 消息带图片。
 *
 * 图片只在本次回答的上下文里有效；下一轮想再看要重新调用，避免把几 MB 的 base64
 * 反复塞进历史、把存储和同步撑爆。
 */
class ViewImageTool(private val workspace: Workspace) : Tool {
    constructor(root: File) : this(LocalWorkspace(root))

    override val spec = ToolSpec(
        name = "view_image",
        description = "读取当前工作区里的一张图片（png/jpg/webp/gif）并交给模型看，" +
            "用于看截图、图表、界面草图。单张上限 1.4MB，超过请先压缩或裁剪。",
        parametersJson = ToolSchemas.VIEW_IMAGE,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome = runToolCatching {
        val path = arg(argumentsJson, "path")?.trim().orEmpty()
        if (path.isBlank()) {
            return ToolOutcome.Error("缺少必填参数 path。示例：{\"path\":\"shots/home.png\"}")
        }
        val mime = mimeOf(path)
            ?: return ToolOutcome.Error("只支持 png/jpg/jpeg/webp/gif，收到：$path")
        val bytes = workspace.readBytes(path, MAX_IMAGE_BYTES)
        if (bytes.isEmpty()) return ToolOutcome.Error("图片是空的：$path")
        if (bytes.size >= MAX_IMAGE_BYTES) {
            return ToolOutcome.Error(
                "图片太大（≥${MAX_IMAGE_BYTES / 1024 / 1024}MB）：$path。先压缩或裁剪后再读。",
            )
        }
        val url = "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
        ToolOutcome.Ok(
            text = "已读取图片 $path（${bytes.size} 字节，$mime），图片已附在本轮上下文里。",
            images = listOf(url),
        )
    }.getOrElse { ToolOutcome.Error("读取图片失败：${it.message ?: it.javaClass.simpleName}") }

    private fun mimeOf(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }

    companion object {
        /** base64 后约 1.9MB，给中转站的 8MiB 请求体留足余量。 */
        const val MAX_IMAGE_BYTES = 1_400_000
    }
}
