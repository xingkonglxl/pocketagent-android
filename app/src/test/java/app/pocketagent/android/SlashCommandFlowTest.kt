package app.pocketagent.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.ConversationStore
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.SyncStore
import app.pocketagent.android.remote.SshHost
import app.pocketagent.android.security.InMemorySecretStore
import app.pocketagent.android.sync.PhoneContext
import app.pocketagent.android.sync.RemoteContext
import app.pocketagent.android.sync.SyncBackend
import app.pocketagent.android.sync.SyncProtocol
import app.pocketagent.android.sync.SyncService
import app.pocketagent.android.ui.chat.ChatUiState
import app.pocketagent.android.ui.chat.ChatViewModel
import app.pocketagent.android.ui.chat.remoteHarnessPrompt
import app.pocketagent.android.ui.home.HomeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 逐条跑对话框里的斜杠命令。这里跑的是真正的 ChatViewModel + 真存储（内存版 SecretStore），
 * 避免「界面看着对、命令其实没生效」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SlashCommandFlowTest {

    private lateinit var container: AppContainer
    private lateinit var vm: ChatViewModel
    private val tempDirs = mutableListOf<File>()
    private val provider = Provider(
        name = "DeepSeek 中转",
        kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://your-relay.example.com/v1",
        model = "deepseek-v4-flash",
    )

    @Before
    fun setUp() {
        // 单测固定中文：语言跟随系统时，Robolectric 默认 en-US 会让断言里的中文文案变英文
        app.pocketagent.android.ui.i18n.L10n.current = "zh"
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        File(ctx.filesDir, "store").deleteRecursively()
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        container = AppContainer(ctx, InMemorySecretStore())
        container.providerStore.save(listOf(provider))
        container.secretStore.put(provider.id, "sk-test")
        vm = ChatViewModel(container, "conv-test")
        await { it.provider != null && it.catalog.isNotEmpty() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun await(cond: (ChatUiState) -> Boolean) = runBlocking {
        withTimeout(10_000) {
            while (!cond(vm.state.value)) delay(20)
        }
    }

    private fun awaitVm(
        target: ChatViewModel,
        cond: (ChatUiState) -> Boolean = { it.provider != null },
    ) = runBlocking {
        withTimeout(10_000) {
            while (!cond(target.state.value)) delay(20)
        }
    }

    private fun lastText(state: ChatUiState = vm.state.value): String =
        state.messages.lastOrNull { it.text.isNotBlank() }?.text.orEmpty()

    private fun run(cmd: String) {
        vm.send(cmd)
        await { true }
    }

    /** 落盘是异步的（viewModelScope + IO），断言存储前先轮询等它写完。 */
    private fun awaitStore(cond: () -> Boolean) = runBlocking {
        withTimeout(10_000) {
            while (!cond()) delay(20)
        }
    }

    private fun tempDir(label: String): File =
        File(container.appContext.cacheDir, "$label-${UUID.randomUUID()}").apply {
            mkdirs()
            tempDirs += this
        }

    private fun conversation(
        id: String,
        text: String,
        hostId: String? = null,
        workspaceDir: String? = null,
    ) = Conversation(
        id = id,
        title = text,
        messages = listOf(
            ChatMessage(role = Role.USER, text = text),
            ChatMessage(role = Role.ASSISTANT, text = "收到"),
        ),
        workspaceHostId = hostId,
        workspaceDir = workspaceDir,
    )

    private fun remote(
        conversation: Conversation,
        rev: String = SyncProtocol.revOf(conversation.messages),
    ) = RemoteContext(
        id = conversation.id,
        title = conversation.title,
        updatedAt = conversation.updatedAt,
        count = conversation.messages.size,
        rev = rev,
        body = SyncProtocol.render(conversation),
    )

    private class FakeSyncBackend(
        var remote: List<RemoteContext> = emptyList(),
    ) : SyncBackend {
        override val label = "Mac"

        override suspend fun dump(hostId: String?): List<RemoteContext> =
            remote.map { it.copy(body = "") }

        override suspend fun fetch(hostId: String?, id: String): String =
            remote.firstOrNull { it.id == id }?.body.orEmpty()

        override suspend fun push(hostId: String?, ctx: PhoneContext): String {
            remote = remote.filterNot { it.id == ctx.id } + RemoteContext(
                id = ctx.id,
                title = ctx.title,
                updatedAt = ctx.updatedAt,
                count = ctx.count,
                rev = ctx.rev,
                body = ctx.body,
            )
            return ctx.rev
        }

        override suspend fun deleteRemote(hostId: String?, id: String) {
            remote = remote.filterNot { it.id == id }
        }
    }

    @Test
    fun `help lists every command`() {
        run("/help")
        val text = lastText()
        listOf(
            "/model", "/reasoning", "/goal", "/fast", "/tools", "/compact",
            "/new", "/status", "/context", "/test",
        )
            .forEach { assertTrue("帮助里缺少 $it", text.contains(it)) }
    }

    @Test
    fun `unknown command falls back to help`() {
        run("/nope")
        assertTrue(lastText().contains("没有这个命令"))
        assertTrue(lastText().contains("/model"))
    }

    @Test
    fun `status shows model protocol and flags`() {
        run("/status")
        val text = lastText()
        assertTrue(text.contains("deepseek-v4-flash"))
        assertTrue(text.contains("chat/completions"))
        assertTrue(text.contains("工具：开"))
    }

    @Test
    fun `new conversation starts local instead of inheriting remote workspace`() {
        assertNull(Conversation().workspaceHostId)
        assertNull(Conversation().workspaceDir)
        container.settings.remoteWorkspaceHostId = "mac-from-old-chat"
        container.settings.remoteWorkspaceDir = "/tmp/old-project"

        val fresh = ChatViewModel(container, "brand-new-chat")
        await { fresh.state.value.provider != null }

        assertNull(container.settings.remoteWorkspaceHostId)
        assertNull(fresh.state.value.workspaceHostId)
        assertEquals("", fresh.state.value.workspaceDir)
    }

    @Test
    fun `steers the current reply and persists the real follow up without waiting for stale answer`() {
        val server = MockWebServer()
        server.start()
        try {
            val localProvider = provider.copy(
                baseUrl = server.url("/v1").toString().removeSuffix("/"),
                autoApprove = true,
            )
            container.providerStore.save(listOf(localProvider))
            container.secretStore.put(localProvider.id, "sk-test")
            val queuedVm = ChatViewModel(container, "queue-${UUID.randomUUID()}")
            awaitVm(queuedVm) { it.provider?.id == localProvider.id }

            fun response(answer: String, delayMs: Long = 0) = MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"$answer\"}}]}\n\n" +
                        "data: [DONE]\n\n",
                )
                .apply { if (delayMs > 0) setBodyDelay(delayMs, TimeUnit.MILLISECONDS) }

            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"第一轮回答 GOAL_DONE\"}}]}\n\n")
                .setHeader("Content-Length", "100000"))
            server.enqueue(response("第二轮回答"))

            queuedVm.send("第一个问题")
            awaitVm(queuedVm) {
                it.running && server.requestCount == 1 && it.messages.any { message ->
                    message.role == Role.ASSISTANT && message.text.contains("第一轮回答")
                }
            }
            queuedVm.send("补充问题")

            awaitVm(queuedVm) {
                !it.running && it.queuedSends.isEmpty() &&
                    it.messages.count { message -> message.role == Role.USER } == 2
            }
            val answers = queuedVm.state.value.messages
                .filter { it.role == Role.ASSISTANT }
                .joinToString("\n") { it.text }
            assertFalse(answers.contains("第一轮回答"))
            assertEquals(1, queuedVm.state.value.appliedFollowUps)
            assertTrue(answers.contains("第二轮回答"))
            assertEquals(2, server.requestCount)
            server.takeRequest(1, TimeUnit.SECONDS)
            val steered = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(steered.contains("补充问题"))
            val saved = container.conversationStore.load().first { it.id.startsWith("queue-") }
            assertEquals(2, saved.messages.count { it.role == Role.USER })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `goal accepts changed requirements during a request and finishes only the corrected goal`() {
        val server = MockWebServer()
        server.start()
        try {
            val localProvider = provider.copy(baseUrl = server.url("/v1").toString().removeSuffix("/"))
            container.providerStore.save(listOf(localProvider))
            val goalVm = ChatViewModel(container, "goal-steering-${UUID.randomUUID()}")
            awaitVm(goalVm) { it.provider?.baseUrl == localProvider.baseUrl }
            fun response(answer: String) = MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"$answer\"}}]}\n\ndata: [DONE]\n\n")
            server.enqueue(response("obsolete GOAL_DONE").setBodyDelay(2, TimeUnit.SECONDS))
            server.enqueue(response("corrected result GOAL_DONE"))
            goalVm.send("/goal original task")
            awaitVm(goalVm) { it.running && server.requestCount == 1 }
            goalVm.send("/goal corrected task")
            awaitVm(goalVm) { !it.running && it.goal == null && it.appliedFollowUps == 1 }
            val messages = goalVm.state.value.messages
            assertTrue(messages.any { it.role == Role.USER && it.text.contains("目标更新为：corrected task") })
            assertFalse(messages.any { it.role == Role.ASSISTANT && it.text.contains("obsolete") })
            assertTrue(messages.any { it.role == Role.ASSISTANT && it.text == "corrected result" })
            assertEquals(2, server.requestCount)
            awaitStore {
                container.conversationStore.load().any {
                    it.id.startsWith("goal-steering-") && it.goal == null &&
                        it.messages.any { m -> m.text == "corrected result" }
                }
            }
        } finally { server.shutdown() }
    }

    @Test
    fun `opening conversations restores each own workspace`() {
        val remoteConversation = conversation(
            id = "workspace-remote-chat",
            text = "远端项目",
            hostId = "host-a",
            workspaceDir = "/srv/project-a",
        )
        val localConversation = conversation(id = "workspace-local-chat", text = "本地项目")
        container.conversationStore.upsertAll(listOf(remoteConversation, localConversation))
        container.settings.remoteWorkspaceHostId = "stale-global-host"
        container.settings.remoteWorkspaceDir = "/stale"

        val remoteVm = ChatViewModel(container, remoteConversation.id)
        await { remoteVm.state.value.provider != null }
        assertEquals("host-a", container.settings.remoteWorkspaceHostId)
        assertEquals("/srv/project-a", container.settings.remoteWorkspaceDir)
        assertEquals("host-a", remoteVm.state.value.workspaceHostId)

        val localVm = ChatViewModel(container, localConversation.id)
        await { localVm.state.value.provider != null }
        assertNull(container.settings.remoteWorkspaceHostId)
        assertEquals("", container.settings.remoteWorkspaceDir)
        assertNull(localVm.state.value.workspaceHostId)
        assertEquals("", localVm.state.value.workspaceDir)
    }

    @Test
    fun `clear messages resets the conversation workspace to local`() {
        val bound = conversation(
            id = "clear-workspace-chat",
            text = "旧项目",
            hostId = "host-a",
            workspaceDir = "/srv/old",
        )
        container.conversationStore.upsert(bound)
        container.settings.remoteWorkspaceHostId = "host-a"
        container.settings.remoteWorkspaceDir = "/srv/old"
        val scoped = ChatViewModel(container, bound.id)
        awaitVm(scoped)

        scoped.clearMessages()

        assertNull(scoped.state.value.workspaceHostId)
        assertEquals("", scoped.state.value.workspaceDir)
        assertNull(container.settings.remoteWorkspaceHostId)
        assertEquals("", container.settings.remoteWorkspaceDir)
        awaitStore {
            container.conversationStore.load().first { it.id == bound.id }.let {
                it.workspaceHostId == null && it.workspaceDir == null
            }
        }
    }

    @Test
    fun `switch workspace persists the conversation binding`() {
        val bound = conversation(
            id = "switch-workspace-chat",
            text = "待切回本地",
            hostId = "removed-host",
            workspaceDir = "/srv/removed",
        )
        container.conversationStore.upsert(bound)
        container.settings.remoteWorkspaceHostId = "removed-host"
        container.settings.remoteWorkspaceDir = "/srv/removed"
        val scoped = ChatViewModel(container, bound.id)
        awaitVm(scoped)

        scoped.switchWorkspace(null, "")

        awaitStore {
            container.conversationStore.load().first { it.id == bound.id }.let {
                it.workspaceHostId == null && it.workspaceDir == null
            }
        }
        assertNull(container.settings.remoteWorkspaceHostId)
        assertEquals("", container.settings.remoteWorkspaceDir)
        assertNull(scoped.state.value.workspaceHostId)
        assertEquals("", scoped.state.value.workspaceDir)

        val reopened = ChatViewModel(container, bound.id)
        awaitVm(reopened)
        assertNull(reopened.state.value.workspaceHostId)
        assertEquals("", reopened.state.value.workspaceDir)
    }

    @Test
    fun `reopened remote chat detects harness before sending and retry preserves earlier history`() {
        val host = SshHost(id = "probe-without-key", name = "Linux server", host = "127.0.0.1", user = "tester")
        container.remoteStore.upsert(host)
        val bound = conversation("probe-reopened-chat", "上一轮必须保留", host.id, "~/project")
        container.conversationStore.upsert(bound)
        val scoped = ChatViewModel(container, bound.id)
        awaitVm(scoped)
        scoped.send("新的远端任务")
        awaitVm(scoped) { !it.running && it.error?.contains("私钥") == true }
        assertEquals(bound.messages, scoped.state.value.messages)
        assertNull(container.remoteManager.host(host.id)?.codexHarnessAvailable)

        scoped.dismissError()
        scoped.retryLast()
        awaitVm(scoped) { !it.running && it.error?.contains("私钥") == true }
        assertEquals("检测失败后的重试不能撤掉上一轮", bound.messages, scoped.state.value.messages)
    }

    @Test
    fun `failed workspace switch restores global and conversation binding`() {
        val unreachable = SshHost(
            id = "workspace-without-key",
            name = "No Key Host",
            host = "127.0.0.1",
            user = "tester",
            workspaceDir = "/srv/original",
        )
        container.remoteStore.upsert(unreachable)
        val bound = conversation(
            id = "rollback-workspace-chat",
            text = "保留旧工作区",
            hostId = "old-host",
            workspaceDir = "/srv/old",
        )
        container.conversationStore.upsert(bound)
        container.settings.remoteWorkspaceHostId = "old-host"
        container.settings.remoteWorkspaceDir = "/srv/old"
        val scoped = ChatViewModel(container, bound.id)
        awaitVm(scoped)

        scoped.switchWorkspace(unreachable.id, "/srv/new")
        awaitVm(scoped) {
            it.workspaceHostId == "old-host" &&
                lastText(it).contains("失败") &&
                lastText(it).contains("已恢复")
        }

        assertEquals("old-host", container.settings.remoteWorkspaceHostId)
        assertEquals("/srv/old", container.settings.remoteWorkspaceDir)
        assertEquals("/srv/original", container.remoteManager.host(unreachable.id)?.workspaceDir)
        awaitStore {
            container.conversationStore.load().first { it.id == bound.id }.let {
                it.workspaceHostId == "old-host" && it.workspaceDir == "/srv/old"
            }
        }
    }

    @Test
    fun `home creation explicitly clears the global remote workspace`() {
        container.settings.remoteWorkspaceHostId = "old-remote"
        container.settings.remoteWorkspaceDir = "/srv/old"
        val home = HomeViewModel(container)
        val ready = CompletableDeferred<String>()

        home.createConversation { ready.complete(it) }
        val id = runBlocking { withTimeout(10_000) { ready.await() } }

        assertNull(container.settings.remoteWorkspaceHostId)
        assertEquals("", container.settings.remoteWorkspaceDir)
        val saved = container.conversationStore.load().first { it.id == id }
        assertNull(saved.workspaceHostId)
        assertNull(saved.workspaceDir)
    }

    @Test
    fun `sync pull preserves the local workspace binding`() = runBlocking {
        val dir = tempDir("sync-pull-workspace")
        val store = ConversationStore(dir)
        val state = SyncStore(dir)
        val original = conversation(
            id = "sync-pull-workspace-chat",
            text = "原始",
            hostId = "host-a",
            workspaceDir = "/srv/a",
        )
        store.upsert(original)
        val backend = FakeSyncBackend(listOf(remote(original)))
        val service = SyncService(state, store, backend)
        service.sync()

        val updated = original.copy(
            messages = original.messages + ChatMessage(role = Role.USER, text = "远端新增"),
        )
        backend.remote = listOf(remote(updated, rev = "remote-2"))
        assertEquals(1, service.sync().pulled)

        val saved = store.load().first { it.id == original.id }
        assertEquals("host-a", saved.workspaceHostId)
        assertEquals("/srv/a", saved.workspaceDir)
        assertEquals("远端新增", saved.messages.last().text)
    }

    @Test
    fun `keeping remote conflict preserves the local workspace binding`() = runBlocking {
        val dir = tempDir("sync-conflict-workspace")
        val store = ConversationStore(dir)
        val state = SyncStore(dir)
        val original = conversation(
            id = "sync-conflict-workspace-chat",
            text = "原始",
            hostId = "host-b",
            workspaceDir = "/srv/b",
        )
        store.upsert(original)
        val backend = FakeSyncBackend(listOf(remote(original)))
        val service = SyncService(state, store, backend)
        service.sync()

        store.upsert(
            original.copy(
                messages = original.messages + ChatMessage(role = Role.USER, text = "本机新增"),
            ),
        )
        val remoteVersion = original.copy(
            messages = original.messages + ChatMessage(role = Role.USER, text = "远端新增"),
        )
        backend.remote = listOf(remote(remoteVersion, rev = "remote-conflict"))
        assertEquals(1, service.sync().conflicts)

        service.resolve(original.id, SyncService.Choice.REMOTE)

        val saved = store.load().first { it.id == original.id }
        assertEquals("host-b", saved.workspaceHostId)
        assertEquals("/srv/b", saved.workspaceDir)
        assertEquals("远端新增", saved.messages.last().text)
    }

    @Test
    fun `context reports model budget and message trimming`() {
        run("/context")
        await { lastText().contains("上下文预算") }
        val text = lastText()
        assertTrue(text.contains("窗口："))
        assertTrue(text.contains("系统提示 + 工具预留"))
        assertTrue(text.contains("历史可用"))
    }

    @Test
    fun `model without argument opens picker`() {
        run("/model")
        assertTrue(vm.state.value.modelPicker)
        assertTrue(vm.state.value.catalog.size > 10)
    }

    @Test
    fun `model with fuzzy name steps into level selection`() {
        run("/model gpt6 astra")
        assertEquals("gpt-6-astra", vm.state.value.pendingModel)
        assertTrue(vm.state.value.modelPicker)
    }

    @Test
    fun `confirm level applies model and effort and persists`() {
        run("/model gpt6 astra")
        vm.confirmModelLevel("high")
        await { it.pendingModel == null }
        val st = vm.state.value
        assertEquals("gpt-6-astra", st.provider?.model)
        assertEquals("high", st.provider?.reasoningEffort)
        assertFalse(st.modelPicker)
        awaitStore { container.providerStore.load().first().model == "gpt-6-astra" }
        assertEquals("gpt-6-astra", container.providerStore.load().first().model)
    }

    @Test
    fun `reasoning opens picker then sets one`() {
        run("/reasoning")
        assertTrue("空参数应弹出档位面板", vm.state.value.reasoningPicker)
        vm.pickReasoningLevel("high")
        assertEquals("high", vm.state.value.provider?.reasoningEffort)
        assertTrue(!vm.state.value.reasoningPicker)
        run("/reasoning low")
        assertEquals("low", vm.state.value.provider?.reasoningEffort)
        run("/reasoning nope")
        assertTrue(lastText().contains("不支持"))
    }

    @Test
    fun `remote thinking is hidden by default and toggles only with remote workspace`() {
        assertFalse(vm.state.value.showRemoteThinking)
        run("/thinking on")
        assertTrue(lastText().contains("只在 /workspace 启用远端主机时生效"))
        assertFalse(vm.state.value.showRemoteThinking)

        container.settings.remoteWorkspaceHostId = "mac-test"
        run("/thinking on")
        assertTrue(vm.state.value.showRemoteThinking)
        assertTrue(container.settings.showRemoteThinking)
        run("/thinking off")
        assertFalse(vm.state.value.showRemoteThinking)
        assertFalse(container.settings.showRemoteThinking)
    }

    @Test
    fun `first remote harness delegation carries prior phone context`() {
        val prompt = remoteHarnessPrompt(
            listOf(
                app.pocketagent.android.data.ChatMessage(role = Role.USER, text = "之前的任务"),
                app.pocketagent.android.data.ChatMessage(
                    role = Role.ASSISTANT,
                    text = "之前的结论",
                    model = "mac-codex",
                ),
                app.pocketagent.android.data.ChatMessage(
                    role = Role.ASSISTANT,
                    text = "/model",
                    model = app.pocketagent.android.agent.LOCAL_NOTICE_MODEL,
                ),
            ),
            "继续",
        )

        assertTrue(prompt.contains("USER:\n之前的任务"))
        assertTrue(prompt.contains("ASSISTANT:\n之前的结论"))
        assertTrue(prompt.endsWith("# Latest user request\n继续"))
        assertFalse(prompt.contains("/model"))
    }

    @Test
    fun `goal set open editor and done`() {
        run("/goal 把 remote-host 接上")
        assertEquals("把 remote-host 接上", vm.state.value.goal)

        // 不带参数应该弹编辑面板，而不是只回一段文字
        run("/goal")
        assertTrue("空参数应打开目标面板", vm.state.value.goalEditor)
        vm.saveGoal("把 remote-host 接上并跑通 ssh_exec")
        assertTrue(!vm.state.value.goalEditor)
        assertEquals("把 remote-host 接上并跑通 ssh_exec", vm.state.value.goal)

        vm.finishGoal()
        assertNull(vm.state.value.goal)
        awaitStore { container.conversationStore.load().firstOrNull { it.id == "conv-test" }?.goal == null }

        // pause / resume / clear 与 codex 对齐
        run("/goal 再设一个")
        run("/goal pause")
        assertTrue("暂停状态要记住", vm.state.value.goalPaused)
        assertEquals("再设一个", vm.state.value.goal)
        run("/goal resume")
        assertTrue(!vm.state.value.goalPaused)

        // 面板里清空 = 清除目标
        run("/goal")
        vm.saveGoal("")
        assertNull(vm.state.value.goal)
        assertTrue(!vm.state.value.goalPaused)

        run("/goal 最后一个")
        run("/goal clear")
        assertNull(vm.state.value.goal)
    }

    @Test
    fun `approvals toggles auto approve`() {
        run("/approvals auto")
        assertEquals(true, vm.state.value.provider?.autoApprove)
        run("/approvals ask")
        assertEquals(false, vm.state.value.provider?.autoApprove)
    }

    @Test
    fun `permissions without argument opens the sheet and closes`() {
        run("/permissions")
        assertTrue("空参数应打开权限面板", vm.state.value.permissionsSheet)
        vm.dismissPermissionsSheet()
        assertFalse(vm.state.value.permissionsSheet)
    }

    @Test
    fun `permissions toggles a category and persists it`() {
        run("/permissions web off")
        assertFalse("web" in vm.state.value.provider!!.permissions)
        run("/permissions write on")
        assertTrue("write" in vm.state.value.provider!!.permissions)
        awaitStore { container.providerStore.load().first().permissions.contains("write") }
        val saved = container.providerStore.load().first()
        assertTrue("read 默认仍放行", "read" in saved.permissions)
        assertFalse("关掉的 web 不能又被默认值加回来", "web" in saved.permissions)
    }

    @Test
    fun `permissions sheet switch writes through`() {
        vm.togglePermission("shell", true)
        assertTrue("shell" in vm.state.value.provider!!.permissions)
        awaitStore { container.providerStore.load().first().permissions.contains("shell") }
        vm.togglePermission("shell", false)
        awaitStore { !container.providerStore.load().first().permissions.contains("shell") }
        assertFalse("shell" in vm.state.value.provider!!.permissions)
    }

    @Test
    fun `permissions rejects an unknown category and keeps policy untouched`() {
        val before = vm.state.value.provider!!.permissions
        run("/permissions nope on")
        assertTrue(lastText().contains("不是有效类别"))
        assertEquals(before, vm.state.value.provider!!.permissions)
    }

    @Test
    fun `repeating the same slash command does not stack bubbles`() {
        run("/permissions")
        vm.dismissPermissionsSheet()
        run("/permissions")
        vm.dismissPermissionsSheet()
        run("/permissions")
        val echoes = vm.state.value.messages.count { it.role == Role.USER && it.text == "/permissions" }
        assertEquals("连按同一个命令只保留一条回显", 1, echoes)
    }

    @Test
    fun `mirror is off by default and refuses without a remote workspace`() {
        run("/mirror")
        assertTrue(lastText().contains("已关闭"))

        run("/mirror on")
        assertTrue("本地工作区不该开启镜像：${lastText()}", lastText().contains("先把 /workspace"))
        assertFalse(container.settings.mirrorSessionsToRemote)
    }

    @Test
    fun `mirror turns off when asked`() {
        container.settings.mirrorSessionsToRemote = true
        run("/mirror off")
        assertFalse(container.settings.mirrorSessionsToRemote)
        assertTrue(lastText().contains("已关闭"))
    }

    @Test
    fun `tools off and on persists`() {
        run("/tools off")
        assertEquals(false, vm.state.value.provider?.toolsEnabled)
        run("/tools on")
        assertEquals(true, vm.state.value.provider?.toolsEnabled)
        awaitStore { container.providerStore.load().first().toolsEnabled }
    }

    @Test
    fun `fast toggles twice`() {
        run("/fast")
        assertTrue(vm.state.value.fastMode)
        run("/fast")
        assertFalse(vm.state.value.fastMode)
    }

    @Test
    fun `fast changes only the Responses service tier`() {
        val responses = provider.copy(kind = ProviderKind.RESPONSES, toolsEnabled = true, maxSteps = 24)
        val fast = app.pocketagent.android.ui.chat.withFastMode(responses, true)
        val normal = app.pocketagent.android.ui.chat.withFastMode(responses, false)
        val chatOnly = app.pocketagent.android.ui.chat.withFastMode(
            provider.copy(toolsEnabled = true, maxSteps = 24),
            true,
        )

        assertEquals("priority", fast.serviceTier)
        assertTrue(fast.toolsEnabled)
        assertEquals(24, fast.maxSteps)
        assertNull(normal.serviceTier)
        assertNull(chatOnly.serviceTier)
    }

    @Test
    fun `undo drops the last user turn`() {
        run("/goal x")
        // 现在设目标会自动开跑，先停掉再测撤回
        vm.stop()
        val before = vm.state.value.messages.size
        run("/undo")
        assertTrue(vm.state.value.messages.size < before + 1)
        assertTrue(lastText().contains("已撤回"))
    }

    @Test
    fun `new clears conversation`() {
        run("/goal x")
        run("/new")
        assertTrue(vm.state.value.messages.isEmpty())
    }
}
