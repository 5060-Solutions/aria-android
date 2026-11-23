package com.solutions5060.aria.ui.forwarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solutions5060.aria.R
import com.solutions5060.aria.pbx.CallForward
import com.solutions5060.aria.pbx.ForwardType
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaScreen
import com.solutions5060.aria.ui.components.SectionCard
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Spacing
import kotlin.math.roundToInt

object ForwardingTags {
    fun destination(type: ForwardType) = "forward_destination_${type.wire}"
    fun enabled(type: ForwardType) = "forward_enabled_${type.wire}"
    fun save(type: ForwardType) = "forward_save_${type.wire}"
    fun error(type: ForwardType) = "forward_error_${type.wire}"
}

@StringRes
private fun ForwardType.title(): Int = when (this) {
    ForwardType.UNCONDITIONAL -> R.string.forwarding_always
    ForwardType.BUSY -> R.string.forwarding_busy
    ForwardType.NO_ANSWER -> R.string.forwarding_no_answer
}

@StringRes
private fun ForwardType.description(): Int = when (this) {
    ForwardType.UNCONDITIONAL -> R.string.forwarding_always_desc
    ForwardType.BUSY -> R.string.forwarding_busy_desc
    ForwardType.NO_ANSWER -> R.string.forwarding_no_answer_desc
}

/**
 * Call forwarding (`/extensions/{id}/forwards`): one card per forward type the
 * PBX supports, each with a destination, an on/off switch and, for no
 * answer, the ring time. The PBX validates destinations (no feature codes or
 * emergency numbers, within the calling profile) and its reason is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardingScreen(onBack: () -> Unit, vm: SelfServiceViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    AriaScreen(
        title = stringResource(R.string.forwarding_title),
        onBack = onBack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.error != null && state.forwards.isEmpty() && !state.loading) {
                Text(stringResource(R.string.forwarding_load_error), color = MaterialTheme.colorScheme.error)
            }
            ForwardType.entries.forEach { type ->
                // Re-keyed on the saved value, so a refresh replaces unsaved edits only when it changed.
                androidx.compose.runtime.key(type, state.forward(type)) {
                    ForwardCard(type, state.forward(type)) { dest, timeout, enabled, done ->
                        vm.saveForward(type, dest, timeout, enabled, done)
                    }
                }
            }
            Text(
                stringResource(R.string.forwarding_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xl),
            )
        }
    }
}

@Composable
private fun ForwardCard(
    type: ForwardType,
    saved: CallForward?,
    onSave: (destination: String, ringTimeout: Int, enabled: Boolean, done: (String?) -> Unit) -> Unit,
) {
    var destination by remember { mutableStateOf(saved?.destination.orEmpty()) }
    var enabled by remember { mutableStateOf(saved?.enabled ?: true) }
    var timeout by remember { mutableFloatStateOf((saved?.ringTimeoutSecs ?: 20).toFloat()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirty = destination.trim() != saved?.destination.orEmpty() ||
        enabled != (saved?.enabled ?: true) ||
        timeout.roundToInt() != (saved?.ringTimeoutSecs ?: 20)

    fun save(dest: String = destination, on: Boolean = enabled) {
        busy = true
        error = null
        onSave(dest, timeout.roundToInt(), on) { e ->
            busy = false
            error = e
        }
    }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(type.title()), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(type.description()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val label = stringResource(type.title())
                Switch(
                    checked = saved != null && enabled,
                    enabled = saved != null && !busy,
                    onCheckedChange = {
                        enabled = it
                        save(on = it)
                    },
                    modifier = Modifier
                        .testTag(ForwardingTags.enabled(type))
                        .semantics { contentDescription = label },
                )
            }
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it; error = null },
                label = { Text(stringResource(R.string.forwarding_destination)) },
                placeholder = { Text(stringResource(R.string.forwarding_destination_hint)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it, modifier = Modifier.testTag(ForwardingTags.error(type))) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                shape = AriaShapes.textField,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ForwardingTags.destination(type)),
            )
            if (type == ForwardType.NO_ANSWER) {
                val secs = timeout.roundToInt()
                Text(
                    pluralStringResource(R.plurals.forwarding_ring_for, secs, secs),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(value = timeout, onValueChange = { timeout = it }, valueRange = 5f..120f, steps = 22)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AriaButton(
                    text = stringResource(R.string.forwarding_save),
                    onClick = { save() },
                    style = AriaButtonStyle.Standard,
                    enabled = !busy && dirty && destination.isNotBlank(),
                    loading = busy,
                    modifier = Modifier.testTag(ForwardingTags.save(type)),
                )
                if (saved != null) {
                    AriaButton(
                        text = stringResource(R.string.forwarding_remove),
                        onClick = {
                            destination = ""
                            save(dest = "")
                        },
                        style = AriaButtonStyle.Plain,
                        enabled = !busy,
                    )
                }
            }
        }
    }
}
