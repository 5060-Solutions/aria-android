package com.solutions5060.aria.ui.history

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.solutions5060.aria.R
import com.solutions5060.aria.history.CallHistoryEntry
import com.solutions5060.aria.history.CallHistoryRecorder
import com.solutions5060.aria.ui.call.CallSheet
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.Avatar
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Spacing
import java.util.Date

/** The info button's sheet: who, their number, and every call with them. */
@Composable
internal fun CallInfoSheet(
    entry: CallHistoryEntry,
    calls: List<CallHistoryEntry>,
    onCall: () -> Unit,
    onDismiss: () -> Unit,
) {
    CallSheet(title = stringResource(R.string.ui_history_call_details), onDismiss = onDismiss) {
        CallInfoContent(entry, calls, onCall)
    }
}

/** Who, their number and every call with them: in the sheet on a phone, the detail pane on a wide window. */
@Composable
internal fun ColumnScope.CallInfoContent(
    entry: CallHistoryEntry,
    calls: List<CallHistoryEntry>,
    onCall: () -> Unit,
) {
    val context = LocalContext.current
    val name = entry.remoteName ?: com.solutions5060.aria.ui.components.PhoneFormat.display(entry.dialNumber)
    run {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = Spacing.xs),
        ) {
            Avatar(entry.remoteName, key = entry.remoteUri)
            Column {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (entry.remoteName != null) {
                    Text(
                        com.solutions5060.aria.ui.components.PhoneFormat.display(entry.dialNumber),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.rowGap)) {
            calls.take(10).forEach { call ->
                val (icon, tint) = directionIcon(call)
                val whenText = DateFormat.getMediumDateFormat(context).format(Date(call.timestamp)) + " · " +
                    DateFormat.getTimeFormat(context).format(Date(call.timestamp))
                AriaListItem(
                    headline = whenText,
                    supporting = CallHistoryRecorder.summary(call),
                    supportingIcon = icon,
                    supportingIconTint = tint,
                    supportingIconDescription = directionLabel(call),
                )
            }
        }
        AriaButton(
            text = stringResource(R.string.ui_history_call_name, name),
            onClick = onCall,
            icon = AriaIcons.Call,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        )
    }
}
