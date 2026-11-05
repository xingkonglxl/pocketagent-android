package app.pocketagent.android.remote

/**
 * 每次 exec 都向 [RemoteManager] 要当前连接（内部会复用），
 * 这样断线重连、切换主机都不用重建工具对象。
 */
class LazyRemoteShell(
    private val manager: RemoteManager,
    private val hostId: String?,
) : RemoteShell {

    override suspend fun exec(command: String, timeoutMs: Long): RemoteResult =
        manager.exec(command, timeoutMs, hostId)

    /** 连接归 manager 管，这里不动。 */
    override suspend fun close() = Unit
}
