package app.pocketagent.android.sync

import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.SyncConflict
import app.pocketagent.android.data.SyncState

/**
 * 一次同步要做的事。三方向比较（merge base = [SyncState.base]）：
 * - 只有本机变了 → 推给远端
 * - 只有远端变了 → 拉成一条独立会话
 * - 两边都变了 → 冲突，等用户选
 *
 * 每组会话始终按 id 独立处理，绝不合并两个窗口的内容。
 */
data class SyncPlan(
    val pulls: List<RemoteContext> = emptyList(),
    val pushes: List<Conversation> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
    val unchanged: Int = 0,
) {
    val isEmpty: Boolean get() = pulls.isEmpty() && pushes.isEmpty() && conflicts.isEmpty()
}

object SyncPlanner {

    fun plan(
        local: List<Conversation>,
        remote: List<RemoteContext>,
        state: SyncState,
    ): SyncPlan {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }
        val pulls = mutableListOf<RemoteContext>()
        val pushes = mutableListOf<Conversation>()
        val conflicts = mutableListOf<SyncConflict>()
        var unchanged = 0

        (localById.keys + remoteById.keys).sorted().forEach { id ->
            val l = localById[id]
            val r = remoteById[id]
            val base = state.base[id]
            val tombstoned = id in state.deleted

            // 用户手动删掉的：不再自动拉回来
            if (tombstoned) {
                unchanged++
                return@forEach
            }

            if (l == null) {
                if (r != null) pulls += r
                return@forEach
            }
            if (r == null) {
                // 远端列表窗口不包含它，不代表它已删除；待处理冲突不能被自动推送覆盖。
                val pending = state.conflicts.firstOrNull { it.id == id }
                if (pending != null) conflicts += pending else pushes += l
                return@forEach
            }

            val localRev = SyncProtocol.revOf(l.messages)
            if (base == null) {
                // 第一次遇到同一个 id：内容一样就只记个 base，不一样才让用户选
                if (localRev == r.rev || sameContent(l, r)) {
                    unchanged++
                } else {
                    conflicts += conflictOf(l, r, localRev)
                }
                return@forEach
            }

            val localChanged = localRev != base.localRev
            val remoteChanged = r.rev != base.remoteRev
            when {
                !localChanged && !remoteChanged -> unchanged++
                localChanged && !remoteChanged -> pushes += l
                !localChanged && remoteChanged -> pulls += r
                localRev == r.rev -> unchanged++
                else -> conflicts += conflictOf(l, r, localRev)
            }
        }

        return SyncPlan(pulls = pulls, pushes = pushes, conflicts = conflicts, unchanged = unchanged)
    }

    private fun conflictOf(l: Conversation, r: RemoteContext, localRev: String) = SyncConflict(
        id = l.id,
        title = l.title,
        localRev = localRev,
        remoteRev = r.rev,
        remoteTitle = r.title,
        remoteBody = r.body,
    )

    /** 第一次相遇时用来判断「内容是否其实一样」，避免因为空行/格式差异误报冲突。 */
    private fun sameContent(l: Conversation, r: RemoteContext): Boolean {
        val localText = l.messages
            .filter { it.role == app.pocketagent.android.data.Role.USER || it.role == app.pocketagent.android.data.Role.ASSISTANT }
            .joinToString("\n") { it.text.trim() }
        val remoteText = SyncProtocol.parseMessages(r.body)
            .joinToString("\n") { it.text.trim() }
        return localText.isNotBlank() && localText == remoteText
    }
}
