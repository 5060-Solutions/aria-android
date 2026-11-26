package com.solutions5060.aria.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.hold.HoldForMeCoordinator
import com.solutions5060.aria.hold.HoldGreeting
import com.solutions5060.aria.hold.HoldGreetingSetting
import com.solutions5060.aria.hold.HoldSettings
import com.solutions5060.aria.hold.PrefsGreetingMirror
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.components.RowShape
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.launch

object HoldSettingsTags {
    const val ROW = "hold.settings"
    fun option(g: HoldGreeting) = "hold.settings.${g.wire}"
}

@StringRes
private fun label(g: HoldGreeting) = when (g) {
    HoldGreeting.SILENCE -> R.string.ui_hold_greeting_silence
    HoldGreeting.LINE -> R.string.ui_hold_greeting_line
    HoldGreeting.ASSISTANT -> R.string.ui_hold_greeting_assistant
}

@StringRes
private fun description(g: HoldGreeting) = when (g) {
    HoldGreeting.SILENCE -> R.string.ui_hold_greeting_silence_desc
    HoldGreeting.LINE -> R.string.ui_hold_greeting_line_desc
    HoldGreeting.ASSISTANT -> R.string.ui_hold_greeting_assistant_desc
}

/**
 * "Hold for me greeting": what the far party hears when they pick up, as the
 * default for every wait. Shown only when the PBX offers Hold for me. Picking
 * saves at once on this phone and tells the PBX in the background; a refusal
 * puts the old one back and says so.
 */
@Composable
fun HoldForMeRow() {
    val available by HoldForMeCoordinator.available.collectAsState()
    if (!available) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    val service = remember(available) { HoldForMeCoordinator.service() } ?: return
    val setting = remember(service) { HoldGreetingSetting(service, PrefsGreetingMirror(context)) }
    var greeting by remember(setting) { mutableStateOf(setting.current()) }
    var settings by remember(setting) { mutableStateOf(HoldSettings.Opening) }
    var open by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.ui_hold_error_save)

    LaunchedEffect(setting) {
        settings = setting.load()
        greeting = settings.greeting
    }

    AriaListItem(
        headline = stringResource(R.string.ui_hold_settings_greeting),
        supporting = stringResource(label(greeting)),
        leading = { RowIcon(AriaIcons.Swap) },
        trailing = { AriaIcon(AriaIcons.ChevronRight, contentDescription = null) },
        onClick = { open = true },
        shape = RowShape.Pill,
        modifier = Modifier.testTag(HoldSettingsTags.ROW),
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.ui_hold_settings_greeting)) },
        text = {
            Column {
                HoldGreeting.entries.forEach { g ->
                    val enabled = g != HoldGreeting.ASSISTANT || settings.assistantAvailable
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .alpha(if (enabled) 1f else 0.5f)
                            .testTag(HoldSettingsTags.option(g))
                            .clickable(enabled = enabled, role = Role.RadioButton) {
                                open = false
                                val previous = greeting
                                greeting = g
                                scope.launch {
                                    if (!setting.pick(g)) {
                                        greeting = previous
                                        snackbar.showSnackbar(failed)
                                    }
                                }
                            }
                            .padding(vertical = Spacing.sm),
                    ) {
                        RadioButton(selected = g == greeting, onClick = null, enabled = enabled)
                        Column(Modifier.padding(start = Spacing.md)) {
                            Text(stringResource(label(g)))
                            Text(
                                stringResource(if (enabled) description(g) else R.string.ui_hold_assistant_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
