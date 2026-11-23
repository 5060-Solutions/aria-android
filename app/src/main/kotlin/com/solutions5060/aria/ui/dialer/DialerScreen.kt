package com.solutions5060.aria.ui.dialer

import android.media.AudioManager
import android.media.ToneGenerator
import android.telephony.PhoneNumberUtils
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.solutions5060.aria.R
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.service.ConnectionState
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.CallActionButton
import com.solutions5060.aria.ui.components.CallActionKind
import com.solutions5060.aria.ui.components.DialKey
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.StatusChip
import com.solutions5060.aria.ui.connection.ConnectionSheet
import com.solutions5060.aria.ui.connection.toRegistrationStatus
import com.solutions5060.aria.ui.contacts.ContactsSource
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaType
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val PREFS = "aria_prefs"
private const val KEY_LAST_DIALED = "last_dialed_number"

private val KeyRows = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#"),
)

private val DtmfTones = mapOf(
    "1" to ToneGenerator.TONE_DTMF_1, "2" to ToneGenerator.TONE_DTMF_2, "3" to ToneGenerator.TONE_DTMF_3,
    "4" to ToneGenerator.TONE_DTMF_4, "5" to ToneGenerator.TONE_DTMF_5, "6" to ToneGenerator.TONE_DTMF_6,
    "7" to ToneGenerator.TONE_DTMF_7, "8" to ToneGenerator.TONE_DTMF_8, "9" to ToneGenerator.TONE_DTMF_9,
    "0" to ToneGenerator.TONE_DTMF_0, "*" to ToneGenerator.TONE_DTMF_S, "#" to ToneGenerator.TONE_DTMF_P,
)

/** The number as people read it ("(555) 201-4410"); extensions and SIP strings stay as typed. */
private fun formatForDisplay(number: String): String {
    if (number.length < 7 || number.any { !it.isDigit() && it != '+' }) return number
    return PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) ?: number
}

/**
 * DESIGN.md §5 Dialer: status chip, number display (auto-shrinks, formatted)
 * with the matching contact, the 4×3 keypad, and Call with backspace.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    onCall: (String) -> Unit,
    displayName: String = "",
    extensionNumber: String = "",
    domain: String = "",
    connection: ConnectionState = ConnectionState(),
    transport: String = "",
    onReconnect: () -> Unit = {},
    dnd: Boolean = false,
    forwarding: Boolean = false,
    callError: String? = null,
    onDismissError: () -> Unit = {},
    /** A number handed in from a tel:/sip: link; replaces what is typed. */
    prefill: String? = null,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current

    var number by rememberSaveable { mutableStateOf(Fixtures.current?.dialNumber ?: "") }
    LaunchedEffect(prefill) { if (prefill != null) number = prefill }
    var showConnection by rememberSaveable { mutableStateOf(Fixtures.current?.sheet == "connection") }
    var showPaste by remember { mutableStateOf(false) }
    val numberLabel = stringResource(R.string.ui_dialer_number)
    val pasteLabel = stringResource(R.string.ui_dialer_paste)

    LaunchedEffect(callError) {
        val message = callError ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        onDismissError()
    }

    val tone = remember {
        try {
            ToneGenerator(AudioManager.STREAM_DTMF, 80)
        } catch (_: RuntimeException) {
            null
        }
    }
    DisposableEffect(tone) { onDispose { tone?.release() } }

    val contacts by produceState(emptyList<com.solutions5060.aria.ui.contacts.Contact>()) {
        value = withContext(Dispatchers.IO) { runCatching { ContactsSource.load(context) }.getOrDefault(emptyList()) }
    }
    val match = remember(number, contacts) { ContactsSource.match(number, contacts) }

    fun type(digit: String) {
        number += digit
        tone?.startTone(DtmfTones[digit] ?: ToneGenerator.TONE_DTMF_0, 80)
        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
    }

    fun call() {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (number.isNotEmpty()) {
            prefs.edit { putString(KEY_LAST_DIALED, number) }
            onCall(number)
        } else {
            // Empty: fill in the last number, like a desk phone's redial.
            prefs.getString(KEY_LAST_DIALED, null)?.takeIf { it.isNotEmpty() }?.let {
                number = it
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
    }

    if (showConnection) {
        ConnectionSheet(
            state = connection,
            account = listOf(displayName, extensionNumber.takeIf { it.isNotBlank() }?.let { "Ext $it" }, domain)
                .filter { !it.isNullOrBlank() }
                .joinToString(" · "),
            transport = transport,
            onReconnect = onReconnect,
            onDismiss = { showConnection = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xs))
        StatusChip(
            accountName = displayName.ifBlank { extensionNumber },
            status = connection.status.toRegistrationStatus(),
            dnd = dnd,
            forwarding = forwarding,
            onClick = { showConnection = true },
        )

        // Number display: fills the space above the keypad, number at its foot.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .combinedClickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                    onLongClick = { showPaste = true },
                )
                .semantics {
                    contentDescription = numberLabel + if (number.isEmpty()) "" else ", " + number.toList().joinToString(" ")
                    liveRegion = LiveRegionMode.Polite
                    onLongClick(label = pasteLabel) { showPaste = true; true }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = formatForDisplay(number),
                    style = AriaType.display.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = AriaType.display.fontSize),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = match?.name ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
                DropdownMenu(expanded = showPaste, onDismissRequest = { showPaste = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_dialer_paste)) },
                        leadingIcon = { AriaIcon(AriaIcons.Copy, null) },
                        onClick = {
                            showPaste = false
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                val digits = text.filter { it.isDigit() || it in "+*#" }
                                if (digits.isNotEmpty()) number = digits
                            }
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.xl))

        // Keypad
        Column(verticalArrangement = Arrangement.spacedBy(Sizes.dialKeyGapV)) {
            KeyRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Sizes.dialKeyGapH)) {
                    row.forEach { digit ->
                        DialKey(
                            digit = digit,
                            onClick = { type(digit) },
                            onLongClick = if (digit == "0") {
                                {
                                    number += "+"
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            } else {
                                null
                            },
                            onLongClickLabel = if (digit == "0") stringResource(R.string.ui_dialer_add_plus) else null,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.xl))

        // Call row: empty slot · Call · backspace (only with digits)
        Row(
            horizontalArrangement = Arrangement.spacedBy(Sizes.dialKeyGapH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(Sizes.dialKey))
            Box(Modifier.width(Sizes.dialKey), contentAlignment = Alignment.Center) {
                CallActionButton(
                    kind = CallActionKind.Call,
                    onClick = ::call,
                    contentDescription = stringResource(
                        if (number.isEmpty()) R.string.ui_dialer_redial else R.string.ui_dialer_call,
                    ),
                    modifier = Modifier.widthIn(max = Sizes.dialKey),
                )
            }
            Box(Modifier.width(Sizes.dialKey), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = number.isNotEmpty(),
                    enter = fadeIn() + scaleIn(initialScale = 0.6f),
                    exit = fadeOut() + scaleOut(targetScale = 0.6f),
                ) {
                    val deleteLabel = stringResource(R.string.ui_dialer_delete)
                    val clearLabel = stringResource(R.string.ui_dialer_clear)
                    Box(
                        modifier = Modifier
                            .size(Sizes.iconButton)
                            .clip(CircleShape)
                            .combinedClickable(
                                interactionSource = null,
                                indication = ripple(),
                                role = Role.Button,
                                onClick = {
                                    number = number.dropLast(1)
                                    haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                                },
                                onLongClick = {
                                    number = ""
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                            )
                            .clearAndSetSemantics {
                                contentDescription = deleteLabel
                                onLongClick(label = clearLabel) { number = ""; true }
                                onClick(label = deleteLabel) { number = number.dropLast(1); true }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AriaIcon(AriaIcons.Backspace, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}
