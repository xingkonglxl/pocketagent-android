package app.pocketagent.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.ProviderPresets
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ThemeMode
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import app.pocketagent.android.ui.chat.ChatScreen
import app.pocketagent.android.ui.chat.ChatUiState
import app.pocketagent.android.ui.chat.PlanStep
import app.pocketagent.android.ui.chat.QueuedSendPreview
import app.pocketagent.android.ui.chat.parsePlanSteps
import app.pocketagent.android.ui.components.ApprovalDialog
import app.pocketagent.android.ui.home.HomeScreen
import app.pocketagent.android.mcp.McpServerConfig
import app.pocketagent.android.ui.mcp.McpScreen
import app.pocketagent.android.ui.mcp.McpUiState
import app.pocketagent.android.ui.providers.ProviderEditScreen
import app.pocketagent.android.ui.providers.ProvidersScreen
import app.pocketagent.android.ui.providers.ProvidersUiState
import app.pocketagent.android.ui.theme.PocketAgentTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RoboUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        val dir = File(System.getProperty("user.dir") ?: ".", "build/robo-screens")
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun homeScreenRendersAndSavesScreenshot() {
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                HomeScreen(
                    conversations = listOf(
                        Conversation(
                            title = "解释这个报错",
                            messages = listOf(
                                ChatMessage(role = Role.USER, text = "帮我看下这个 CUDA 报错"),
                                ChatMessage(role = Role.ASSISTANT, text = "先给结论：显存不够，不是代码写错。"),
                            ),
                            providerId = ProviderPresets.deepSeek().id,
                        ),
                        Conversation(
                            title = "写个日志统计脚本",
                            messages = listOf(ChatMessage(role = Role.USER, text = "按小时统计 nginx 日志")),
                        ),
                    ),
                    providers = listOf(ProviderPresets.deepSeek(), ProviderPresets.openAi()),
                    onOpen = {}, onNew = {}, onDelete = {}, onProviders = {}, onSettings = {},
                )
            }
        }
        rule.onNodeWithText("PocketAgent").assertIsDisplayed()
        rule.onNodeWithText("解释这个报错").assertIsDisplayed()
        rule.onNodeWithText("2 个来源可用").assertIsDisplayed()
        shot("home-dark")
    }

    @Test
    fun chatRendersToolCardAndMarkdown() {
        val state = ChatUiState(
            title = "解释报错",
            provider = ProviderPresets.deepSeek(),
            messages = listOf(
                ChatMessage(role = Role.USER, text = "帮我看下这个 CUDA 报错"),
                ChatMessage(
                    id = "answer",
                    role = Role.ASSISTANT,
                    text = "先给结论：**显存不够**。\n\n```python\ntorch.cuda.empty_cache()\n```\n\n- batch 降到 8",
                    reasoning = "先分析显存和 batch 的关系",
                    toolCalls = listOf(
                        ToolCallRecord("c1", "fs_read", """{"path":"train.log"}""", ToolStatus.OK, "…", 8),
                        ToolCallRecord("c2", "shell_exec", """{"command":"nvidia-smi"}""", ToolStatus.OK, "…", 12),
                    ),
                    tokensIn = 1280,
                    tokensOut = 96,
                ),
            ),
        )
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ChatScreen(
                    state = state,
                    onBack = {}, onOpenProviders = {}, onSend = {}, onStop = {},
                    onSelectProvider = {}, onClear = {}, onDismissError = {}, onRetry = {},
                )
            }
        }
        rule.onNodeWithText("先给结论：显存不够。").assertIsDisplayed()
        rule.onNodeWithText("fs_read").assertIsDisplayed()
        // 桌面 Codex 的多步轨迹：每条工具调用都带步骤序号，顺序与执行顺序一致
        rule.onNodeWithText("步骤 1").assertIsDisplayed()
        rule.onNodeWithText("步骤 2").assertIsDisplayed()
        rule.onNodeWithText("shell_exec").assertIsDisplayed()
        rule.onNodeWithText("AI 生成 · in 1280 · out 96 tokens").assertIsDisplayed()
        rule.onNodeWithTag("composer").assertIsDisplayed()
        rule.onNodeWithText("问点什么…").assertIsDisplayed()
        rule.onNodeWithTag("copy_reasoning").performClick()
        val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
        assertEquals(
            "先分析显存和 batch 的关系",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
        )
        rule.onNodeWithTag("copy_message_answer").performScrollTo().performClick()
        val fullMessage = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(fullMessage.contains("【思考过程】"))
        assertTrue(fullMessage.contains("先分析显存和 batch 的关系"))
        assertTrue(fullMessage.contains("【回答】"))
        assertTrue(fullMessage.contains("batch 降到 8"))
        shot("chat-dark")
    }

    @Test
    fun composerAcceptsFollowUpAndShowsQueueWhileReplying() {
        var sent: String? = null
        var cleared = false
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ChatScreen(
                    state = ChatUiState(
                        title = "处理中",
                        provider = ProviderPresets.deepSeek(),
                        running = true,
                        queuedSends = listOf(QueuedSendPreview("q1", "补充约束", 0)),
                        messages = listOf(ChatMessage(role = Role.USER, text = "先分析项目")),
                    ),
                    onBack = {}, onOpenProviders = {}, onSend = { sent = it }, onStop = {},
                    onClearQueue = { cleared = true },
                    onSelectProvider = {}, onClear = {}, onDismissError = {},
                )
            }
        }
        rule.onNodeWithText("执行中，可发送补充要求调整方向").assertIsDisplayed()
        rule.onNodeWithText("等待读取 1 · 补充约束").assertIsDisplayed()
        rule.onNodeWithTag("composer_input").performTextInput("再补一条")
        rule.onNodeWithTag("send").performClick()
        assertEquals("再补一条", sent)
        rule.onNodeWithTag("clear_queue").performClick()
        assertTrue(cleared)
        shot("chat-queue-dark")
    }

    @Test
    fun reasoningStartsCollapsedAndExpandsOnTap() {
        val reasoning = "先核对输入再给出结论"
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ChatScreen(
                    state = ChatUiState(
                        title = "思考折叠",
                        provider = ProviderPresets.deepSeek(),
                        messages = listOf(
                            ChatMessage(role = Role.USER, text = "分析一下"),
                            ChatMessage(
                                id = "reasoning",
                                role = Role.ASSISTANT,
                                text = "结论如下。",
                                reasoning = reasoning,
                            ),
                        ),
                    ),
                    onBack = {}, onOpenProviders = {}, onSend = {}, onStop = {},
                    onSelectProvider = {}, onClear = {}, onDismissError = {},
                )
            }
        }
        rule.onNodeWithText(reasoning).assertDoesNotExist()
        rule.onNodeWithText("查看思考过程", substring = true).performClick()
        rule.onNodeWithText(reasoning).assertIsDisplayed()
    }

    @Test
    fun mcpScreenShowsServersWithoutLeakingTokens() {
        val state = McpUiState(
            servers = listOf(
                McpServerConfig(id = "a", name = "Docs MCP", url = "https://mcp.example.com/mcp"),
                McpServerConfig(id = "b", name = "Off MCP", url = "https://off.example.com/mcp", enabled = false),
            ),
            tokenMasks = mapOf("a" to "••••（已保存）"),
        )
        rule.setContent {
            PocketAgentTheme(ThemeMode.LIGHT) {
                McpScreen(
                    state = state,
                    onBack = {},
                    onSave = { _, _, _ -> },
                    onDelete = {},
                    onTest = { _, _ -> },
                    onToggle = { _, _ -> },
                    onRefreshTools = {},
                )
            }
        }
        rule.onNodeWithText("Docs MCP").assertIsDisplayed()
        rule.onNodeWithText("https://mcp.example.com/mcp").assertIsDisplayed()
        rule.onNodeWithText("访问令牌：••••（已保存）").assertIsDisplayed()
        rule.onNodeWithText("Off MCP").assertIsDisplayed()
        rule.onNodeWithText("还没有 MCP 服务器。点右上角「添加」，填名称和 https 地址；需要令牌的服务器再填令牌。")
            .assertDoesNotExist()
        shot("mcp-light")
    }

    @Test
    fun chatRendersPlanCardForUpdatePlan() {
        val state = ChatUiState(
            title = "迁移",
            provider = ProviderPresets.deepSeek(),
            messages = listOf(
                ChatMessage(role = Role.USER, text = "帮我迁移到新接口"),
                ChatMessage(
                    role = Role.ASSISTANT,
                    text = "先拆一下步骤。",
                    toolCalls = listOf(
                        ToolCallRecord(
                            id = "p1",
                            name = "update_plan",
                            arguments = "{}",
                            status = ToolStatus.OK,
                            result = "计划已更新：\n1. [x] 读现有接口\n2. [>] 改实现\n3. [ ] 跑测试",
                            durationMs = 4,
                        ),
                    ),
                ),
            ),
        )
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ChatScreen(
                    state = state,
                    onBack = {}, onOpenProviders = {}, onSend = {}, onStop = {},
                    onSelectProvider = {}, onClear = {}, onDismissError = {}, onRetry = {},
                )
            }
        }
        rule.onNodeWithText("计划").assertIsDisplayed()
        rule.onNodeWithText("读现有接口").assertIsDisplayed()
        rule.onNodeWithText("改实现").assertIsDisplayed()
        rule.onNodeWithText("跑测试").assertIsDisplayed()
        rule.onNodeWithText("✓").assertIsDisplayed()
        rule.onNodeWithText("▶").assertIsDisplayed()
        shot("plan-dark")
    }

    @Test
    fun slashMenuModelDispatchesCommandImmediately() {
        var sent: String? = null
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ChatScreen(
                    state = ChatUiState(provider = ProviderPresets.deepSeek()),
                    onBack = {}, onOpenProviders = {}, onSend = { sent = it }, onStop = {},
                    onSelectProvider = {}, onClear = {}, onDismissError = {},
                )
            }
        }
        rule.onNodeWithTag("slash_button").performClick()
        rule.onNodeWithText("/model [名字]").performClick()
        assertEquals("从菜单点模型应直接打开选择器，无需再发送一次", "/model", sent)
    }

    @Test
    fun planStepsAreParsedOnlyFromUpdatePlanOutput() {
        val steps = parsePlanSteps("计划已更新（拆分）：\n1. [x] 读代码\n2. [>] 改实现\n3. [ ] 跑测试")
        assertEquals(3, steps.size)
        assertEquals(PlanStep('x', "读代码"), steps[0])
        assertEquals(PlanStep('>', "改实现"), steps[1])
        assertEquals(PlanStep(' ', "跑测试"), steps[2])
        assertTrue(parsePlanSteps("普通工具输出\n没有任何计划行").isEmpty())
    }

    @Test
    fun providerEditBlocksSaveUntilValid() {
        var saved: String? = null
        rule.setContent {
            PocketAgentTheme(ThemeMode.LIGHT) {
                ProviderEditScreen(
                    initial = ProviderPresets.custom(),
                    isNew = true,
                    existingKeyMask = null,
                    onBack = {},
                    onSave = { p, _ -> saved = p.name },
                )
            }
        }
        // 表单可滚动：默认提示词变长后底部按钮会落在可视区外，先滚过去再点。
        rule.onNodeWithTag("field_name").performScrollTo().performClick()
        rule.onNodeWithTag("field_name").performTextInput("DeepSeek")
        rule.onNodeWithTag("field_base").performScrollTo().performClick()
        rule.onNodeWithTag("field_base").performTextInput("https://api.deepseek.com")
        rule.onNodeWithTag("field_model").performScrollTo().performClick()
        rule.onNodeWithTag("field_model").performTextInput("deepseek-chat")
        rule.onNodeWithTag("field_key").performScrollTo().performClick()
        rule.onNodeWithTag("field_key").performTextInput("sk-test")
        rule.onNodeWithTag("save").performScrollTo().assertIsEnabled().performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals("DeepSeek", saved)
        shot("provider-edit-light")
    }

    @Test
    fun providersListAndApprovalDialog() {
        val a = ProviderPresets.deepSeek()
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ProvidersScreen(
                    state = ProvidersUiState(listOf(a), mapOf(a.id to "••••9f3a")),
                    onBack = {}, onAdd = {}, onEdit = {}, onDelete = {}, onProbe = {}, onDismissMessage = {},
                )
            }
        }
        rule.onNodeWithText("Key ••••9f3a").assertIsDisplayed()
        shot("providers-dark")
    }

    @Test
    fun approvalDialogShowsArguments() {
        var decision: Boolean? = null
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ApprovalDialog(
                    request = ApprovalRequest("c1", "fs_write", """{"path":"a.txt","content":"hi"}"""),
                    onDecision = { decision = it },
                )
            }
        }
        rule.onNodeWithText("允许执行 fs_write？").assertIsDisplayed()
        rule.onNodeWithTag("approve").performClick()
        assert(decision == true)
        shot("approval-dark")
    }
}
