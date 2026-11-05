package app.pocketagent.android.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pocketagent.android.AppContainer
import app.pocketagent.android.remote.configurationError
import app.pocketagent.android.remote.normalized
import app.pocketagent.android.remote.SshHost
import app.pocketagent.android.remote.SshRemoteShell
import app.pocketagent.android.security.maskSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import app.pocketagent.android.ui.i18n.L10n

data class RemoteUiState(
    val hosts: List<SshHost> = emptyList(),
    val keyMasks: Map<String, String> = emptyMap(),
    val busyId: String? = null,
    val message: String? = null,
)

class RemoteViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(RemoteUiState())
    val state = _state.asStateFlow()
    private var testJob: Job? = null
    private var testSerial = 0

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (hosts, masks) = withContext(Dispatchers.IO) {
                // 不注入维护者的主机配置；仅加载用户自己保存的主机。
                val seeded = container.remoteStore.load().toMutableList()
                defaultHosts().forEach { d ->
                    if (seeded.none { it.name == d.name }) {
                        container.remoteStore.upsert(d)
                        seeded.add(d)
                    }
                }
                val list = seeded.toList()
                list to list.associate { it.id to maskSecret(container.secretStore.get(container.remoteManager.keyName(it.id))) }
            }
            _state.update { it.copy(hosts = hosts, keyMasks = masks) }
        }
    }

    // 开源版不预置任何主机：由用户自己添加
    private fun defaultHosts(): List<SshHost> = emptyList()

    fun save(input: SshHost, keyPem: String?) {
        val host = input.normalized()
        host.configurationError()?.let { error ->
            _state.update { it.copy(message = L10n.t(error)) }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val previous = container.remoteManager.host(host.id)
                val sameEndpoint = previous != null && previous.host == host.host &&
                    previous.port == host.port && previous.user == host.user &&
                    previous.jumpHost == host.jumpHost && previous.jumpPort == host.jumpPort &&
                    previous.jumpUser == host.jumpUser && previous.codexExecutable == host.codexExecutable
                container.remoteStore.upsert(host.copy(
                    codexHarnessAvailable = if (sameEndpoint) previous?.codexHarnessAvailable else null,
                ))
                if (!keyPem.isNullOrBlank()) {
                    container.secretStore.put(container.remoteManager.keyName(host.id), normalizePem(keyPem))
                    if (host.passphrase.isNullOrEmpty()) {
                        container.secretStore.remove(container.remoteManager.passphraseName(host.id))
                    }
                }
                host.passphrase?.takeIf { it.isNotEmpty() }?.let {
                    container.secretStore.put(container.remoteManager.passphraseName(host.id), it)
                }
            }
            container.remoteManager.close()
            refresh()
            _state.update { it.copy(message = "已保存 ${host.name}") }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.remoteStore.delete(id)
                container.secretStore.remove(container.remoteManager.keyName(id))
                container.secretStore.remove(container.remoteManager.passphraseName(id))
                if (container.settings.syncHostId == id) {
                    container.settings.syncHostId = null
                }
            }
            refresh()
        }
    }

    fun test(input: SshHost, keyPem: String?) {
        val host = input.normalized()
        host.configurationError()?.let { error ->
            _state.update { it.copy(message = L10n.t(error)) }
            return
        }
        val key = keyPem?.takeIf { it.isNotBlank() }
            ?: container.secretStore.get(container.remoteManager.keyName(host.id))
        if (key.isNullOrBlank()) {
            _state.update { it.copy(message = L10n.t("先导入私钥再测试")) }
            return
        }
        val passphrase = host.passphrase?.takeIf { it.isNotEmpty() } ?: if (keyPem.isNullOrBlank())
            container.secretStore.get(container.remoteManager.passphraseName(host.id)) else null
        testJob?.cancel()
        testSerial += 1
        val serial = testSerial
        _state.update { it.copy(busyId = host.id, message = "正在连接 ${host.target} …（最多等待 15 秒）") }
        testJob = viewModelScope.launch outer@{
            // sshj 的连接/跳板握手是阻塞 IO，withTimeout 未必能及时打断；
            // 所以另起一个看门狗，到点一定把转圈状态清掉，保证还能测试下一台。
            val watchdog = launch {
                delay(TEST_TIMEOUT_MS)
                if (serial == testSerial) {
                    testSerial += 1
                    _state.update {
                        it.copy(
                            busyId = null,
                            message = "连接超时（15 秒）：请检查地址、端口、跳板机和私钥。可以直接测试下一台。",
                        )
                    }
                }
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeout(TEST_TIMEOUT_MS) {
                        val shell = SshRemoteShell(
                            host.withKey(normalizePem(key), passphrase),
                            hostKeyVerifier = container.remoteManager.hostKeyVerifier(),
                            connectTimeoutMs = CONNECT_TIMEOUT_MS,
                        )
                        try {
                            val r = shell.exec("echo CONNECT_OK; hostname; uname -sr; pwd", 20_000)
                            r.requireOk("SSH 连接")
                            val available = app.pocketagent.android.tools.detectRemoteCodex(shell, 8_000, host.codexExecutable)
                            r.summary() to available
                        } finally {
                            runCatching { shell.close() }
                        }
                    }
                }
            }
            watchdog.cancel()
            // 看门狗已经报超时 / 用户又点了另一台：旧结果晚到也不能覆盖新状态
            if (serial != testSerial) return@outer
            result.getOrNull()?.let { (_, available) ->
                val saved = container.remoteManager.host(host.id)
                // 编辑器里的未保存地址不能覆盖已保存主机的检测结果。
                if (saved?.withoutSecret()?.copy(codexHarnessAvailable = null) == host.withoutSecret().copy(codexHarnessAvailable = null)) {
                    container.remoteStore.upsert(saved.copy(codexHarnessAvailable = available))
                    _state.update { it.copy(hosts = container.remoteManager.hosts()) }
                }
            }
            _state.update {
                it.copy(
                    busyId = null,
                    message = result.fold(
                        onSuccess = { (out, available) -> "连接结果：\n$out\n" + L10n.t(
                            if (available) "已检测到 Codex，可使用远端 harness；模型和 API 凭证需在远端配置。"
                            else "未安装 Codex，当前可使用远端文件和命令工具；安装并配置后再次测试即可。",
                        ) },
                        onFailure = { e -> "连接或 harness 检测失败：${e.message ?: e.javaClass.simpleName}" },
                    ),
                )
            }
            testJob = null
        }
    }

    /** 允许粘贴成一行（中间没有换行）的 PEM：按 64 字符一行重新折行。 */
    private fun normalizePem(raw: String): String {
        val trimmed = raw.trim().replace("\r\n", "\n")
        val header = Regex("-----BEGIN ([A-Z ]+)-----").find(trimmed) ?: return trimmed
        val footer = Regex("-----END ([A-Z ]+)-----").find(trimmed) ?: return trimmed
        val body = trimmed
            .substring(header.range.last + 1, footer.range.first)
            .replace(Regex("\\s"), "")
        if (body.isEmpty()) return trimmed
        val wrapped = body.chunked(64).joinToString("\n")
        return "${header.value}\n$wrapped\n${footer.value}\n"
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 8_000
    }
}
