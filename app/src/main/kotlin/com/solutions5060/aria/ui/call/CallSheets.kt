package com.solutions5060.aria.ui.call

import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.solutions5060.aria.R
import com.solutions5060.aria.call.AudioRouteOption
import com.solutions5060.aria.call.AudioRouteState
import com.solutions5060.aria.call.AudioRouteType
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.DialKey
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.AriaType
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing

/** The sheet chrome every call-screen sheet shares: 28 dp top corners, title, side margins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallSheet(
    title: String,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissible },
    )
    ModalBottomSheet(
        onDismissRequest = { if (dismissible) onDismiss() },
        sheetState = state,
        shape = AriaShapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .navigationBarsPadding()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(bottom = Spacing.xs)
                    .semantics { heading() },
            )
            content()
        }
    }
}

/**
 * Transfer and Add call: a number field with a contact picker. Transfer is
 * blind and can take up to about 45 s to report; the sheet stays up with a
 * wavy progress bar until it does. "Consult first" calls the target and holds
 * this call instead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NumberEntrySheet(
    title: String,
    confirmLabel: String,
    @DrawableRes confirmIcon: Int,
    busy: Boolean,
    busyText: String?,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // ACTION_PICK on phone numbers grants read access to the one picked row,
    // so this needs no contacts permission of its own.
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) target = cursor.getString(0).orEmpty() }
        } catch (_: Exception) {
        }
    }
    val ready = !busy && target.isNotBlank()

    CallSheet(title = title, onDismiss = onDismiss, dismissible = !busy) {
        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text(stringResource(R.string.ui_call_number_or_extension)) },
            singleLine = true,
            enabled = !busy,
            shape = AriaShapes.textField,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (ready) onConfirm(target) }),
            colors = OutlinedTextFieldDefaults.colors(),
            trailingIcon = {
                androidx.compose.material3.IconButton(
                    onClick = {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    },
                    enabled = !busy,
                ) {
                    AriaIcon(AriaIcons.ContactCard, stringResource(R.string.ui_call_choose_contact))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        )
        if (busy && busyText != null) {
            Column(
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(busyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        AriaButton(
            text = confirmLabel,
            onClick = { onConfirm(target) },
            icon = confirmIcon,
            enabled = ready,
            loading = busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        )
        if (secondaryLabel != null) {
            AriaButton(
                text = secondaryLabel,
                onClick = { onSecondary(target) },
                style = AriaButtonStyle.Standard,
                icon = AriaIcons.AddCall,
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@DrawableRes
internal fun routeIcon(type: AudioRouteType): Int = when (type) {
    AudioRouteType.EARPIECE -> AriaIcons.Earpiece
    AudioRouteType.SPEAKER -> AriaIcons.Speaker
    AudioRouteType.BLUETOOTH -> AriaIcons.Bluetooth
    AudioRouteType.WIRED_HEADSET -> AriaIcons.Headset
    AudioRouteType.STREAMING -> AriaIcons.Cast
}

/** The audio outputs Telecom offers; the current one is checked. */
@Composable
internal fun AudioRouteSheet(
    audio: AudioRouteState,
    onSelect: (AudioRouteOption) -> Unit,
    onDismiss: () -> Unit,
) {
    CallSheet(title = stringResource(R.string.ui_call_audio_output), onDismiss = onDismiss) {
        if (audio.options.isEmpty()) {
            Text(
                stringResource(R.string.ui_call_audio_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.rowGap)) {
            audio.options.forEach { option ->
                val selected = option.id == audio.currentId
                AriaListItem(
                    headline = option.label,
                    leading = {
                        RowIcon(
                            routeIcon(option.type),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                            container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        )
                    },
                    trailing = if (selected) {
                        { AriaIcon(AriaIcons.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    onClick = { onSelect(option) },
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                )
            }
        }
    }
}

/** In-call keypad (DTMF): slides up as a sheet over the controls; shows what was sent. */
@Composable
internal fun KeypadSheet(onDigit: (String) -> Unit, onDismiss: () -> Unit) {
    var sent by rememberSaveable { mutableStateOf("") }
    CallSheet(title = stringResource(R.string.ui_call_keypad), onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.primaryButton),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                sent,
                style = AriaType.display.copy(textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Sizes.dialKeyGapV),
        ) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Sizes.dialKeyGapH)) {
                    row.forEach { digit ->
                        DialKey(digit = digit, onClick = {
                            sent += digit
                            onDigit(digit)
                        })
                    }
                }
            }
        }
    }
}
