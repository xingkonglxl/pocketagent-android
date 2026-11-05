package app.pocketagent.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.pocketagent.android.data.ThemeMode
import app.pocketagent.android.ui.i18n.L10n
import app.pocketagent.android.ui.settings.SettingsScreen
import app.pocketagent.android.ui.theme.PocketAgentTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 公开包（-PshareBuild=true）要隐藏 SSH 远程与上下文同步，避免敏感类目被拒审。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
class SettingsVisibilityTest {

    @get:Rule
    val rule = createComposeRule()

    @Before
    fun setUp() {
        L10n.current = "zh"
    }

    @Test
    fun publicBuildHidesRemoteAndSync() {
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                SettingsScreen(
                    theme = ThemeMode.DARK,
                    appLock = false,
                    secureScreen = false,
                    biometricReady = true,
                    workspacePath = "手机本地",
                    version = "0.1.0",
                    onTheme = {},
                    onAppLock = {},
                    onSecureScreen = {},
                    onClearWorkspace = {},
                    onOpenProviders = {},
                    showRemote = false,
                    showSync = false,
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText("模型").assertExists()
        rule.onAllNodesWithText("远程执行").assertCountEquals(0)
        rule.onAllNodesWithText("历史上下文").assertCountEquals(0)
        rule.onNodeWithText("工作区").assertExists()
        rule.onNodeWithText("隐私政策").assertExists()
    }

    @Test
    fun internalBuildShowsRemoteAndSync() {
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                SettingsScreen(
                    theme = ThemeMode.DARK,
                    appLock = false,
                    secureScreen = false,
                    biometricReady = true,
                    workspacePath = "手机本地",
                    version = "0.1.0",
                    onTheme = {},
                    onAppLock = {},
                    onSecureScreen = {},
                    onClearWorkspace = {},
                    onOpenProviders = {},
                    showRemote = true,
                    showSync = true,
                    onBack = {},
                )
            }
        }

        rule.onNodeWithText("远程执行").assertExists()
        rule.onNodeWithText("历史上下文").assertExists()
    }
}
