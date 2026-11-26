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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.solutions5060.aria.R
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.service.ConnectionState
import com.solutions5060.aria.ui.adaptive.KeypadMetrics
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.CallActionButton
import com.solutions5060.aria.ui.components.CallActionKind
import com.solutions5060.aria.ui.components.DialKey
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.StatusChip
import com.solutions5060.aria.ui.splash.HeaderMark
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
    onOpenDiagnostics: () -> Unit = {},
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
        ConnectionSheet(onOpenDiagnostics = {
            showConnection = false
            onOpenDiagnostics()
        }, onDismiss = { showConnection = false })
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // A phone on its side has the height of a small window but the width of a tablet:
        // keypad beside the number, rather than a keypad a thumb wide.
        val sideBySide = maxWidth > maxHeight && maxHeight < SideBySideHeight
        val padW = Spacing.screen * 2
        val columnWidth = minOf(maxWidth - padW, MaxKeypadWidth)
        val metrics = if (sideBySide) {
            KeypadMetrics.forSpace(
                width = minOf((maxWidth - padW) / 2, MaxKeypadWidth),
                height = maxHeight - Spacing.xs * 2,
                rowSpacing = 0.dp,
                callRow = false,
            )
        } else {
            KeypadMetrics.forSpace(
                width = columnWidth,
                height = maxHeight - ChipAndDisplay - Spacing.xl - Spacing.lg,
                rowSpacing = Spacing.xl,
            )
        }
        // The number grows with the keys, to a limit; auto-size still shrinks it for a long one.
        val displayStyle = AriaType.display.let {
            val f = metrics.textScale.coerceIn(1f, 1.5f)
            it.copy(fontSize = it.fontSize * f, lineHeight = it.lineHeight * f)
        }

        val chip: @Composable () -> Unit = {
            // The brand mark at the leading edge is where the splash icon lands.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                HeaderMark(Modifier.align(Alignment.CenterStart))
                StatusChip(
                    accountName = displayName.ifBlank { extensionNumber },
                    status = connection.toRegistrationStatus(),
                    dnd = dnd,
                    forwarding = forwarding,
                    onClick = { showConnection = true },
                )
            }
        }

        // Number display: fills the space above the keypad, number at its foot.
        val display: @Composable (Modifier) -> Unit = { modifier ->
            Box(
                modifier = modifier
                    .fillMaxWidth()
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
                    // Always one display line, so the layout does not jump; a faint hint when empty.
                    BasicText(
                        text = if (number.isEmpty()) stringResource(R.string.ui_dialer_hint) else formatForDisplay(number),
                        style = displayStyle.copy(
                            color = if (number.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = displayStyle.fontSize),
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
        }

        val keypad: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(metrics.gapV)) {
                KeyRows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(metrics.gapH)) {
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
                                size = metrics.key,
                            )
                        }
                    }
                }
            }
        }

        // Call row: empty slot · Call · backspace (only with digits)
        val callRow: @Composable () -> Unit = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(metrics.gapH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(metrics.key))
                Box(Modifier.width(metrics.key), contentAlignment = Alignment.Center) {
                    CallActionButton(
                        kind = CallActionKind.Call,
                        onClick = ::call,
                        contentDescription = stringResource(
                            if (number.isEmpty()) R.string.ui_dialer_redial else R.string.ui_dialer_call,
                        ),
                        modifier = Modifier.widthIn(max = metrics.key),
                        size = metrics.callAction,
                        iconSize = Sizes.callActionIcon * metrics.textScale,
                    )
                }
                Box(Modifier.width(metrics.key), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = number.isNotEmpty(),
                        enter = fadeIn() + scaleIn(initialScale = 0.6f),
                        exit = fadeOut() + scaleOut(targetScale = 0.6f),
                    ) {
                        val deleteLabel = stringResource(R.string.ui_dialer_delete)
                        val clearLabel = stringResource(R.string.ui_dialer_clear)
                        Box(
                            modifier = Modifier
                                .size(metrics.backspace)
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
                            AriaIcon(
                                AriaIcons.Backspace,
                                null,
                                size = Sizes.icon * metrics.textScale.coerceAtMost(1.3f),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (sideBySide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    chip()
                    display(Modifier.weight(1f))
                    Spacer(Modifier.height(Spacing.sm))
                    callRow()
                    Spacer(Modifier.height(Spacing.xs))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { keypad() }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = MaxKeypadWidth + padW)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(Spacing.xs))
                chip()
                display(Modifier.weight(1f))
                Spacer(Modifier.height(Spacing.xl))
                keypad()
                Spacer(Modifier.height(Spacing.xl))
                callRow()
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

/** The widest the keypad column gets: a tablet gets a phone-width pad, not a stretched one. */
private val MaxKeypadWidth = 440.dp

/** Below this window height, a landscape window puts the keypad beside the number. */
private val SideBySideHeight = 480.dp

/** The status chip plus the least the number display needs above the keys. */
private val ChipAndDisplay = 56.dp + 96.dp
