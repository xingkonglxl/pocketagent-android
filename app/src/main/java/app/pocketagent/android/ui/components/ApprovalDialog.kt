package app.pocketagent.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.ui.i18n.tr

@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    onDecision: (Boolean) -> Unit,
    onAllowSession: (() -> Unit)? = null,
) {
    AlertDialog(
        modifier = Modifier.testTag("approval_dialog"),
        onDismissRequest = { onDecision(false) },
        title = { Text("允许执行 ${request.toolName}？") },
        text = {
            Column {
                Text(
                    tr("模型想在你的工作区做一次操作："),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    request.argumentsJson,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .testTag("approval_arguments")
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
                if (!request.sessionAllowable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("此操作风险较高或涉及敏感路径：本次批准只对这一次有效。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("approval_session_warning"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }, modifier = Modifier.testTag("approve")) {
                Text(tr("允许"))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDecision(false) }, modifier = Modifier.testTag("deny")) {
                    Text(tr("拒绝"))
                }
                if (request.sessionAllowable && onAllowSession != null) {
                    TextButton(
                        onClick = { onAllowSession() },
                        modifier = Modifier.testTag("approve_session"),
                    ) {
                        Text(tr("本次会话都允许"))
                    }
                }
            }
        },
    )
}
