package app.pocketagent.android.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ApprovalRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    /**
     * 是否允许“本次会话都允许”。危险工具和敏感路径的请求必须是 false，
     * 这样一次宽泛授权不会把后续 shell/删除/远端写入也一起放行。
     */
    val sessionAllowable: Boolean = false,
)

interface ApprovalGate {
    suspend fun request(request: ApprovalRequest): Boolean
}

object AutoApproveGate : ApprovalGate {
    override suspend fun request(request: ApprovalRequest) = true
}

object AutoDenyGate : ApprovalGate {
    override suspend fun request(request: ApprovalRequest) = false
}

/** UI 用的审批闸门：挂起等待用户点「允许/拒绝」。 */
class UiApprovalGate : ApprovalGate {
    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending

    // 多个会话同时要审批时排队，一次只弹一个，避免互相覆盖
    private val mutex = Mutex()

    @Volatile
    private var waiter: CompletableDeferred<Boolean>? = null

    /** 用户点了「本次会话都允许」后，后续低风险工具不再弹窗。进程重启即失效。 */
    @Volatile
    var autoAllowForSession: Boolean = false
        private set

    override suspend fun request(request: ApprovalRequest): Boolean = mutex.withLock {
        if (autoAllowForSession && request.sessionAllowable) return@withLock true
        val deferred = CompletableDeferred<Boolean>()
        waiter = deferred
        _pending.value = request
        try {
            deferred.await()
        } finally {
            _pending.value = null
            waiter = null
        }
    }

    fun answer(allow: Boolean) {
        waiter?.complete(allow)
    }

    /** 允许当前这次，并放行本次会话后续可批量批准的请求。 */
    fun allowAllForSession() {
        if (_pending.value?.sessionAllowable != true) return
        autoAllowForSession = true
        waiter?.complete(true)
    }

    /** 手动恢复逐个审批（设置页/暂停时用）。 */
    fun resetSessionAutoAllow() {
        autoAllowForSession = false
    }

    fun cancelAll() {
        waiter?.complete(false)
    }
}
