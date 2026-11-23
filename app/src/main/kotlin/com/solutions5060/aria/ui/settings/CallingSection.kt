package com.solutions5060.aria.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solutions5060.aria.R
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.AriaSwitchRow
import com.solutions5060.aria.ui.components.RowGroup
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.components.RowShape
import com.solutions5060.aria.ui.forwarding.SelfServiceViewModel
import com.solutions5060.aria.ui.theme.AriaIcons

object CallingSectionTags {
    const val DND = "settings_dnd"
    const val FORWARDING = "settings_forwarding"
    const val CODECS = "settings_codecs"
}

/** "Opus, G.711 µ-law, G.711 A-law": the enabled codecs in order. */
@Composable
private fun codecSummary(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    val list by com.solutions5060.aria.call.CodecPreferences.state.collectAsState()
    LaunchedEffect(Unit) { com.solutions5060.aria.call.CodecPreferences.load(context) }
    return com.solutions5060.aria.call.CodecOrder.offered(list)
        .joinToString(", ") { com.solutions5060.aria.call.CodecPreferences.label(it) }
}

/**
 * The "Calling" settings group (DESIGN.md §5 Settings 2): Do Not Disturb
 * (PBX-side, so it applies to every device on the extension) and the way
 * into call forwarding. Further rows (codecs, …) are passed in [more].
 */
@Composable
fun CallingSection(
    onOpenForwarding: () -> Unit,
    onOpenCodecs: (() -> Unit)? = null,
    vm: SelfServiceViewModel = viewModel(),
    more: @Composable () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    RowGroup(title = stringResource(R.string.settings_calling)) {
        AriaSwitchRow(
            headline = stringResource(R.string.settings_dnd),
            supporting = stringResource(R.string.settings_dnd_desc),
            checked = state.doNotDisturb == true,
            enabled = state.doNotDisturb != null,
            onCheckedChange = { vm.setDoNotDisturb(it) },
            leading = { RowIcon(AriaIcons.Dnd) },
            modifier = Modifier.testTag(CallingSectionTags.DND),
        )
        AriaListItem(
            headline = stringResource(R.string.forwarding_title),
            supporting = stringResource(if (state.forwardingOn) R.string.settings_forwarding_on else R.string.settings_forwarding_off),
            leading = { RowIcon(AriaIcons.Forwarding) },
            trailing = { AriaIcon(AriaIcons.ChevronRight, contentDescription = null) },
            onClick = onOpenForwarding,
            shape = RowShape.Pill,
            modifier = Modifier.testTag(CallingSectionTags.FORWARDING),
        )
        if (onOpenCodecs != null) {
            AriaListItem(
                headline = stringResource(R.string.codecs_title),
                supporting = codecSummary(),
                leading = { RowIcon(AriaIcons.Codecs) },
                trailing = { AriaIcon(AriaIcons.ChevronRight, contentDescription = null) },
                onClick = onOpenCodecs,
                shape = RowShape.Pill,
                modifier = Modifier.testTag(CallingSectionTags.CODECS),
            )
        }
        more()
    }
}
