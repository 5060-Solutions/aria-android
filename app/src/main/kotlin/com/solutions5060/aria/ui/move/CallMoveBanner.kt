package com.solutions5060.aria.ui.move

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
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
import com.solutions5060.aria.move.ActiveCall
import com.solutions5060.aria.move.CallMoveCoordinator
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Spacing

object CallMoveTags {
    const val BANNER = "move_banner"
    const val MOVE_HERE = "move_here"
}

/**
 * "Call with X on <device> · Move here" for each call on another device.
 * Capped in width so a rail-and-list window does not stretch it.
 */
@Composable
fun CallMoveBanner(modifier: Modifier = Modifier) {
    val calls by CallMoveCoordinator.banner.collectAsState()
    val suggested by CallMoveCoordinator.suggested.collectAsState()
    if (calls.isEmpty()) return
    Column(
        // Above every screen, so it sits below the status bar itself: drawn
        // under it, the system takes the taps and Move here cannot be pressed.
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Spacing.screen, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        calls.forEach { call ->
            BannerRow(call, suggestion = suggested?.id == call.id)
        }
    }
}

@Composable
private fun BannerRow(call: ActiveCall, suggestion: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(CallMoveTags.BANNER),
    ) {
        Row(
            Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (suggestion) {
                    Text(
                        stringResource(R.string.move_continue_here),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    stringResource(R.string.move_banner, call.displayName, call.deviceLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = { CallMoveCoordinator.moveHere(call) },
                modifier = Modifier.testTag(CallMoveTags.MOVE_HERE),
            ) { Text(stringResource(R.string.move_here)) }
            IconButton(onClick = { CallMoveCoordinator.dismiss(call.id) }) {
                AriaIcon(AriaIcons.Close, stringResource(R.string.move_dismiss))
            }
        }
    }
}
