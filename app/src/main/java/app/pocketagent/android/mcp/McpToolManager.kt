package app.pocketagent.android.mcp

import app.pocketagent.android.net.ToolSpec
import app.pocketagent.android.security.SecretStore
import app.pocketagent.android.tools.Tool
import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.arg
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Locale

data class McpRefreshSummary(
    val servers: Int,
    val tools: Int,
    val errors: List<String>,
) {
    fun message(): String = when {
        servers == 0 -> "没有启用的 MCP 服务器"
        errors.isNotEmpty() -> "已连接 ${servers - errors.size}/$servers 个 MCP 服务器，发现 $tools 个工具；失败：" +
            errors.joinToString("；")
        else -> "已连接 $servers 个 MCP 服务器，发现 $tools 个工具"
    }
}

/**
 * 把用户配置的 MCP 服务器上的工具接进手机 harness。
 *
 * `tools/list` 的结果缓存 [ttlMs]，避免每一轮都重新建连；服务器报错时保留上一次
 * 成功发现的工具，只把错误报到界面，不会让正在跑的任务突然少一半工具。
 */
class McpToolManager(
    private val store: McpServerStore,
    private val secrets: SecretStore,
    private val clientFactory: (McpServerConfig, String?) -> McpSession = { server, token ->
        McpClient(server.url, token)
    },
    private val ttlMs: Long = 10 * 60_000,
    private val discoveryTimeoutMs: Long = 20_000,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: List<Tool> = emptyList()

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var lastErrors: List<String> = emptyList()

    @Volatile
    private var sessionsByServer: Map<String, Pair<McpServerConfig, McpSession>> = emptyMap()

    fun tools(): List<Tool> = cached

    fun errors(): List<String> = lastErrors

    fun invalidate() {
        cachedAt = 0L
    }

    fun enabledServers(): List<McpServerConfig> =
        store.load().filter { it.enabled && it.url.isNotBlank() }

    suspend fun ensureFresh(force: Boolean = false): McpRefreshSummary {
        val now = System.currentTimeMillis()
        if (!force && cachedAt > 0 && now - cachedAt < ttlMs) {
            return McpRefreshSummary(enabledServers().size, cached.size, lastErrors)
        }
        return mutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force && cachedAt > 0 && lockedNow - cachedAt < ttlMs) {
                return@withLock McpRefreshSummary(enabledServers().size, cached.size, lastErrors)
            }
            val servers = enabledServers()
            if (servers.isEmpty()) {
                cached = emptyList()
                cachedAt = lockedNow
                lastErrors = emptyList()
                sessionsByServer = emptyMap()
                return@withLock McpRefreshSummary(0, 0, emptyList())
            }
            val discovered = mutableListOf<Tool>()
            val used = mutableSetOf<String>()
            // 资源工具的名字先占住，避免某个服务器的 id + 工具名恰好拼成同名。
            used += McpListResourcesTool.NAME
            used += McpReadResourceTool.NAME
            val errors = mutableListOf<String>()
            val sessions = mutableMapOf<String, Pair<McpServerConfig, McpSession>>()
            for (server in servers) {
                val token = runCatching { secrets.get(server.tokenId) }.getOrNull()
                val client = clientFactory(server, token)
                // 即使 tools/list 失败，资源也可能可用，session 一并留着。
                sessions[server.id] = server to client
                val outcome = runCatching {
                    withTimeout(discoveryTimeoutMs) { client.listTools() }
                }
                outcome.onSuccess { infos ->
                    infos.forEach { info ->
                        val name = toolName(server, info.name, used)
                        used += name
                        discovered += McpServerTool(
                            spec = ToolSpec(
                                name = name,
                                description = describe(server, info),
                                parametersJson = info.inputSchema
                                    .takeIf { it.isNotEmpty() }
                                    ?.toString()
                                    ?: EMPTY_SCHEMA,
                            ),
                            serverId = server.id,
                            serverName = server.name.ifBlank { server.url },
                            remoteName = info.name,
                            client = client,
                        )
                    }
                }.onFailure { error ->
                    errors += "${server.name.ifBlank { server.url }}：" +
                        (error.message?.take(200) ?: error.javaClass.simpleName)
                }
            }
            sessionsByServer = sessions
            if (sessions.isNotEmpty()) {
                discovered += McpListResourcesTool { sessionsByServer.values.toList() }
                discovered += McpReadResourceTool { sessionsByServer.values.toList() }
            }
            cached = discovered
            cachedAt = lockedNow
            lastErrors = errors
            McpRefreshSummary(servers.size, discovered.size, errors)
        }
    }

    private fun describe(server: McpServerConfig, info: McpToolInfo): String {
        val label = server.name.ifBlank { server.url }
        val body = info.description.trim().ifBlank { "调用 ${server.url} 上的 ${info.name}" }
        val prefix = "[MCP $label] "
        return (prefix + body.replace(Regex("\\s+"), " ")).take(MAX_DESCRIPTION_CHARS)
    }

    private fun toolName(server: McpServerConfig, remoteName: String, used: Set<String>): String {
        val base = "mcp__${slug(server.id).ifBlank { "server" }}__${slug(remoteName).ifBlank { "tool" }}"
        var candidate = base.take(MAX_TOOL_NAME_CHARS)
        var index = 2
        while (candidate in used) {
            val suffix = "_$index"
            candidate = (base.take(MAX_TOOL_NAME_CHARS - suffix.length) + suffix)
            index += 1
        }
        return candidate
    }

    private fun slug(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-', '_')

    private companion object {
        const val MAX_TOOL_NAME_CHARS = 64
        const val MAX_DESCRIPTION_CHARS = 600
        const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""
    }
}

/**
 * 资源类工具：和桌面 Codex 的 `list_mcp_resources` / `read_mcp_resource` 对齐。
 * 服务器只提供工具、不提供资源时，调用会明确说明，而不是静默返回空。
 */
class McpListResourcesTool(
    private val sessions: () -> List<Pair<McpServerConfig, McpSession>>,
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "列出 MCP 服务器暴露的资源（resources/list）。可用 server 只看某一台；" +
            "资源常用于文档、数据集、知识库条目。",
        parametersJson = """
            {"type":"object","properties":{
              "server":{"type":"string","description":"可选：服务器名称或 id；省略则列出全部"}
            }}
        """,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome {
        val wanted = arg(argumentsJson, "server")?.trim()?.takeIf { it.isNotEmpty() }
        val all = sessions()
        if (all.isEmpty()) return ToolOutcome.Error("还没有可用的 MCP 服务器")
        val targets = all.filter { (config, _) -> wanted == null || matches(config, wanted) }
        if (targets.isEmpty()) return ToolOutcome.Error("找不到 MCP 服务器：$wanted")
        val text = buildString {
            targets.forEach { (config, session) ->
                val label = config.name.ifBlank { config.id }
                val outcome = runCatching { session.listResources() }
                outcome.onSuccess { resources ->
                    append("· ").append(label).append("：")
                    if (resources.isEmpty()) {
                        append("没有资源\n")
                    } else {
                        append(resources.size).append(" 个\n")
                        resources.take(MAX_RESOURCES_PER_SERVER).forEach { resource ->
                            append("  - ").append(resource.uri)
                            if (resource.name.isNotBlank()) append("（").append(resource.name).append("）")
                            if (resource.mimeType.isNotBlank()) append(" [").append(resource.mimeType).append("]")
                            append('\n')
                        }
                        if (resources.size > MAX_RESOURCES_PER_SERVER) {
                            append("  …（只显示前 ").append(MAX_RESOURCES_PER_SERVER)
                                .append(" 个，共 ").append(resources.size).append(" 个）\n")
                        }
                    }
                }.onFailure { error ->
                    append("· ").append(label).append("：")
                        .append(error.message ?: error.javaClass.simpleName).append('\n')
                }
            }
        }
        return ToolOutcome.Ok(text.trimEnd())
    }

    companion object {
        const val NAME = "mcp__resources__list"
        private const val MAX_RESOURCES_PER_SERVER = 50
    }
}

class McpReadResourceTool(
    private val sessions: () -> List<Pair<McpServerConfig, McpSession>>,
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "读取 MCP 服务器上的一个资源（resources/read）。先 mcp__resources__list 找 uri；" +
            "binary 资源只回占位。",
        parametersJson = """
            {"type":"object","properties":{
              "server":{"type":"string","description":"服务器名称或 id"},
              "uri":{"type":"string","description":"资源的 uri"}
            },"required":["server","uri"]}
        """,
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome {
        val wanted = arg(argumentsJson, "server")?.trim().orEmpty()
        val uri = arg(argumentsJson, "uri")?.trim().orEmpty()
        if (wanted.isBlank()) return ToolOutcome.Error("缺少 server（服务器名称或 id）")
        if (uri.isBlank()) return ToolOutcome.Error("缺少 uri")
        val target = sessions().firstOrNull { (config, _) -> matches(config, wanted) }
            ?: return ToolOutcome.Error("找不到 MCP 服务器：$wanted")
        return try {
            val text = target.second.readResource(uri)
            if (text.isBlank()) {
                ToolOutcome.Ok("（资源 $uri 没有文本内容）")
            } else {
                ToolOutcome.Ok(clipResource(text))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.Error("读取资源失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun clipResource(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) {
            text
        } else {
            text.take(MAX_RESULT_CHARS) + "\n…（资源超过 $MAX_RESULT_CHARS 字符，已截断）"
        }

    companion object {
        const val NAME = "mcp__resources__read"
        private const val MAX_RESULT_CHARS = 30_000
    }
}

private fun matches(config: McpServerConfig, wanted: String): Boolean =
    config.id.equals(wanted, ignoreCase = true) || config.name.equals(wanted, ignoreCase = true)

/** 单个 MCP 工具。名字形如 `mcp__<server>__<tool>`，参数 schema 由服务器给出。 */
class McpServerTool(
    override val spec: ToolSpec,
    val serverId: String,
    private val serverName: String,
    private val remoteName: String,
    private val client: McpSession,
) : Tool {

    override suspend fun execute(argumentsJson: String): ToolOutcome {
        val arguments = parseArguments(argumentsJson)
            ?: return ToolOutcome.Error("参数不是合法的 JSON 对象：${argumentsJson.take(200)}")
        return try {
            val result = client.callTool(remoteName, arguments)
            val text = clip(result.text)
            if (result.isError) ToolOutcome.Error(text) else ToolOutcome.Ok(text)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome.Error("MCP $serverName/$remoteName 调用失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun parseArguments(raw: String): JsonObject? {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching {
            Json.parseToJsonElement(raw).jsonObject
        }.getOrNull()
    }

    private fun clip(text: String): String =
        if (text.length <= MAX_RESULT_CHARS) {
            text
        } else {
            val omitted = text.length - MAX_RESULT_CHARS
            text.take(MAX_RESULT_CHARS / 2) +
                "\n…（MCP 返回过长，中间 $omitted 字符已省略；需要全文请让服务器返回链接或改用远端工具）…\n" +
                text.takeLast(MAX_RESULT_CHARS / 2)
        }

    private companion object {
        const val MAX_RESULT_CHARS = 30_000
    }
}
