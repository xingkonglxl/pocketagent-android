package app.pocketagent.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.agent.UiApprovalGate
import app.pocketagent.android.data.SettingsStore
import app.pocketagent.android.data.ThemeMode
import app.pocketagent.android.ui.components.ApprovalDialog
import app.pocketagent.android.ui.theme.PocketAgentTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GateAndSettingsTest {

    @Test
    fun approvalGateSuspendsUntilUserAnswers() = runTest {
        val gate = UiApprovalGate()
        val request = ApprovalRequest("c1", "fs_write", """{"path":"a.txt"}""")
        var result: Boolean? = null

        val job = launch { result = gate.request(request) }
        testScheduler.runCurrent()
        assertNotNull(gate.pending.value)
        assertEquals("fs_write", gate.pending.value?.toolName)
        assertNull(result)

        gate.answer(true)
        job.join()
        assertTrue(result == true)
        assertNull(gate.pending.value)
    }

    @Test
    fun approvalGateDenyAndCancel() = runTest {
        val gate = UiApprovalGate()
        var result: Boolean? = null
        val job = launch { result = gate.request(ApprovalRequest("c2", "fs_delete", "{}")) }
        testScheduler.runCurrent()
        gate.answer(false)
        job.join()
        assertFalse(result!!)

        var second: Boolean? = null
        val job2 = launch { second = gate.request(ApprovalRequest("c3", "http_get", "{}")) }
        testScheduler.runCurrent()
        gate.cancelAll()
        job2.join()
        assertFalse(second!!)
    }

    @Test
    fun sessionWideApprovalNeverCoversDangerousOrSensitiveCalls() = runTest {
        val gate = UiApprovalGate()
        var first: Boolean? = null
        val firstJob = launch {
            first = gate.request(
                ApprovalRequest("c1", "fs_read", "{}", sessionAllowable = true),
            )
        }
        testScheduler.runCurrent()
        gate.allowAllForSession()
        firstJob.join()
        assertTrue(first == true)
        assertTrue(gate.autoAllowForSession)

        assertTrue(
            gate.request(ApprovalRequest("c2", "fs_read", "{}", sessionAllowable = true)),
        )

        var dangerous: Boolean? = null
        val dangerousJob = launch {
            dangerous = gate.request(
                ApprovalRequest("c3", "shell_exec", """{"command":"rm -rf x"}""", sessionAllowable = false),
            )
        }
        testScheduler.runCurrent()
        assertNotNull("危险工具必须再次弹窗", gate.pending.value)
        gate.answer(false)
        dangerousJob.join()
        assertFalse(dangerous!!)
    }

    @Test
    fun allowAllForSessionRejectsNonSessionAllowableRequest() = runTest {
        val gate = UiApprovalGate()
        var result: Boolean? = null
        val job = launch {
            result = gate.request(
                ApprovalRequest("c1", "ssh_write", "{}", sessionAllowable = false),
            )
        }
        testScheduler.runCurrent()

        gate.allowAllForSession()

        assertFalse("不能从危险请求建立会话级放行", gate.autoAllowForSession)
        assertNotNull("危险请求必须继续等待逐次确认", gate.pending.value)
        gate.answer(true)
        job.join()
        assertTrue(result == true)
    }

    @Test
    fun settingsPersistThemeLockAndSecureScreen() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = SettingsStore(context)

        store.theme = ThemeMode.LIGHT
        store.appLock = true
        store.secureScreen = true
        store.lastProviderId = "p1"

        val reopened = SettingsStore(context)
        assertEquals(ThemeMode.LIGHT, reopened.theme)
        assertTrue(reopened.appLock)
        assertTrue(reopened.secureScreen)
        assertEquals("p1", reopened.lastProviderId)

        reopened.appLock = false
        assertFalse(SettingsStore(context).appLock)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ApprovalDialogSecurityTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dangerousRequestShowsFullArgumentsWithoutSessionApproval() {
        val arguments = """{"path":".env","content":"${"x".repeat(800)}_END_MARKER"}"""

        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ApprovalDialog(
                    request = ApprovalRequest(
                        callId = "c1",
                        toolName = "fs_write",
                        argumentsJson = arguments,
                        sessionAllowable = false,
                    ),
                    onDecision = {},
                    onAllowSession = {},
                )
            }
        }

        rule.onNodeWithTag("approval_arguments").assertExists()
        rule.onNodeWithText(arguments).assertExists()
        rule.onNodeWithTag("approval_session_warning").assertExists()
        rule.onNodeWithTag("approve_session").assertDoesNotExist()
    }

    @Test
    fun sessionAllowableRequestOffersSessionApproval() {
        rule.setContent {
            PocketAgentTheme(ThemeMode.DARK) {
                ApprovalDialog(
                    request = ApprovalRequest(
                        callId = "c1",
                        toolName = "fs_read",
                        argumentsJson = """{"path":"README.md"}""",
                        sessionAllowable = true,
                    ),
                    onDecision = {},
                    onAllowSession = {},
                )
            }
        }

        rule.onNodeWithTag("approve_session").assertExists()
        rule.onNodeWithTag("approval_session_warning").assertDoesNotExist()
    }
}
