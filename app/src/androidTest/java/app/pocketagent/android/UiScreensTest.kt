package app.pocketagent.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.data.ProviderPresets
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ThemeMode
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import app.pocketagent.android.ui.chat.ChatScreen
import app.pocketagent.android.ui.chat.ChatUiState
import app.pocketagent.android.ui.components.ApprovalDialog
import app.pocketagent.android.ui.home.HomeScreen
import app.pocketagent.android.ui.providers.ProviderEditScreen
import app.pocketagent.android.ui.providers.ProvidersScreen
import app.pocketagent.android.ui.providers.ProvidersUiState
import app.pocketagent.android.ui.theme.PocketAgentTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiScreensTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun homeEmptyStateInvitesSetup() {
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                HomeScreen(
                    conversations = emptyList(),
                    providers = emptyList(),
                    onOpen = {},
                    onNew = {},
                    onDelete = {},
                    onProviders = {},
                    onSettings = {},
                )
            }
        }
        rule.onNodeWithText("把 Codex 装进口袋").assertIsDisplayed()
        rule.onNodeWithText("添加模型来源").assertIsDisplayed()
        rule.onNodeWithText("Key 只保存在本机的系统密钥库里").assertIsDisplayed()
    }

    @Test
    fun homeListsConversation() {
        val conv = Conversation(
            title = "解释报错",
            messages = listOf(ChatMessage(role = Role.USER, text = "帮我看看这个报错")),
        )
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                HomeScreen(
                    conversations = listOf(conv),
                    providers = listOf(ProviderPresets.deepSeek()),
                    onOpen = {},
                    onNew = {},
                    onDelete = {},
                    onProviders = {},
                    onSettings = {},
                )
            }
        }
        rule.onNodeWithText("解释报错").assertIsDisplayed()
        rule.onNodeWithText("帮我看看这个报错").assertIsDisplayed()
    }

    @Test
    fun providersShowModelAndMaskedKey() {
        val provider = Provider(name = "DeepSeek", baseUrl = "https://api.deepseek.com", model = "deepseek-chat")
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ProvidersScreen(
                    state = ProvidersUiState(
                        providers = listOf(provider),
                        keyMasks = mapOf(provider.id to "••••abcd"),
                    ),
                    onBack = {},
                    onAdd = {},
                    onEdit = {},
                    onDelete = {},
                    onProbe = {},
                    onDismissMessage = {},
                )
            }
        }
        rule.onNodeWithText("DeepSeek").assertIsDisplayed()
        rule.onNodeWithText("deepseek-chat").assertIsDisplayed()
        rule.onNodeWithText("Key ••••abcd").assertIsDisplayed()
    }

    @Test
    fun providerEditValidatesBeforeSave() {
        var saved: Provider? = null
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ProviderEditScreen(
                    initial = ProviderPresets.custom(),
                    isNew = true,
                    existingKeyMask = null,
                    onBack = {},
                    onSave = { provider, _ -> saved = provider },
                )
            }
        }
        rule.onNodeWithTag("save").assertIsNotEnabled()

        rule.onNodeWithTag("field_name").performClick()
        rule.onNodeWithTag("field_name").performTextInput("DeepSeek")
        rule.onNodeWithTag("field_base").performClick()
        rule.onNodeWithTag("field_base").performTextInput("https://api.deepseek.com")
        rule.onNodeWithTag("field_model").performClick()
        rule.onNodeWithTag("field_model").performTextInput("deepseek-chat")
        rule.onNodeWithTag("field_key").performClick()
        rule.onNodeWithTag("field_key").performTextInput("sk-test-123")

        rule.onNodeWithTag("save").assertIsEnabled()
        rule.onNodeWithTag("save").performClick()
        assertEquals("DeepSeek", saved?.name)
        assertEquals("deepseek-chat", saved?.model)
    }

    @Test
    fun chatRendersMarkdownAndSendsDraft() {
        var sent: String? = null
        val state = ChatUiState(
            title = "解释报错",
            provider = ProviderPresets.deepSeek(),
            messages = listOf(
                ChatMessage(role = Role.USER, text = "帮我看看"),
                ChatMessage(
                    role = Role.ASSISTANT,
                    text = "先给结论：**是路径问题**。\n\n```kotlin\nval x = 1\n```",
                    toolCalls = listOf(
                        ToolCallRecord("c1", "fs_read", """{"path":"a.txt"}""", ToolStatus.OK, "hello", 12),
                    ),
                    tokensIn = 10,
                    tokensOut = 20,
                ),
            ),
        )
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ChatScreen(
                    state = state,
                    onBack = {},
                    onOpenProviders = {},
                    onSend = { sent = it },
                    onStop = {},
                    onSelectProvider = {},
                    onClear = {},
                    onDismissError = {},
                    onRetry = {},
                )
            }
        }
        rule.onNodeWithText("帮我看看").assertIsDisplayed()
        rule.onNodeWithText("先给结论：是路径问题。").assertIsDisplayed()
        rule.onNodeWithText("fs_read").assertIsDisplayed()
        rule.onNodeWithText("in 10 · out 20 tokens").assertIsDisplayed()

        rule.onNodeWithTag("composer").performClick()
        rule.onNodeWithTag("composer").performTextInput("再详细点")
        rule.onNodeWithTag("send").performClick()
        assertEquals("再详细点", sent)
    }

    @Test
    fun approvalDialogReportsDecision() {
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
        assertTrue(decision == true)
    }
}
