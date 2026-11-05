package app.pocketagent.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.Conversation
import app.pocketagent.android.data.ProviderPresets
import app.pocketagent.android.data.Role
import app.pocketagent.android.data.ThemeMode
import app.pocketagent.android.data.ToolCallRecord
import app.pocketagent.android.data.ToolStatus
import app.pocketagent.android.ui.chat.ChatScreen
import app.pocketagent.android.ui.chat.ChatUiState
import app.pocketagent.android.ui.home.HomeScreen
import app.pocketagent.android.ui.providers.ProviderEditScreen
import app.pocketagent.android.ui.providers.ProvidersScreen
import app.pocketagent.android.ui.providers.ProvidersUiState
import app.pocketagent.android.ui.settings.SettingsScreen
import app.pocketagent.android.ui.theme.PocketAgentTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.FileOutputStream

@RunWith(Parameterized::class)
class ScreenshotTest(private val dark: Boolean) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun modes() = listOf(true, false)
    }

    @get:Rule
    val rule = createComposeRule()

    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getExternalFilesDir("shots") ?: File("/sdcard")
        dir.mkdirs()
        val file = File(dir, "$name${if (dark) "-dark" else "-light"}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT

    @Test
    fun homeScreen() {
        val conv = Conversation(
            title = "解释这个报错",
            messages = listOf(ChatMessage(role = Role.USER, text = "帮我看下这个 CUDA 报错，顺便说说怎么改")),
        )
        rule.setContent {
            PocketAgentTheme(mode) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    HomeScreen(
                        conversations = listOf(conv, Conversation(title = "写个日志统计脚本")),
                        providers = listOf(ProviderPresets.deepSeek(), ProviderPresets.openAi()),
                        onOpen = {}, onNew = {}, onDelete = {}, onProviders = {}, onSettings = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        save("home")
    }

    @Test
    fun chatScreen() {
        val state = ChatUiState(
            title = "解释这个报错",
            provider = ProviderPresets.deepSeek(),
            messages = listOf(
                ChatMessage(role = Role.USER, text = "帮我看下这个 CUDA 报错"),
                ChatMessage(
                    role = Role.ASSISTANT,
                    toolCalls = listOf(
                        ToolCallRecord("c1", "fs_read", """{"path":"train.log"}""", ToolStatus.OK, "…", 8),
                    ),
                    text = "先给结论：**显存不够**，不是代码写错。\n\n```python\ntorch.cuda.empty_cache()\n```\n\n- 把 batch 降到 8\n- 或者开 gradient checkpointing",
                    tokensIn = 1280,
                    tokensOut = 96,
                ),
            ),
        )
        rule.setContent {
            PocketAgentTheme(mode) {
                ChatScreen(
                    state = state,
                    onBack = {}, onOpenProviders = {}, onSend = {}, onStop = {},
                    onSelectProvider = {}, onClear = {}, onDismissError = {},
                )
            }
        }
        rule.waitForIdle()
        save("chat")
    }

    @Test
    fun providersScreen() {
        val a = ProviderPresets.deepSeek()
        val b = ProviderPresets.openAi()
        rule.setContent {
            PocketAgentTheme(mode) {
                ProvidersScreen(
                    state = ProvidersUiState(
                        providers = listOf(a, b),
                        keyMasks = mapOf(a.id to "••••9f3a", b.id to "未配置"),
                    ),
                    onBack = {}, onAdd = {}, onEdit = {}, onDelete = {}, onProbe = {}, onDismissMessage = {},
                )
            }
        }
        rule.waitForIdle()
        save("providers")
    }

    @Test
    fun providerEditScreen() {
        rule.setContent {
            PocketAgentTheme(mode) {
                ProviderEditScreen(
                    initial = ProviderPresets.deepSeek(),
                    isNew = true,
                    existingKeyMask = null,
                    onBack = {},
                    onSave = { _, _ -> },
                )
            }
        }
        rule.waitForIdle()
        save("provider-edit")
    }

    @Test
    fun settingsScreen() {
        rule.setContent {
            PocketAgentTheme(mode) {
                SettingsScreen(
                    theme = mode,
                    appLock = true,
                    secureScreen = false,
                    biometricReady = true,
                    workspacePath = "/data/user/0/app.pocketagent.android/files/workspace",
                    version = "0.1.0",
                    onTheme = {}, onAppLock = {}, onSecureScreen = {},
                    onClearWorkspace = {}, onBack = {},
                )
            }
        }
        rule.waitForIdle()
        save("settings")
    }
}
