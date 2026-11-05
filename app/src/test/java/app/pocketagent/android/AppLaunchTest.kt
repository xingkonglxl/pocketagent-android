package app.pocketagent.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 冒烟测试：真正拉起 MainActivity，走完 Application → AppContainer → 主题 → 首页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
class AppLaunchTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appBootsToHomeAndShowsEmptyState() {
        rule.waitForIdle()
        // 首次启动必须先同意隐私政策/用户协议，同意后才进首页
        rule.onNodeWithText("同意并继续").assertIsDisplayed()
        rule.onNodeWithText("隐私政策").assertIsDisplayed()
        rule.onNodeWithText("用户协议").assertIsDisplayed()
        rule.onNodeWithText("同意并继续").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("PocketAgent").assertIsDisplayed()
        rule.onNodeWithText("把 Codex 装进口袋").assertIsDisplayed()
        rule.onNodeWithText("添加模型来源").assertIsDisplayed()
    }
}
