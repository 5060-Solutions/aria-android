package com.solutions5060.aria.ui.hold

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.R
import com.solutions5060.aria.hold.HoldForMeCoordinator
import com.solutions5060.aria.hold.HoldWait
import com.solutions5060.aria.ui.call.HoldTags
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** `12:34`, or `1:02:03` from an hour on. */
internal fun holdElapsed(millis: Long): String {
    val total = (millis.coerceAtLeast(0L) / 1000L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

/**
 * The waits that are open, above the tab content. Nothing shows unless the
 * PBX offers Hold for me.
 */
@Composable
fun HoldWaitCards(modifier: Modifier = Modifier) {
    val available by HoldForMeCoordinator.available.collectAsState()
    val waits by HoldForMeCoordinator.waits.collectAsState()
    val open = waits.values.filter { it.isOpen }
    if (!available || open.isEmpty()) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        open.forEach { wait ->
            HoldWaitCard(wait, now) { rejoin ->
                AppScope.scope.launch { HoldForMeCoordinator.cancel(wait.callId, rejoin) }
            }
        }
    }
}

/**
 * "Waiting on hold · 12:34" with who it is and Cancel. Cancel asks whether to
 * rejoin now or hang up; [onCancel] gets `true` for Rejoin now.
 */
@Composable
fun HoldWaitCard(wait: HoldWait, now: Long, modifier: Modifier = Modifier, onCancel: (rejoin: Boolean) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    val title = when (wait.state) {
        "human_detected" -> stringResource(R.string.ui_hold_someone_answered)
        "ringing" -> stringResource(R.string.ui_hold_ringing_you)
        else -> stringResource(R.string.ui_hold_waiting, holdElapsed(now - (wait.startedAtMillis ?: now)))
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(HoldTags.CARD),
    ) {
        Row(
            Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    wait.remoteName ?: wait.remoteNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { asking = true },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp).testTag(HoldTags.CANCEL),
            ) { Text(stringResource(R.string.ui_hold_cancel)) }
        }
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.ui_hold_stop_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onCancel(true)
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(HoldTags.REJOIN),
                ) { Text(stringResource(R.string.ui_hold_rejoin_now)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        asking = false
                        onCancel(false)
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(HoldTags.HANG_UP),
                ) { Text(stringResource(R.string.ui_hold_hang_up)) }
            },
        )
    }
}
