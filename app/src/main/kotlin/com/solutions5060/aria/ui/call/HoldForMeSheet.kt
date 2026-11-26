package com.solutions5060.aria.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.solutions5060.aria.R
import com.solutions5060.aria.hold.HoldGreeting
import com.solutions5060.aria.hold.HoldSettings
import com.solutions5060.aria.hold.HoldSheetChoice
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Spacing

object HoldTags {
    const val OVERFLOW = "hold.overflow"
    const val START = "hold.start"
    fun greeting(g: HoldGreeting) = "hold.greeting.${g.wire}"
    const val CARD = "hold.card"
    const val CANCEL = "hold.cancel"
    const val REJOIN = "hold.rejoin"
    const val HANG_UP = "hold.hangUp"
    const val STILL_ON_HOLD = "hold.stillOnHold"
    const val TRANSCRIPT = "hold.transcript"
}

/**
 * "Hold for me": what the far party hears when they pick up, then Start.
 * The assistant is offered only when the account has it. [onStart] gets the
 * row the user picked, or null when they kept the default (the PBX applies it).
 */
@Composable
internal fun HoldForMeSheet(
    settings: HoldSettings,
    onStart: (HoldGreeting?) -> Unit,
    onDismiss: () -> Unit,
) {
    // The user's own pick wins over the default, even if the settings arrive after it.
    var chosen by remember { mutableStateOf<HoldGreeting?>(null) }
    val selected = HoldSheetChoice.shown(chosen, settings)
    CallSheet(title = stringResource(R.string.ui_hold_title), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.ui_hold_sheet_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.ui_hold_when_they_pick_up),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.rowGap)) {
            HoldGreeting.entries.forEach { g ->
                val enabled = g != HoldGreeting.ASSISTANT || settings.assistantAvailable
                val isSelected = g == selected
                AriaListItem(
                    headline = stringResource(greetingTitle(g)),
                    supporting = stringResource(if (enabled) greetingDescription(g) else R.string.ui_hold_assistant_unavailable),
                    leading = {
                        RowIcon(
                            greetingIcon(g),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            container = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        )
                    },
                    trailing = if (isSelected) {
                        { AriaIcon(AriaIcons.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    onClick = if (enabled) ({ chosen = g }) else null,
                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .testTag(HoldTags.greeting(g))
                        .semantics { this.selected = isSelected },
                )
            }
        }
        AriaButton(
            text = stringResource(R.string.ui_hold_start),
            onClick = { onStart(HoldSheetChoice.toSend(chosen, settings)) },
            icon = AriaIcons.Hourglass,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs)
                .testTag(HoldTags.START),
        )
    }
}

private fun greetingTitle(g: HoldGreeting) = when (g) {
    HoldGreeting.SILENCE -> R.string.ui_hold_greeting_silence
    HoldGreeting.LINE -> R.string.ui_hold_greeting_line
    HoldGreeting.ASSISTANT -> R.string.ui_hold_greeting_assistant
}

private fun greetingDescription(g: HoldGreeting) = when (g) {
    HoldGreeting.SILENCE -> R.string.ui_hold_greeting_silence_desc
    HoldGreeting.LINE -> R.string.ui_hold_greeting_line_desc
    HoldGreeting.ASSISTANT -> R.string.ui_hold_greeting_assistant_desc
}

private fun greetingIcon(g: HoldGreeting) = when (g) {
    HoldGreeting.SILENCE -> AriaIcons.MicOff
    HoldGreeting.LINE -> AriaIcons.Speaker
    HoldGreeting.ASSISTANT -> AriaIcons.Recordings
}
