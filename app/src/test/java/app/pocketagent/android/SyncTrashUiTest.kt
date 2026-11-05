package app.pocketagent.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performClick
import app.pocketagent.android.sync.TrashContext
import app.pocketagent.android.ui.sync.SyncRow
import app.pocketagent.android.ui.sync.SyncScreen
import app.pocketagent.android.ui.sync.SyncUiState
import app.pocketagent.android.ui.sync.TrashDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SyncTrashUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun `permanent deletion requires explicit selection and a second confirmation`() {
        val state = mutableStateOf(SyncUiState(
            selectedHostId = "host", hostName = "test host", trashOpen = true,
            trash = listOf(TrashContext("one", "synthetic first"), TrashContext("two", "synthetic second")),
        ))
        var deleted = emptySet<String>()
        rule.setContent {
            TrashDialog(
                state = state.value, onClose = {}, onReload = {},
                onToggle = { id -> state.value = state.value.copy(trashSelected = setOf(id)) },
                onSelectAll = {},
                onRestore = {}, onPermanentlyDelete = { deleted = state.value.trashSelected },
            )
        }
        rule.onNodeWithText("永久删除所选").assertIsNotEnabled()
        rule.onNodeWithText("恢复所选").assertIsNotEnabled()
        rule.onNodeWithTag("trash-select-one").performClick()
        rule.onNodeWithTag("trash-select-two").assertIsOff()
        rule.onNodeWithText("永久删除所选").performClick()
        rule.onNodeWithText("永久删除 1 组历史？").assertIsDisplayed()
        assertEquals(emptySet<String>(), deleted)
        rule.onNodeWithText("取消").performClick()
        assertEquals(emptySet<String>(), deleted)
        rule.onNodeWithText("永久删除所选").performClick()
        rule.onNodeWithText("确认永久删除").performClick()
        assertEquals(setOf("one"), deleted)
    }

    @Test fun `each conversation exposes a single item sync action`() {
        val state = SyncUiState(
            selectedHostId = "host",
            hostName = "test host",
            rows = listOf(SyncRow("one", "synthetic", "phone", 1L, 2, false)),
        )
        var synced = ""
        rule.setContent {
            SyncScreen(
                state = state,
                onBack = {},
                onSync = {},
                onSyncOne = { synced = it },
                onSelectHost = {},
                onToggle = {},
                onSelectAll = {},
                onClearSelection = {},
                onDeleteSelected = {},
                onResolve = { _, _ -> },
                onDismissMessage = {},
            )
        }
        rule.onNodeWithTag("sync-one-one").performClick()
        assertEquals("one", synced)
    }
}
