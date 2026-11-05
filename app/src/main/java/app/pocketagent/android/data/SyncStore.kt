package app.pocketagent.android.data

import kotlinx.serialization.Serializable
import java.io.File

/** 上一次成功同步时，同一个会话在两边各自的版本号（相当于 git 的 merge base）。 */
@Serializable
data class SyncBase(
    val localRev: String,
    val remoteRev: String,
)

/** 两边都改过的同一个会话，等用户选：保留本机 / 保留远端 / 两个都留。 */
@Serializable
data class SyncConflict(
    val id: String,
    val title: String,
    val localRev: String,
    val remoteRev: String,
    val remoteTitle: String = "",
    val remoteBody: String = "",
    val at: Long = System.currentTimeMillis(),
)

/**
 * 同步状态：`sync/state.json`。
 *
 * - [base]：上次同步后每个会话两边的版本号，用来做三方比较（本地改没改、远端改没改）。
 * - [deleted]：用户手动删掉的会话 id，删了就不再自动拉回来。
 * - [conflicts]：待处理的冲突，用户选完才生效。
 */
@Serializable
data class SyncState(
    val base: Map<String, SyncBase> = emptyMap(),
    val deleted: Map<String, Long> = emptyMap(),
    val conflicts: List<SyncConflict> = emptyList(),
    val lastSyncAt: Long = 0L,
    val lastHostName: String = "",
    /** 最近一次远端清单 token；未变化时整次同步只需传输一行。 */
    val remoteManifestToken: String = "",
)

class SyncStore(
    private val dir: File,
    private val cipher: StoreCipher? = null,
    scope: String? = null,
) {
    // 每个远端主机一份状态：base/conflicts/deleted 不能跨机器复用，否则会把上下文混在一起。
    private val file = if (scope.isNullOrBlank()) {
        File(dir, "sync/state.json")
    } else {
        File(dir, "sync/state-${scopeKey(scope)}.json")
    }
    private val backupFile = File(file.parentFile, file.name + ".bak")

    private fun decode(text: String): SyncState? =
        runCatching { AppJson.decodeFromString(SyncState.serializer(), text) }.getOrNull()

    @Synchronized
    fun load(): SyncState {
        readStore(file, cipher)?.let(::decode)?.let { return it }
        // 同步状态里有 base/删除墓碑/待处理冲突；损坏时当成空会把已删除会话重新拉回来。
        return readStore(backupFile, cipher)?.let(::decode) ?: SyncState()
    }

    @Synchronized
    fun save(state: SyncState) {
        runCatching { backupCurrent() }
        writeStore(file, AppJson.encodeToString(SyncState.serializer(), state), cipher)
    }

    private fun backupCurrent() {
        if (!file.exists() || file.length() == 0L) return
        val current = runCatching { readStore(file, cipher) }.getOrNull() ?: return
        if (decode(current) == null) return
        writeFileAtomically(backupFile, current)
    }

    @Synchronized
    fun update(block: (SyncState) -> SyncState): SyncState {
        val next = block(load())
        save(next)
        return next
    }

    /** 用户彻底删掉这些会话：手机上的会话由调用方删，这里只记住「别再同步回来」。 */
    @Synchronized
    fun markDeleted(ids: Collection<String>): SyncState = update { s ->
        val now = System.currentTimeMillis()
        s.copy(
            deleted = s.deleted + ids.associateWith { now },
            base = s.base - ids.toSet(),
            conflicts = s.conflicts.filterNot { it.id in ids },
            remoteManifestToken = "",
        )
    }

    /** 删错了：清掉「已删除」记录，下次同步会把还在远端的会话重新拉回来。 */
    @Synchronized
    fun clearDeleted(ids: Collection<String>): SyncState = update {
        it.copy(deleted = it.deleted - ids.toSet(), remoteManifestToken = "")
    }

    private fun scopeKey(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(24)
}
