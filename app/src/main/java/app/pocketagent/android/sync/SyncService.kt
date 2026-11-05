package app.pocketagent.android.sync

import kotlinx.coroutines.CancellationException

import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.ConversationStore
import app.pocketagent.android.data.SyncBase
import app.pocketagent.android.data.SyncConflict
import app.pocketagent.android.data.SyncState
import app.pocketagent.android.data.SyncStore
import kotlinx.serialization.Serializable

@Serializable
data class TrashContext(
    val id: String,
    val title: String = "",
    val deletedAt: Long = 0,
    val bytes: Long = 0,
)

data class TrashActionResult(
    val completed: Set<String> = emptySet(),
    val missing: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
)

interface SyncBackend {
    val label: String
    fun labelFor(hostId: String?): String = label
    suspend fun dump(hostId: String?): List<RemoteContext>
    /** 只取指定会话的清单；SSH 后端不应为了单条同步下载整库清单。 */
    suspend fun dumpItems(hostId: String?, ids: Set<String>): List<RemoteContext> =
        dump(hostId).filter { it.id in ids }
    /** 带清单 token 的增量读取；不变时 contexts 为空，无需传输每一组。 */
    suspend fun manifest(hostId: String?, knownToken: String): RemoteManifest =
        RemoteManifest(unchanged = false, contexts = dump(hostId))
    /** 拉某一组的完整正文（不截断）。 */
    suspend fun fetch(hostId: String?, id: String): String

    /** 正文和版本必须属于同一快照。实际 SSH 后端用单个 exportfull 请求实现。 */
    suspend fun fetchContext(hostId: String?, id: String): RemoteContext {
        val ctx = dump(hostId).firstOrNull { it.id == id }
            ?: throw IllegalStateException("远端找不到这组会话，冲突已保留")
        return ctx.copy(body = ctx.body.ifBlank { fetch(hostId, id) })
    }

    /** 一次拿回全部正文（gzip 批量），首次导入很多组时用。 */
    suspend fun fetchAll(hostId: String?): Map<String, String> = emptyMap()
    suspend fun fetchContexts(hostId: String?, ids: Set<String>? = null): List<RemoteContext> {
        val bodies = fetchAll(hostId)
        return dump(hostId).mapNotNull { ctx ->
            if (ids != null && ctx.id !in ids) null
            else bodies[ctx.id]?.takeIf { it.isNotBlank() }?.let { ctx.copy(body = it) }
        }
    }
    /** 批量传输、逐组消费；SSH 实现只保留当前一组解压后的正文。 */
    suspend fun visitContexts(hostId: String?, ids: Set<String>, consume: (RemoteContext) -> Unit) {
        fetchContexts(hostId, ids).forEach(consume)
    }
    /** 推到远端，返回远端记录的版本号（正常就是 ctx.rev）。 */
    suspend fun push(hostId: String?, ctx: PhoneContext): String
    /** 从 Mac 回收站把会话搬回去；回收站被清理过返回 false */
    suspend fun restore(hostId: String?, id: String): Boolean = false
    suspend fun listTrash(hostId: String?): List<TrashContext> =
        throw IllegalStateException("远端同步脚本不支持管理回收站，请更新脚本")
    suspend fun purgeTrash(hostId: String?, id: String) {
        throw IllegalStateException("远端同步脚本不支持永久删除，请更新脚本")
    }
    /** 可批量实现，减少 SSH 往返；替身与旧后端保留逐组错误和取消语义。 */
    suspend fun purgeTrashBatch(
        hostId: String?, ids: List<String>, onProgress: (Int, Int) -> Unit,
    ): TrashActionResult {
        val completed = mutableSetOf<String>()
        val errors = mutableMapOf<String, String>()
        ids.forEachIndexed { index, id ->
            try {
                purgeTrash(hostId, id)
                completed += id
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                errors[id] = app.pocketagent.android.remote.SshDiagnostics.describeChain(e)
            }
            onProgress(index + 1, ids.size)
        }
        return TrashActionResult(completed = completed, errors = errors)
    }

    suspend fun deleteRemote(hostId: String?, id: String)

    /**
     * 一批推送结束后的收尾（例如把会话落成 Codex 原生记录）。
     * 默认什么都不做；失败不能影响同步结果。
     */
    suspend fun afterPush(hostId: String?, ids: Set<String>) {}
}

data class RemoteManifest(
    val token: String = "",
    val unchanged: Boolean = false,
    val contexts: List<RemoteContext> = emptyList(),
)

data class SyncOutcome(
    val localCount: Int = 0,
    val remoteCount: Int = 0,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val unchanged: Int = 0,
    val skippedDeleted: Int = 0,
    val hostName: String = "",
    val summary: String = "",
)

/**
 * 双向同步：手机所有会话 ⇄ 远端所有会话。
 * 每个 id 一组，独立导入成独立会话，不会混进当前对话。
 */
class SyncService(
    private val store: SyncStore,
    private val conversations: ConversationStore,
    private val backend: SyncBackend,
    private val storeForHost: ((String?) -> SyncStore)? = null,
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()

    private fun stateStore(hostId: String?): SyncStore =
        if (hostId.isNullOrBlank()) store else storeForHost?.invoke(hostId) ?: store

    fun state(hostId: String? = null): SyncState = stateStore(hostId).load()

    fun conflicts(hostId: String? = null): List<SyncConflict> = stateStore(hostId).load().conflicts

    suspend fun sync(hostId: String? = null, onProgress: (String) -> Unit = {}): SyncOutcome {
        mutex.lock()
        try {
            return syncLocked(hostId, onProgress, onlyIds = null)
        } finally {
            mutex.unlock()
        }
    }

    /** 只同步一条；不下载整库清单，也不处理其它会话。 */
    suspend fun syncOne(hostId: String?, id: String, onProgress: (String) -> Unit = {}): SyncOutcome {
        mutex.lock()
        try {
            return syncLocked(hostId, onProgress, onlyIds = setOf(id))
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 首次在远端使用 Codex harness 前，把手机已有正文落成同 id 的原生 rollout。
     *
     * 这样首轮也能用 `exec resume` 读到完整历史，而不是只把一段截断摘要塞进新会话；
     * 同时电脑和手机保持同一个 session id，之后同步不会冒出两条重复对话。
     *
     * 只做“远端没有时才推送”。远端已经有同 id 会话时以远端为准，不覆盖。
     */
    suspend fun ensureNativeThread(hostId: String, conversation: Conversation): String {
        require(hostId.isNotBlank()) { "远端 Codex harness 缺少主机 id" }
        val id = conversation.id
        mutex.lock()
        try {
            val state = stateStore(hostId).load()
            check(id !in state.deleted) { "这组会话已在同步目标上删除；请先选择另一组或恢复它" }
            val exists = backend.dumpItems(hostId, setOf(id)).any { it.id == id }
            if (!exists) {
                val rev = backend.push(hostId, SyncProtocol.toPhoneContext(conversation))
                stateStore(hostId).update { current ->
                    current.copy(
                        base = current.base + (id to SyncBase(
                            SyncProtocol.revOf(conversation.messages),
                            rev,
                        )),
                        remoteManifestToken = "",
                    )
                }
            }
            // 兼容旧副本：即使会话文件已经存在，也补一次 rollout，修好之前只同步了 json 的机器。
            runCatching { backend.afterPush(hostId, setOf(id)) }
            return id
        } finally {
            mutex.unlock()
        }
    }

    /**
     * 远端 harness 完成后，同一轮已经写进远端原生 rollout，也拿到了手机里的最终正文。
     * 只更新 merge base，避免下一次同步把两边各自“新增”的同一轮误判成冲突。
     */
    suspend fun rebaseAfterRemoteTurn(hostId: String, conversation: Conversation): Boolean {
        if (hostId.isBlank()) return false
        mutex.lock()
        try {
            val remote = backend.dumpItems(hostId, setOf(conversation.id))
                .firstOrNull { it.id == conversation.id } ?: return false
            stateStore(hostId).update { current ->
                current.copy(
                    base = current.base + (conversation.id to SyncBase(
                        SyncProtocol.revOf(conversation.messages),
                        remote.rev,
                    )),
                    remoteManifestToken = "",
                )
            }
            return true
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // 基线对齐失败不影响本轮回答；下次同步会正常比较，必要时让用户选。
            return false
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun syncLocked(
        hostId: String?, onProgress: (String) -> Unit, onlyIds: Set<String>?,
    ): SyncOutcome {
        onProgress(if (onlyIds == null) "正在读取远端清单指纹…" else "正在读取这条会话的远端版本…")
        val hostState = stateStore(hostId).load()
        // 「共同删除」是全局的：在 A 机器上删掉的，换到 B 机器同步时也不能再拉回来。
        val globalDeleted = if (hostId.isNullOrBlank()) emptyMap() else store.load().deleted
        val state = hostState.copy(deleted = hostState.deleted + globalDeleted)
        var remoteToken = ""
        val manifest = try {
            if (onlyIds == null) {
                backend.manifest(hostId, state.remoteManifestToken)
            } else {
                RemoteManifest(contexts = backend.dumpItems(hostId, onlyIds))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 带上阶段名：用户看到的「同步失败」要能直接指向断在哪一步
            throw IllegalStateException(
                "拉取远端清单失败：" + app.pocketagent.android.remote.SshDiagnostics.describeChain(e),
                e,
            )
        }
        remoteToken = manifest.token
        val allLocal = conversations.load()
        val local = if (onlyIds == null) allLocal else allLocal.filter { it.id in onlyIds }
        val remote = if (manifest.unchanged) unchangedRemote(local, state) else manifest.contexts
        if (onlyIds != null && local.isEmpty() && remote.isEmpty()) {
            throw IllegalStateException("远端和手机上都找不到这条会话")
        }
        var plan = SyncPlanner.plan(local, remote, state)
        val pendingConflictIds = state.conflicts.map { it.id }.toSet()
        if (manifest.unchanged && pendingConflictIds.isNotEmpty()) {
            // 清单克隆自 merge base，不能让「远端没变」掩盖尚未处理的冲突。
            plan = plan.copy(
                pulls = plan.pulls.filterNot { it.id in pendingConflictIds },
                pushes = plan.pushes.filterNot { it.id in pendingConflictIds },
                conflicts = state.conflicts.filter { conflict ->
                    conflict.id in local.map { it.id } && conflict.id !in state.deleted
                },
            )
        }
        onProgress("清单已读取：需要拉取 ${plan.pulls.size} 组，推送 ${plan.pushes.size} 组，冲突 ${plan.conflicts.size} 组")

        val base = state.base.toMutableMap()
        val resolvedIds = mutableSetOf<String>()
        // 每次已完成的传输都记住进度；后面的某一组断线，不应让前面全部白做。
        fun checkpoint() {
            stateStore(hostId).update { current ->
                current.copy(
                    base = current.base + base,
                    conflicts = current.conflicts.filterNot { it.id in resolvedIds },
                )
            }
        }
        val changedIds = (plan.pulls.map { it.id } + plan.pushes.map { it.id } +
            plan.conflicts.map { it.id }).toSet()
        val localById = local.associateBy { it.id }
        remote.filter { it.id !in changedIds && it.id !in state.deleted }.forEach { ctx ->
            localById[ctx.id]?.let { conv ->
                base[ctx.id] = SyncBase(SyncProtocol.revOf(conv.messages), ctx.rev)
                resolvedIds += ctx.id
            }
        }

        // 1) 拉：远端有、本机没有 / 只有远端变了 → 落成独立会话
        val pulled = mutableListOf<Conversation>()
        val pulledRemoteRevs = mutableMapOf<String, String>()
        val skipped = mutableListOf<String>()
        fun savePulls() {
            if (pulled.isNotEmpty()) {
                onProgress("正在保存 ${pulled.size} 组完整会话…")
                conversations.upsertAll(pulled)
            }
            pulled.forEach { conv ->
                base[conv.id] = SyncBase(SyncProtocol.revOf(conv.messages), pulledRemoteRevs.getValue(conv.id))
                resolvedIds += conv.id
            }
            checkpoint()
        }
        // 每批解析完就释放原始正文，不能同时持有「全部导出正文 + 全部解析对象」。
        // 627 组真实历史约 62 MB；再一次性序列化会造成手机内存溢出。
        try {
            plan.pulls.chunked(12).forEachIndexed { batchIndex, batch ->
                val needBody = batch.filter { it.body.isBlank() }
                onProgress("正在压缩传输会话 ${batchIndex * 12 + 1}–${batchIndex * 12 + batch.size}/${plan.pulls.size}…")
                fun consume(snapshot: RemoteContext) {
                    val conv = preserveLocalMetadata(
                        SyncProtocol.toConversation(snapshot, source = "mac"),
                        localById[snapshot.id],
                    )
                    if (conv.messages.isEmpty() || (snapshot.count > 0 && conv.messages.size != snapshot.count)) {
                        skipped += snapshot.title.ifBlank { snapshot.id.take(8) } +
                            "（正文不完整：远端 ${snapshot.count} 条，解析出 ${conv.messages.size} 条）"
                    } else {
                        pulled += conv
                        pulledRemoteRevs[snapshot.id] = snapshot.rev
                    }
                }
                batch.filter { it.body.isNotBlank() }.forEach(::consume)
                val remaining = needBody.map { it.id }.toMutableSet()
                if (plan.pulls.size > 20 && remaining.isNotEmpty()) {
                    try {
                        backend.visitContexts(hostId, remaining.toSet()) { snapshot ->
                            if (snapshot.id in remaining && (snapshot.parsedMessages != null || snapshot.body.isNotBlank())) {
                                consume(snapshot)
                                remaining.remove(snapshot.id)
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        app.pocketagent.android.remote.SshDiagnostics.record(
                            "批量拉正文失败，改为逐组拉：" +
                                app.pocketagent.android.remote.SshDiagnostics.describeChain(e),
                        )
                    }
                }
                // 缺失/失败的部分逐组补拉，成功的部分不重传，也不同时持有多组原始正文。
                needBody.filter { it.id in remaining }.forEach { ctx ->
                    var readError: String? = null
                    val snapshot = try {
                        backend.fetchContext(hostId, ctx.id)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        readError = app.pocketagent.android.remote.SshDiagnostics.describeChain(e)
                        null
                    }
                    if (snapshot != null && (snapshot.parsedMessages != null || snapshot.body.isNotBlank())) consume(snapshot) else {
                        skipped += ctx.title.ifBlank { ctx.id.take(8) } +
                            (readError?.let { "（${it.take(240)}）" } ?: "（远端正文为空）")
                    }
                }
            }
        } catch (e: CancellationException) {
            // 用户停止同步时，已完整验证的批次和 base 一起保存，重试不从头下载。
            savePulls()
            throw e
        }
        savePulls()

        // 2) 推：本机有、远端没有 / 只有本机变了
        plan.pushes.forEachIndexed { index, conv ->
            onProgress("正在上传会话 ${index + 1}/${plan.pushes.size}…（之前完成的进度已保存）")
            val ctx = SyncProtocol.toPhoneContext(conv)
            val remoteRev = try {
                backend.push(hostId, ctx)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                throw IllegalStateException("上传第 ${index + 1}/${plan.pushes.size} 组失败（已完成的进度已保存）", e)
            }
            base[conv.id] = SyncBase(
                localRev = SyncProtocol.revOf(conv.messages),
                remoteRev = remoteRev,
            )
            resolvedIds += conv.id
            checkpoint()
        }
        if (plan.pushes.isNotEmpty()) {
            runCatching { backend.afterPush(hostId, plan.pushes.map { it.id }.toSet()) }
        }

        // 3) 冲突：只记录，不动内容，等用户选
        // 冲突卡只需清单；处理时再读完整快照，避免为几十组冲突提前重复下载正文。
        val conflicts = plan.conflicts
        stateStore(hostId).save(
            state.copy(
                base = base,
                conflicts = mergeConflicts(
                    // 清单可能有窗口限制，未出现不能当作远端删除；只清理已成功对齐的组。
                    state.conflicts.filterNot { it.id in resolvedIds || it.id in state.deleted },
                    conflicts,
                ),
                lastSyncAt = System.currentTimeMillis(),
                lastHostName = backend.labelFor(hostId),
                remoteManifestToken = when {
                    onlyIds == null && plan.pushes.isEmpty() && skipped.isEmpty() -> remoteToken
                    onlyIds != null && plan.pushes.isEmpty() && plan.pulls.isEmpty() && skipped.isEmpty() ->
                        state.remoteManifestToken
                    else -> ""
                },
            ),
        )

        val pulledIds = pulled.map { it.id }.toSet()
        val pushedIds = plan.pushes.map { it.id }.toSet()
        val lines = buildList {
            if (plan.pulls.isNotEmpty()) add("⬇︎ 拉取 ${pulled.size}/${plan.pulls.size} 组（各自成为独立会话）")
            if (plan.pushes.isNotEmpty()) add("⬆︎ 推送 ${plan.pushes.size} 组到 ${backend.labelFor(hostId)}")
            if (conflicts.isNotEmpty()) add("⚠︎ ${conflicts.size} 组冲突，等你选保留哪边")
            add("本机 ${local.size} 组 ⇄ 远端 ${remote.size} 组，不变 ${plan.unchanged} 组" + if (state.deleted.isNotEmpty()) "（含 ${state.deleted.size} 组已删除）" else "")
            if (pulledIds.isNotEmpty()) add("新会话：\n" + pulled.joinToString("\n") { "· ${it.title.ifBlank { it.id.take(8) }}" })
            if (pushedIds.isNotEmpty()) {
                add(
                    "已推送（电脑上接着聊：codex resume <会话id>）：\n" +
                        plan.pushes.joinToString("\n") { "· ${it.title}\n  codex resume ${it.id}" },
                )
            }
            if (skipped.isNotEmpty()) {
                add("⚠︎ ${skipped.size} 组过大/读取失败/正文不完整，本次跳过（远端原文件保持不变，不会被覆盖）：\n" +
                    skipped.joinToString("\n") { "· $it" })
            }
        }

        return SyncOutcome(
            localCount = local.size,
            remoteCount = remote.size,
            pulled = pulled.size,
            pushed = plan.pushes.size,
            conflicts = conflicts.size,
            unchanged = plan.unchanged,
            skippedDeleted = state.deleted.size,
            hostName = backend.labelFor(hostId),
            summary = lines.joinToString("\n"),
        )
    }

    private fun unchangedRemote(local: List<Conversation>, state: SyncState): List<RemoteContext> {
        val localById = local.associateBy { it.id }
        val conflictById = state.conflicts.associateBy { it.id }
        return (state.base.keys + conflictById.keys).asSequence()
            .filterNot { it in state.deleted }
            .distinct()
            .mapNotNull { id ->
                val baseRev = state.base[id]?.remoteRev
                val conflict = conflictById[id]
                val rev = baseRev ?: conflict?.remoteRev ?: return@mapNotNull null
                val localConversation = localById[id]
                RemoteContext(
                    id = id,
                    title = conflict?.remoteTitle ?: localConversation?.title.orEmpty(),
                    updatedAt = localConversation?.updatedAt ?: 0,
                    rev = rev,
                    body = "",
                )
            }
            .toList()
    }

    /** 冲突的三种处理方式。 */
    enum class Choice { LOCAL, REMOTE, BOTH }

    suspend fun resolve(conflictId: String, choice: Choice, hostId: String? = null): String {
        mutex.lock()
        try {
            return resolveLocked(conflictId, choice, hostId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun resolveLocked(conflictId: String, choice: Choice, hostId: String?): String {
        val state = stateStore(hostId).load()
        val conflict = state.conflicts.firstOrNull { it.id == conflictId }
            ?: throw IllegalStateException("这组冲突已不在待处理列表，请刷新后重试")
        val local = conversations.load().firstOrNull { it.id == conflict.id }
            ?: throw IllegalStateException("本机找不到这组会话，请重新同步后重试")

        val base = state.base.toMutableMap()
        when (choice) {
            Choice.LOCAL -> {
                val rev = backend.push(hostId, SyncProtocol.toPhoneContext(local))
                base[local.id] = SyncBase(SyncProtocol.revOf(local.messages), rev)
            }

            Choice.REMOTE -> {
                // 冲突记录可能没带正文（同步时正文没取全）；点「保留远端」时再补拉一次，
                // 否则会直接返回、用户看起来就是「点了没反应」。
                val remote = remoteForResolution(conflict, local, hostId)
                val fresh = preserveLocalMetadata(validatedConversation(remote), local)
                conversations.upsert(fresh)
                base[conflict.id] = SyncBase(
                    SyncProtocol.revOf(fresh.messages),
                    remote.rev,
                )
            }

            Choice.BOTH -> {
                val remote = remoteForResolution(conflict, local, hostId)
                val fresh = validatedConversation(remote)
                // 副本用真正的 UUID：脚本按 UUID 落 Codex 原生会话，排序/去重也走正常路径；
                // 以前用 "<id>-rXXXX" 会被当成非会话 id，下次同步可能又冒出冲突。
                val copyId = java.util.UUID.nameUUIDFromBytes(
                    "${hostId.orEmpty()}|${conflict.id}|${remote.rev}".toByteArray(Charsets.UTF_8),
                ).toString()
                val copy = fresh.copy(
                    id = copyId,
                    title = conflict.remoteTitle.ifBlank { "${conflict.title}（远端）" },
                    // 用当前时间，避免副本因为时间戳太老掉出远端「最近 N 组」的窗口
                    updatedAt = System.currentTimeMillis(),
                )
                // 先保存远端副本，再覆盖原 id；断线重试使用同一 UUID，不会产生重复副本。
                conversations.upsert(copy)
                val copyRev = backend.push(hostId, SyncProtocol.toPhoneContext(copy))
                base[copyId] = SyncBase(SyncProtocol.revOf(copy.messages), copyRev)
                stateStore(hostId).update {
                    it.copy(base = it.base + base, remoteManifestToken = "")
                }
                val rev = backend.push(hostId, SyncProtocol.toPhoneContext(local))
                base[local.id] = SyncBase(SyncProtocol.revOf(local.messages), rev)
            }
        }

        stateStore(hostId).update { s ->
            s.copy(
                base = base,
                conflicts = s.conflicts.filterNot { it.id == conflictId },
                remoteManifestToken = "",
            )
        }
        return when (choice) {
            Choice.LOCAL -> "已保留本机版本：${local.title}"
            Choice.REMOTE -> "已换成远端版本：${conflict.remoteTitle.ifBlank { local.title }}"
            Choice.BOTH -> "两个都留了：${local.title} + ${conflict.remoteTitle.ifBlank { "远端副本" }}"
        }
    }

    private suspend fun remoteForResolution(conflict: SyncConflict, local: Conversation, hostId: String?): RemoteContext {
        val remote = backend.fetchContext(hostId, conflict.id)
        if (remote.parsedMessages == null && remote.body.isBlank()) {
            throw IllegalStateException("远端正文为空，冲突已保留，可重新同步后重试")
        }
        val messages = remote.parsedMessages ?: SyncProtocol.parseMessages(remote.body)
        if (remote.count > 0 && messages.size != remote.count) {
            // 极旧的无标记格式会把正文里的「## user」误当消息边界。
            // 只有手机完整版本/前缀的内容哈希及条数都与远端完全相同，才可重建格式。
            // 读失败、空正文、哈希不同均不能以本地内容代替用户选择的远端内容。
            val candidate = local.copy(messages = local.messages.filter {
                it.role == app.pocketagent.android.data.Role.USER || it.role == app.pocketagent.android.data.Role.ASSISTANT
            }.take(remote.count))
            val ctx = SyncProtocol.toPhoneContext(candidate)
            if (ctx.count == remote.count && ctx.rev == remote.rev) {
                val repaired = ctx.copy(title = remote.title, startedAt = remote.startedAt, updatedAt = remote.updatedAt)
                val rev = backend.push(hostId, repaired)
                // 流式下载可能已缓存旧格式误拆出来的消息，修复正文时必须一并替换。
                return remote.copy(body = repaired.body, rev = rev, parsedMessages = candidate.messages)
            }
        }
        return remote.copy(parsedMessages = messages)
    }

    private fun validatedConversation(remote: RemoteContext): Conversation {
        val conv = SyncProtocol.toConversation(remote, source = "mac")
        check(conv.messages.isNotEmpty() && (remote.count <= 0 || conv.messages.size == remote.count)) {
            "远端正文为空或不完整（预期 ${remote.count} 条，解析 ${conv.messages.size} 条），冲突已保留"
        }
        return conv
    }

    /**
     * 远端正文更新时，保留只属于手机侧的设置和远端 session 绑定。
     * 否则“保留远端/拉取新内容”会把当前模型、目标和 `/workspace` 的原生会话绑定清空。
     */
    private fun preserveLocalMetadata(fresh: Conversation, local: Conversation?): Conversation {
        if (local == null) return fresh
        return fresh.copy(
            providerId = fresh.providerId ?: local.providerId,
            goal = fresh.goal ?: local.goal,
            goalPaused = if (fresh.goal.isNullOrBlank()) local.goalPaused else fresh.goalPaused,
            remoteThreadId = fresh.remoteThreadId ?: local.remoteThreadId,
            remoteHostId = fresh.remoteHostId ?: local.remoteHostId,
            // 工作区是手机侧的会话设置，不随远端正文覆盖。
            workspaceHostId = local.workspaceHostId,
            workspaceDir = local.workspaceDir,
        )
    }

    /** 手动删：手机上的删掉，并在 Mac 上撤掉推送过的副本；之后不再同步回来。 */
    suspend fun deleteConversations(ids: Collection<String>, hostId: String? = null): Int {
        if (ids.isEmpty()) return 0
        mutex.lock()
        try {
            return deleteConversationsLocked(ids, hostId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun deleteConversationsLocked(ids: Collection<String>, hostId: String?): Int {
        var removed = 0
        val rmFailed = mutableListOf<String>()
        // 先保存删除记录；网络中断或取消也不能把已删的历史重新拉回。
        stateStore(hostId).markDeleted(ids)
        if (!hostId.isNullOrBlank()) store.markDeleted(ids)
        ids.forEach { id ->
            val existed = conversations.delete(id).none { it.id == id }
            if (existed) removed++
            // 不管有没有 base 都尽力删远端；远端本来就没有时脚本会返回「没有这组副本」
            val r = runCatching { backend.deleteRemote(hostId, id) }
                .onFailure { if (it is CancellationException) throw it }
            if (r.isFailure) rmFailed += id
        }
        if (rmFailed.isNotEmpty()) {
            throw IllegalStateException("手机上已删掉，但远端 ${rmFailed.size} 组副本没删成功（检查 SSH 连接）")
        }
        return removed
    }

    /**
     * 删错了：先让 Mac 把回收站里的会话搬回去，再清掉删除记录，
     * 下次同步就重新拉回来。回收站被清过的会跳过（返回 notFound）。
     */
    suspend fun listTrash(hostId: String? = null): List<TrashContext> = backend.listTrash(hostId)

    /** 只恢复明确选中的组；缺失、失败、未选中的删除记录都必须保留。 */
    suspend fun restoreDeleted(
        ids: Collection<String>, hostId: String? = null, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): TrashActionResult = actOnTrash(ids, hostId, permanent = false, onProgress)

    /** 只清理回收站中的文件，保留全局及目标机删除墓碑，禁止自动重新导入。 */
    suspend fun permanentlyDelete(
        ids: Collection<String>, hostId: String? = null, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): TrashActionResult = actOnTrash(ids, hostId, permanent = true, onProgress)

    private suspend fun actOnTrash(
        ids: Collection<String>, hostId: String?, permanent: Boolean, onProgress: (Int, Int) -> Unit,
    ): TrashActionResult {
        mutex.lock()
        try {
            val completed = mutableSetOf<String>()
            val missing = mutableSetOf<String>()
            val errors = mutableMapOf<String, String>()
            val target = stateStore(hostId)
            if (permanent) {
                target.markDeleted(ids)
                if (!hostId.isNullOrBlank()) store.markDeleted(ids)
            }
            val selected = ids.distinct()
            onProgress(0, selected.size)
            if (permanent) {
                return try {
                    backend.purgeTrashBatch(hostId, selected, onProgress)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    TrashActionResult(errors = selected.associateWith {
                        app.pocketagent.android.remote.SshDiagnostics.describeChain(e)
                    })
                }
            }
            selected.forEachIndexed { index, id ->
                try {
                    if (backend.restore(hostId, id)) {
                        // 每组成功后立即落盘，后续断线不影响已恢复的组。
                        target.clearDeleted(listOf(id))
                        if (!hostId.isNullOrBlank()) store.clearDeleted(listOf(id))
                        completed += id
                    } else {
                        missing += id
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    errors[id] = app.pocketagent.android.remote.SshDiagnostics.describeChain(e)
                }
                onProgress(index + 1, selected.size)
            }
            return TrashActionResult(completed, missing, errors)
        } finally {
            mutex.unlock()
        }
    }

    private fun mergeConflicts(old: List<SyncConflict>, fresh: List<SyncConflict>): List<SyncConflict> {
        val map = old.associateBy { it.id }.toMutableMap()
        fresh.forEach { map[it.id] = it }
        return map.values.sortedBy { it.at }
    }
}
