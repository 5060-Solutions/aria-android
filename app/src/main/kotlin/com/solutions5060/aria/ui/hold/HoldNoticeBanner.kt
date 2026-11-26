package com.solutions5060.aria.ui.hold

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.hold.HoldForMeCoordinator
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Spacing

object HoldNoticeTags {
    const val BANNER = "hold.notice"
    const val CALL_AGAIN = "hold.callAgain"
}

/**
 * "<name> picked up while you were away" with Call again, after a Hold for me
 * alert (a tapped notification, or a push while the app is open). Call again
 * dials the number through [onCallAgain], the app's normal dial path.
 */
@Composable
fun HoldNoticeBanner(onCallAgain: (String) -> Unit, modifier: Modifier = Modifier) {
    val notice by HoldForMeCoordinator.notice.collectAsState()
    val n = notice ?: return
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(HoldNoticeTags.BANNER),
        ) {
            Row(
                Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Only a missed pickup has the fixed "picked up" copy; the other events (the business hung up, the wait ended) name who it was.
                Text(
                    if (n.event == "missed") stringResource(R.string.ui_hold_missed_title, n.title) else n.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        HoldForMeCoordinator.clearNotice()
                        onCallAgain(n.remoteNumber)
                    },
                    modifier = Modifier.testTag(HoldNoticeTags.CALL_AGAIN),
                ) { Text(stringResource(R.string.ui_hold_call_again)) }
                IconButton(onClick = { HoldForMeCoordinator.clearNotice() }) {
                    AriaIcon(AriaIcons.Close, stringResource(R.string.move_dismiss))
                }
            }
        }
    }
}
