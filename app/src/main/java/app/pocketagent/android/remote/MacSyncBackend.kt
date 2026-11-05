package app.pocketagent.android.remote

import app.pocketagent.android.sync.PhoneContext
import app.pocketagent.android.sync.RemoteManifest
import app.pocketagent.android.sync.RemoteContext
import app.pocketagent.android.sync.SyncBackend
import app.pocketagent.android.sync.SyncProtocol
import app.pocketagent.android.sync.TrashContext
import app.pocketagent.android.sync.TrashActionResult
import app.pocketagent.android.data.AppJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
private data class TrashListing(val protocol: String, val entries: List<TrashContext>)

@Serializable
private data class TrashPurgeReceipt(val protocol: String, val deletedFiles: Int)

@Serializable
private data class TrashBatchItem(val id: String, val deletedFiles: Int = -1, val error: String? = null)

@Serializable
private data class TrashBatchReceipt(val protocol: String, val results: List<TrashBatchItem>)

/**
 * 走现有 SSH 连接和 Mac 上的 `~/.local/bin/pocketagent-export-context` 脚本同步：
 * - 拉：`dump` 一行一组（id/标题/时间/条数/版本/base64 正文）
 * - 推：直接写 `~/.pocketagent/sync/from-phone/<id>.json`，脚本会把它当成一组会话
 */
class MacSyncBackend(
    private val manager: RemoteOperations,
    private val hostId: String? = null,
    private val helperScript: String? = null,
) : SyncBackend {

    private val helperHash: String? = helperScript?.let {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(it.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
    private val helperReady = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val helperMutex = Mutex()

    /**
     * 同步协议脚本随 APK 发布并在首次使用时自动写入目标机。
     * 用户不需要手工安装或维护版本；哈希不一致时自动原子更新。
     */
    private suspend fun ensureHelper(requestedHost: String?) = helperMutex.withLock {
        val script = helperScript ?: return@withLock
        val target = requestedHost ?: hostId
        val key = target.orEmpty()
        if (key in helperReady) return@withLock
        val path = shellPath(HELPER_PATH)
        val probe = manager.exec(
            "if test -f $path; then " +
                "if command -v sha256sum >/dev/null 2>&1; then sha256sum $path; else shasum -a 256 $path; fi; " +
                "fi",
            30_000,
            target,
        )
        val current = Regex("(?m)^([0-9a-fA-F]{64})\\b").find(probe.stdout)?.groupValues?.getOrNull(1)
        if (probe.ok && current.equals(helperHash, ignoreCase = true)) {
            helperReady += key
            return@withLock
        }
        val shell = manager.shell(target)
        ShellRemoteFs(shell).writeAtomic(HELPER_PATH, script)
        shell.exec("chmod 700 -- $path", 30_000).requireOk("安装同步脚本")
        val verify = manager.exec(
            "if command -v sha256sum >/dev/null 2>&1; then sha256sum $path; else shasum -a 256 $path; fi",
            30_000,
            target,
        )
        val installed = Regex("(?m)^([0-9a-fA-F]{64})\\b").find(verify.stdout)?.groupValues?.getOrNull(1)
        check(verify.ok && installed.equals(helperHash, ignoreCase = true)) {
            "同步脚本自动安装失败，请确认目标机支持 Python 3"
        }
        helperReady += key
    }

    override val label: String
        get() = manager.host(hostId)?.name ?: "Mac"

    override fun labelFor(hostId: String?): String =
        manager.host(hostId ?: this.hostId)?.name ?: "Mac"

    override suspend fun dump(hostId: String?): List<RemoteContext> {
        ensureHelper(hostId)
        val result = manager.exec(
            // 先只要清单（id/标题/时间/条数/版本），正文按需再拉
            "POCKETAGENT_SYNC_LIST=0 ~/.local/bin/pocketagent-export-context dump",
            300_000,
            hostId ?: this.hostId,
        )
        if (!result.ok) throw IllegalStateException(result.summary(600))
        val contexts = SyncProtocol.parseDump(result.stdout)
        // 只记数量，不记内容：dump 里是整段会话正文，打进 logcat 等于泄露
        android.util.Log.i(
            "PocketAgentSync",
            "dump exit=${result.exitCode} bytes=${result.stdout.length} " +
                "lines=${result.stdout.count { it == '\n' }} parsed=${contexts.size} " +
                "errLen=${result.stderr.length}",
        )
        if (contexts.isEmpty() && result.stdout.isNotBlank()) {
            throw IllegalStateException("远端清单解析失败（${result.stdout.length} 字符），请检查同步脚本版本")
        }
        return contexts
    }

    override suspend fun dumpItems(hostId: String?, ids: Set<String>): List<RemoteContext> {
        if (ids.isEmpty()) return emptyList()
        ensureHelper(hostId)
        val contexts = mutableListOf<RemoteContext>()
        ids.toList().chunked(512).forEach { batch ->
            val result = manager.exec(
                "~/.local/bin/pocketagent-export-context dumpids " +
                    batch.joinToString(" ") { shellQuote(it) },
                120_000,
                hostId ?: this.hostId,
            )
            if (!result.ok) throw IllegalStateException("读取单条会话清单失败：" + result.summary(400))
            contexts += SyncProtocol.parseDump(result.stdout).filter { it.id in ids }
        }
        return contexts.distinctBy { it.id }
    }

    override suspend fun manifest(hostId: String?, knownToken: String): RemoteManifest {
        ensureHelper(hostId)
        val result = manager.exec(
            "~/.local/bin/pocketagent-export-context manifest " + shellQuote(knownToken),
            300_000,
            hostId ?: this.hostId,
        )
        if (!result.ok) throw IllegalStateException("读取远端清单失败：" + result.summary(600))
        val lines = result.stdout.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull()?.split('\t').orEmpty()
        check(header.size >= 3 && header[0] == "pocketagent-manifest-v1") {
            "远端清单协议不兼容，请更新 tools/pocketagent-export-context 后重试"
        }
        val token = header[1]
        check(token.isNotBlank()) { "远端返回了空清单令牌，请更新同步脚本后重试" }
        if (header[2] == "unchanged") {
            check(lines.size == 1) { "远端返回了不完整的增量清单，请更新同步脚本后重试" }
            return RemoteManifest(token = token, unchanged = true)
        }
        check(header[2] == "changed") { "远端清单状态无效，请更新同步脚本后重试" }
        val contexts = SyncProtocol.parseDump(lines.drop(1).joinToString("\n"))
        return RemoteManifest(token = token, contexts = contexts)
    }

    override suspend fun fetch(hostId: String?, id: String): String {
        return fetchSnapshot(hostId, id, decodeMessages = false).body
    }

    override suspend fun fetchContext(hostId: String?, id: String): RemoteContext =
        fetchSnapshot(hostId, id, decodeMessages = true)

    private suspend fun fetchSnapshot(hostId: String?, id: String, decodeMessages: Boolean): RemoteContext {
        ensureHelper(hostId)
        val result = manager.exec(
            "~/.local/bin/pocketagent-export-context exportfull ${shellQuote(id)}",
            300_000,
            hostId ?: this.hostId,
        )
        if (!result.ok) throw IllegalStateException(result.summary(400))
        return SyncProtocol.parseDumpSequence(result.stdout, decodeMessages)
            .singleOrNull { it.id == id && (it.parsedMessages != null || it.body.isNotBlank()) }
            ?: throw IllegalStateException("读取完整会话失败，原内容及冲突已保留；请更新远端同步脚本后重试")
    }

    override suspend fun fetchAll(hostId: String?): Map<String, String> {
        return fetchContexts(hostId).associate { it.id to it.body }
    }

    override suspend fun fetchContexts(hostId: String?, ids: Set<String>?): List<RemoteContext> {
        val contexts = mutableListOf<RemoteContext>()
        visitContextsInternal(hostId, ids, consume = { contexts += it })
        return contexts
    }

    override suspend fun visitContexts(hostId: String?, ids: Set<String>, consume: (RemoteContext) -> Unit) =
        visitContextsInternal(hostId, ids, consume, decodeMessages = true)

    private suspend fun visitContextsInternal(
        hostId: String?, ids: Set<String>?, consume: (RemoteContext) -> Unit, decodeMessages: Boolean = false,
    ) {
        val commands = ids?.toList()?.chunked(12)?.map { batch ->
            "~/.local/bin/pocketagent-export-context exportbatch " + batch.joinToString(" ") { shellQuote(it) }
        } ?: listOf("POCKETAGENT_SYNC_LIST=0 ~/.local/bin/pocketagent-export-context dumpfull")
        commands.forEach { command ->
            val result = manager.exec(command, 300_000, hostId ?: this.hostId)
            if (!result.ok) throw IllegalStateException(result.summary(400))
            SyncProtocol.parseDumpSequence(result.stdout, decodeMessages)
                .filter { (it.parsedMessages != null || it.body.isNotBlank()) && (ids == null || it.id in ids) }
                .forEach(consume)
        }
    }

    override suspend fun push(hostId: String?, ctx: PhoneContext): String {
        val path = "${DIR}/${SyncProtocol.safeFileName(ctx.id)}.json"
        val shell = manager.shell(hostId ?: this.hostId)
        ShellRemoteFs(shell).writeAtomic(path, SyncProtocol.encodePhoneContext(ctx))
        return ctx.rev
    }

    override suspend fun restore(hostId: String?, id: String): Boolean {
        ensureHelper(hostId)
        val result = manager.exec(
            "~/.local/bin/pocketagent-export-context restore ${shellQuote(id)}",
            60_000,
            hostId ?: this.hostId,
        )
        if (!result.ok) throw IllegalStateException("恢复远端会话失败：" + result.summary(300))
        return !result.stdout.contains("没有这组")
    }

    override suspend fun listTrash(hostId: String?): List<TrashContext> {
        ensureHelper(hostId)
        val result = manager.exec(
            "~/.local/bin/pocketagent-export-context trash", 60_000, hostId ?: this.hostId,
        )
        if (!result.ok) throw IllegalStateException("读取回收站失败：" + result.summary(300))
        val listing = runCatching { AppJson.decodeFromString(TrashListing.serializer(), result.stdout) }.getOrNull()
        check(listing?.protocol == "pocketagent-trash-v1") {
            "远端回收站脚本版本不兼容，请更新 tools/pocketagent-export-context 后重试"
        }
        return listing.entries.distinctBy { it.id }
    }

    override suspend fun purgeTrash(hostId: String?, id: String) {
        ensureHelper(hostId)
        val result = manager.exec(
            "~/.local/bin/pocketagent-export-context purge ${shellQuote(id)}", 60_000, hostId ?: this.hostId,
        )
        if (!result.ok) throw IllegalStateException("永久删除失败：" + result.summary(300))
        val receipt = runCatching { AppJson.decodeFromString(TrashPurgeReceipt.serializer(), result.stdout) }.getOrNull()
        check(receipt?.protocol == "pocketagent-trash-v1" && receipt.deletedFiles >= 0) {
            "远端未确认永久删除，删除记录已保留，请更新同步脚本后重试"
        }
    }

    override suspend fun purgeTrashBatch(
        hostId: String?, ids: List<String>, onProgress: (Int, Int) -> Unit,
    ): TrashActionResult {
        ensureHelper(hostId)
        val selected = ids.distinct()
        val completed = mutableSetOf<String>()
        val errors = mutableMapOf<String, String>()
        var done = 0
        // 一般几百组只需一条命令；stdin 有 SSH 流控，不把大量 id 塞进 exec 请求包。
        for (batch in selected.chunked(512)) {
            try {
                val shell = manager.shell(hostId ?: this.hostId)
                val result = if (shell is RemoteInputShell) {
                    val input = AppJson.encodeToString(ListSerializer(String.serializer()), batch)
                    shell.execWithInput(
                        "~/.local/bin/pocketagent-export-context purgebatch --stdin",
                        input.toByteArray(Charsets.UTF_8), 60_000,
                    )
                } else {
                    shell.exec(
                        "~/.local/bin/pocketagent-export-context purgebatch " +
                            batch.joinToString(" ") { shellQuote(it) }, 60_000,
                    )
                }
                check(result.ok) { "批量永久删除失败（exit=${result.exitCode}），删除记录已保留，可重试" }
                val receipt = runCatching {
                    AppJson.decodeFromString(TrashBatchReceipt.serializer(), result.stdout)
                }.getOrNull()
                check(receipt?.protocol == "pocketagent-trash-batch-v1" &&
                    receipt.results.size == batch.size &&
                    receipt.results.map { it.id }.toSet() == batch.toSet() &&
                    receipt.results.all { it.deletedFiles >= 0 }) {
                    "远端未完整确认批量永久删除，删除记录已保留，请更新同步脚本后重试"
                }
                receipt.results.forEach { row ->
                    if (row.error == null) completed += row.id
                    else errors[row.id] = row.error
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                val detail = SshDiagnostics.describeChain(e)
                batch.forEach { errors[it] = detail }
            }
            done += batch.size
            onProgress(done, selected.size)
        }
        return TrashActionResult(completed = completed, errors = errors)
    }

    override suspend fun deleteRemote(hostId: String?, id: String) {
        ensureHelper(hostId)
        // 由 Mac 上的脚本处理：Codex rollout 移到 ~/.pocketagent/trash（可恢复），
        // 同时删掉手机推上去的 .json/.md 副本。
        // Mac 上进回收站（可恢复）；其它机器按用户要求永久删除
        val permanent = !labelFor(hostId).contains("Mac", ignoreCase = true)
        val flag = if (permanent) " --permanent" else ""
        val result = manager.exec(
            "~/.local/bin/pocketagent-export-context delete ${shellQuote(id)}$flag",
            60_000,
            hostId ?: this.hostId,
        )
        if (!result.ok) throw IllegalStateException("删除远端会话失败：" + result.summary(300))
    }

    /**
     * 推送完立刻把会话补成 Codex 原生 rollout：
     * 用户推完直接去电脑上 `codex resume <id>` 就能接上，不用等下一次同步。
     * 老版本脚本没有这个子命令，失败就算了，下次 dump 时也会补。
     */
    override suspend fun afterPush(hostId: String?, ids: Set<String>) {
        if (ids.isEmpty()) return
        ensureHelper(hostId)
        runCatching {
            ids.toList().chunked(128).forEach { batch ->
                manager.exec(
                    // 老版本脚本没有 rollout 子命令时会打印一整段会话正文，这里直接丢掉输出
                    "~/.local/bin/pocketagent-export-context rollout " +
                        batch.joinToString(" ") { shellQuote(it) } + " >/dev/null 2>&1 || true",
                    60_000,
                    hostId ?: this.hostId,
                )
            }
        }
    }

    companion object {
        const val DIR = "~/.pocketagent/sync/from-phone"
        const val HELPER_PATH = "~/.local/bin/pocketagent-export-context"
    }
}
