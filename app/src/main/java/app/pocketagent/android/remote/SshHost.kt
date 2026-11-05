package app.pocketagent.android.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

/**
 * 一台可远程执行命令的机器（remote-host、Mac、Orin 都行）。
 *
 * [privateKeyPem] 与 [passphrase] 标了 `@Transient`：写进 JSON 的只有连接信息，
 * 私钥本体交给 Keystore（`SecretStore`）保管，用时通过 [withKey] 注入。
 *
 * [jumpHost] 非空时走 ProxyJump：先连跳板，再从跳板开 direct-tcpip 到目标机。
 */
@Serializable
data class SshHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String,
    val port: Int = 22,
    val user: String,
    val jumpHost: String? = null,
    val jumpPort: Int = 22,
    val jumpUser: String? = null,
    @Transient val privateKeyPem: String = "",
    @Transient val passphrase: String? = null,
    val workDir: String = "~",
    /** 这台机器上的工作区目录（fs_* 工具、/export、/remember、同步共用） */
    val workspaceDir: String = "~/pocketagent",
    /** 选择工作区时实际检测，null 表示旧配置尚未检测；与主机名称无关。 */
    val codexHarnessAvailable: Boolean? = null,
    /** 留空自动查找；非常规安装可指定绝对路径或 ~/ 路径，不执行 shell 表达式。 */
    val codexExecutable: String = "",
    val enabled: Boolean = true,
) {
    /** `user@host:port`，用于日志和 UI。 */
    val target: String get() = "$user@$host:$port"

    val viaJump: Boolean get() = !jumpHost.isNullOrBlank()

    val hasKey: Boolean get() = privateKeyPem.isNotBlank()

    /** 把 Keystore 里取出来的私钥塞回来，得到可直接连的对象。 */
    fun withKey(privateKeyPem: String, passphrase: String? = null): SshHost =
        copy(privateKeyPem = privateKeyPem, passphrase = passphrase)

    /** 去掉密钥，落盘前再兜一层底。 */
    fun withoutSecret(): SshHost = copy(privateKeyPem = "", passphrase = null)

    fun label(): String = name.ifBlank { host }
}

/** Shared by Save and Test, including callers outside the Compose editor. */
internal fun SshHost.configurationError(): String? = when {
    host.isBlank() || user.isBlank() -> "请填写主机地址和用户名"
    port !in 1..65535 || (viaJump && jumpPort !in 1..65535) -> "端口必须在 1–65535 之间"
    host.any { it.isWhitespace() } || "://" in host || '/' in host -> "主机只填写域名或 IP，不要填写协议、路径或空格"
    viaJump && (jumpHost!!.any { it.isWhitespace() } || "://" in jumpHost || '/' in jumpHost) -> "跳板机只填写域名或 IP"
    codexExecutable.isNotBlank() && !codexExecutable.startsWith("/") && !codexExecutable.startsWith("~/") ->
        "Codex 路径必须是绝对路径或 ~/ 路径；留空自动检测"
    else -> null
}

internal fun SshHost.normalized(): SshHost = copy(
    name = name.trim(), host = host.trim(), user = user.trim(),
    jumpHost = jumpHost?.trim()?.ifBlank { null }, jumpUser = jumpUser?.trim()?.ifBlank { null },
    workDir = workDir.trim().ifBlank { "~" }, workspaceDir = workspaceDir.trim().ifBlank { "~/pocketagent" },
    codexExecutable = codexExecutable.trim(),
)
