package com.solutions5060.aria.ui.call

import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.call.AudioRouteOption
import com.solutions5060.aria.call.AudioRouteType
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallStateMapping
import com.solutions5060.aria.call.CallUiState
import com.solutions5060.aria.call.CallWaiting
import com.solutions5060.aria.call.Conference
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.ui.SecureWindow
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.hold.HoldReturn
import com.solutions5060.aria.hold.stillOnHoldDeadline
import com.solutions5060.aria.hold.stillOnHoldVisible
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaSnackbarHost
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.Avatar
import com.solutions5060.aria.ui.components.CallActionButton
import com.solutions5060.aria.ui.components.CallActionKind
import com.solutions5060.aria.ui.components.CallControlButton
import com.solutions5060.aria.ui.components.CappedFontScale
import com.solutions5060.aria.ui.components.isAccessibilityFontScale
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.AriaType
import com.solutions5060.aria.ui.theme.LocalAriaColors
import com.solutions5060.aria.ui.theme.LocalReducedMotion
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import com.solutions5060.aria.call.CallEncryption
import com.solutions5060.aria.call.UnencryptedCallNotices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Everything the call screen can ask for. Every action names the call shown when it was tapped. */
data class CallActions(
    val onAnswer: () -> Unit = {},
    val onDecline: () -> Unit = {},
    val onEndAndAnswer: () -> Unit = {},
    val onHangup: () -> Unit = {},
    val onToggleMute: () -> Unit = {},
    val onToggleHold: () -> Unit = {},
    val onDtmf: (String) -> Unit = {},
    val onSelectAudioRoute: (AudioRouteOption) -> Unit = {},
    val onTransfer: (String) -> Unit = {},
    val onConsult: (String) -> Unit = {},
    val onCompleteTransfer: () -> Unit = {},
    val onDismissTransferError: () -> Unit = {},
    /** Returns why the call could not be placed, or null. */
    val onAddCall: (String) -> String? = { null },
    val onSwapTo: (String) -> Unit = {},
    val onHangupOther: (String) -> Unit = {},
    /** Returns why not, or null. */
    val onMerge: () -> String? = { null },
    /** Returns why not, or null. */
    val onSplit: () -> String? = { null },
    /** Starts recording; [consentGiven] when the notice was just confirmed. Returns why not, or null. */
    val onStartRecording: (consentGiven: Boolean) -> String? = { null },
    val onStopRecording: () -> Unit = {},
    val recordingConsentNeeded: () -> Boolean = { false },
    /** The user's other online devices this call can move to. */
    val loadMoveTargets: suspend () -> List<com.solutions5060.aria.move.MoveDevice> = { emptyList() },
    val onMoveTo: (com.solutions5060.aria.move.MoveDevice) -> Unit = {},
    /** Hold for me's settings, for its greeting sheet. */
    val loadHoldSettings: suspend () -> com.solutions5060.aria.hold.HoldSettings = { com.solutions5060.aria.hold.HoldSettings.Fallback },
    /** Start Hold for me on this call with the chosen greeting. */
    val onHoldForMe: (com.solutions5060.aria.hold.HoldGreeting) -> Unit = {},
    /** "Still on hold" on a hold-return call: tell the PBX, then (while ringing) reject the ring. */
    val onStillOnHold: () -> Unit = {},
)

/** What else is going on around the call on screen. */
data class CallContext(
    val otherCalls: List<CallUiState> = emptyList(),
    val canAddCall: Boolean = false,
    val canConsult: Boolean = false,
    val canMerge: Boolean = false,
    val canSplit: Boolean = false,
    val canSwap: Boolean = true,
    val transferPair: CallWaiting.TransferPair? = null,
    /** Graded from media stats; null until there is something to judge. */
    val quality: CallQuality? = null,
    /** Errors to show once each (call setup, merge/record failures). */
    val errors: List<String> = emptyList(),
    /** Hold for me is on for this account and this call can use it (an outgoing, connected call, not in a conference). */
    val holdForMeAvailable: Boolean = false,
    /** What the assistant heard, for a hold-return call; empty until there is a transcript. */
    val holdTranscript: List<com.solutions5060.aria.hold.TranscriptLine> = emptyList(),
    /** Hours of the final "max wait" ring ("Still waiting after N hours"), else null. */
    val holdMaxWaitHours: Int? = null,
)

/**
 * DESIGN.md §5 call screen: SRTP lock at the top, avatar · name · number ·
 * timer in the middle, the other call (or conference) as a tonal card, the
 * 3×2 control grid with Record and Merge/Split in overflow, and Hang-up.
 * Incoming calls get Decline/Answer (plus Hold & Answer / End & Answer when
 * a call is already up).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CallScreen(
    call: CallUiState,
    actions: CallActions,
    context: CallContext = CallContext(),
    onDismissError: (String) -> Unit = {},
) {
    // Shown over the lock screen for incoming calls: keep it out of screenshots.
    SecureWindow()

    var showTransfer by rememberSaveable { mutableStateOf(false) }
    var showAddCall by rememberSaveable { mutableStateOf(false) }
    var addCallError by remember { mutableStateOf<String?>(null) }
    var consultRequested by remember { mutableStateOf(false) }
    var showKeypad by rememberSaveable { mutableStateOf(Fixtures.current?.sheet == "keypad") }
    var showAudio by rememberSaveable { mutableStateOf(false) }
    var showMove by rememberSaveable { mutableStateOf(false) }
    var showHold by rememberSaveable { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showRecordingNotice by remember { mutableStateOf(false) }
    // The app's snackbar host, so notices posted elsewhere ("Call dropped") show here too.
    val snackbar = LocalSnackbarHostState.current
    /** A result from a tap on this screen (merge refused, recording notice…), shown once. */
    var localMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(localMessage) {
        val message = localMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        localMessage = null
    }

    val phase = call.phase
    val active = phase == CallPhase.ACTIVE || phase == CallPhase.HELD
    val incoming = phase == CallPhase.INCOMING

    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(call.connectedAtElapsed, phase) {
        val connectedAt = call.connectedAtElapsed ?: return@LaunchedEffect
        while (phase == CallPhase.ACTIVE || phase == CallPhase.HELD) {
            elapsed = (SystemClock.elapsedRealtime() - connectedAt) / 1000
            delay(1000)
        }
    }

    // Errors and action results go to one snackbar on the call screen.
    LaunchedEffect(context.errors) {
        context.errors.firstOrNull()?.let { message ->
            snackbar.showSnackbar(message)
            onDismissError(message)
        }
    }

    // Spoken state changes (DESIGN.md §7): "Call connected", "Call on hold", "Recording started".
    val announcement = announcementFor(call)
    // A hold return: the label stands in for the name, and the status line says what to do.
    val holdLabel = if (incoming) HoldReturn.label(stringResource(R.string.ui_hold_theyre_back), call) else null
    val holdStatus = if (holdLabel != null) {
        context.holdMaxWaitHours?.let { stringResource(R.string.ui_hold_still_waiting_hours, it) }
            ?: stringResource(R.string.ui_hold_tap_to_rejoin)
    } else {
        null
    }
    // "Still on hold" shows while ringing and for 10 s after joining; recompose when the window closes.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.holdReturnId, call.joinedAtMillis, phase) {
        val deadline = stillOnHoldDeadline(call) ?: return@LaunchedEffect
        nowMillis = System.currentTimeMillis()
        delay((deadline - nowMillis).coerceAtLeast(0L))
        nowMillis = System.currentTimeMillis()
    }
    val stillOnHold = stillOnHoldVisible(call, nowMillis)

    // An unencrypted call is called out once per call: when it connects
    // unencrypted, or the first time it drops to unencrypted later. The
    // snackbar host is a polite live region, so TalkBack reads it out.
    val encryption = CallEncryption.of(call)
    val unencryptedNotice = stringResource(R.string.ui_call_unencrypted_notice)
    val noticeScope = rememberCoroutineScope()
    val explainUnencrypted: () -> Unit = {
        noticeScope.launch {
            snackbar.showSnackbar(unencryptedNotice, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    LaunchedEffect(call.key, encryption) {
        val notices = UnencryptedCallNotices.shared
        notices.retainOnly(context.otherCalls.map { it.key }.toSet() + call.key)
        if (notices.shouldNotify(call.key, encryption)) explainUnencrypted()
    }

    // Sheets
    if (showTransfer && !active) showTransfer = false
    if (consultRequested && context.otherCalls.isNotEmpty()) {
        consultRequested = false
        showTransfer = false
    }
    if (showTransfer) {
        NumberEntrySheet(
            title = stringResource(R.string.ui_call_transfer_title),
            confirmLabel = stringResource(R.string.ui_call_transfer),
            confirmIcon = AriaIcons.Transfer,
            busy = call.transferring,
            busyText = stringResource(R.string.ui_call_transferring),
            error = call.transferError,
            onConfirm = actions.onTransfer,
            onDismiss = {
                if (!call.transferring) {
                    showTransfer = false
                    consultRequested = false
                    actions.onDismissTransferError()
                }
            },
            secondaryLabel = if (context.canConsult) stringResource(R.string.ui_call_consult_first) else null,
            onSecondary = {
                consultRequested = true
                actions.onConsult(it)
            },
        )
    }
    if (showMove && !active) showMove = false
    if (showMove) {
        MoveToSheet(
            loadTargets = actions.loadMoveTargets,
            onSelect = {
                showMove = false
                actions.onMoveTo(it)
            },
            onDismiss = { showMove = false },
        )
    }
    if (showHold && !(active && context.holdForMeAvailable)) showHold = false
    if (showHold) {
        // Opens at once on the default; the account's settings replace it when they arrive.
        var holdSettings by remember { mutableStateOf(com.solutions5060.aria.hold.HoldSettings.Opening) }
        LaunchedEffect(Unit) {
            holdSettings = try {
                actions.loadHoldSettings()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                com.solutions5060.aria.hold.HoldSettings.Fallback
            }
        }
        HoldForMeSheet(
            settings = holdSettings,
            onStart = {
                showHold = false
                actions.onHoldForMe(it)
            },
            onDismiss = { showHold = false },
        )
    }
    if (showAddCall && !context.canAddCall) showAddCall = false
    if (showAddCall) {
        NumberEntrySheet(
            title = stringResource(R.string.ui_call_add_call),
            confirmLabel = stringResource(R.string.ui_dialer_call),
            confirmIcon = AriaIcons.Call,
            busy = false,
            busyText = null,
            error = addCallError,
            onConfirm = { number ->
                addCallError = actions.onAddCall(number)
                if (addCallError == null) showAddCall = false
            },
            onDismiss = {
                showAddCall = false
                addCallError = null
            },
        )
    }
    if (showKeypad && active) KeypadSheet(onDigit = actions.onDtmf, onDismiss = { showKeypad = false })
    if (showAudio) {
        AudioRouteSheet(
            audio = call.audio,
            onSelect = {
                actions.onSelectAudioRoute(it)
                showAudio = false
            },
            onDismiss = { showAudio = false },
        )
    }
    if (showRecordingNotice) {
        RecordingNoticeDialog(
            onConfirm = {
                showRecordingNotice = false
                actions.onStartRecording(true)?.let { localMessage = it }
            },
            onDismiss = { showRecordingNotice = false },
        )
    }

    fun startRecording() {
        if (actions.recordingConsentNeeded()) {
            showRecordingNotice = true
        } else {
            actions.onStartRecording(false)?.let { localMessage = it }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Short screens and accessibility font sizes: one scrolling column, so
        // hang-up stays below the controls instead of being pushed over them.
        val compact = maxHeight < CompactCallHeight || isAccessibilityFontScale()
        val landscape = maxWidth > maxHeight && maxWidth >= LandscapeCallWidth
        AmbientBlobs(ringing = incoming || phase == CallPhase.RINGING || phase == CallPhase.CONNECTING)
        val topRow: @Composable () -> Unit = {
            // Top: SRTP lock, overflow.
            TopRow(
                call = call,
                quality = context.quality,
                onExplainUnencrypted = explainUnencrypted,
                showMenu = active,
                menuOpen = showMore,
                onMenu = { showMore = it },
            ) {
                MoreMenuItems(
                    call = call,
                    context = context,
                    onRecord = {
                        showMore = false
                        if (call.recording) actions.onStopRecording() else startRecording()
                    },
                    onMerge = {
                        showMore = false
                        actions.onMerge()?.let { localMessage = it }
                    },
                    onSplit = {
                        showMore = false
                        actions.onSplit()?.let { localMessage = it }
                    },
                    onAudio = {
                        showMore = false
                        showAudio = true
                    },
                    onHoldForMe = {
                        showMore = false
                        showHold = true
                    },
                )
            }

        }
        val otherCalls: @Composable () -> Unit = {
            // Other call / conference
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                context.otherCalls.forEach { other ->
                    OtherCallCard(
                        call = other,
                        foreground = call,
                        canSwap = context.canSwap,
                        canMerge = context.canMerge,
                        onSwap = { actions.onSwapTo(other.key) },
                        onMerge = { actions.onMerge()?.let { localMessage = it } },
                        onHangup = { actions.onHangupOther(other.key) },
                    )
                }
            }

        }
        val identity: @Composable (Modifier) -> Unit = { identityModifier ->
            // Middle: who and how long
            Column(
                modifier = identityModifier
                    .fillMaxWidth()
                    .semantics { isTraversalGroup = true; traversalIndex = -1f },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(Spacing.xl))
                CallerAvatar(call, animate = incoming)
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    if (call.inConference) {
                        stringResource(R.string.ui_call_conference)
                    } else {
                        holdLabel ?: call.displayName.ifEmpty { stringResource(R.string.ui_call_unknown) }
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading(); traversalIndex = -1f },
                )
                val number = remoteNumber(call)
                if (number != null && number != call.displayName) {
                    Text(
                        number,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xxs),
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                StateLine(call, elapsed, holdStatus)
                HoldReturnTranscript(
                    context.holdTranscript,
                    Modifier.padding(top = Spacing.md),
                )
                call.originalStartMillis?.takeIf { call.phase == CallPhase.ACTIVE || call.phase == CallPhase.HELD }?.let { start ->
                    Text(
                        stringResource(
                            R.string.move_started_at,
                            android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(start)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                call.movingFrom?.takeIf { call.phase == CallPhase.INCOMING }?.let { from ->
                    Text(
                        stringResource(R.string.move_incoming_from, from),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(call.recording) {
                    RecordingBadge(Modifier.padding(top = Spacing.sm))
                }
                Spacer(Modifier.height(Spacing.xl))
            }

        }
        val announce: @Composable () -> Unit = {
            // Spoken announcements only (invisible).
            Text(
                announcement,
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )

        }
        val controls: @Composable () -> Unit = {
            // Controls
            AnimatedVisibility(
                visible = active,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ControlGrid(
                        call = call,
                        canAddCall = context.canAddCall,
                        onMute = actions.onToggleMute,
                        onKeypad = { showKeypad = true },
                        onSpeaker = {
                            if (call.audio.options.any { it.type == AudioRouteType.BLUETOOTH || it.type == AudioRouteType.WIRED_HEADSET }) {
                                showAudio = true
                            } else {
                                call.audio.speakerToggleTarget()?.let(actions.onSelectAudioRoute)
                            }
                        },
                        onHold = actions.onToggleHold,
                        onAddCall = {
                            addCallError = null
                            showAddCall = true
                        },
                        onTransfer = { showTransfer = true },
                        onMove = { showMove = true },
                    )
                    context.transferPair?.let { pair ->
                        CompleteTransfer(pair, onComplete = actions.onCompleteTransfer)
                    }
                    Spacer(Modifier.height(Spacing.xl))
                }
            }

        }
        val bottom: @Composable () -> Unit = {
            // Bottom actions
            if (stillOnHold) {
                AriaButton(
                    text = stringResource(R.string.ui_hold_still_on_hold),
                    onClick = actions.onStillOnHold,
                    style = AriaButtonStyle.Standard,
                    modifier = Modifier
                        .padding(bottom = Spacing.md)
                        .testTag(HoldTags.STILL_ON_HOLD),
                )
            }
            AnimatedContent(
                targetState = when {
                    incoming && context.otherCalls.isNotEmpty() -> BottomMode.Waiting
                    incoming -> BottomMode.Incoming
                    phase == CallPhase.ENDED -> BottomMode.Ended
                    else -> BottomMode.HangUp
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "callActions",
            ) { mode ->
                BottomActions(mode, actions)
            }
        }
        if (landscape) {
            // Wide and short (a phone on its side, a tablet): who is calling on the left, the controls on the right.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    topRow()
                    otherCalls()
                    identity(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    announce()
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    controls()
                    bottom()
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            topRow()
            otherCalls()
            identity(if (compact) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState()))
            announce()
            controls()
            bottom()
            Spacer(Modifier.height(Spacing.xl))
        }
        }
        AriaSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Sizes.callAction + Spacing.xxl),
        )
    }
}

/** Below this height the call screen scrolls as one column (DESIGN.md §7: never clip primary actions). */
private val CompactCallHeight = 720.dp

/** From this width, a window wider than tall lays the call out in two columns. */
private val LandscapeCallWidth = 560.dp

private enum class BottomMode { Incoming, Waiting, HangUp, Ended }

@Composable
private fun announcementFor(call: CallUiState): String {
    val connected = stringResource(R.string.ui_call_announce_connected)
    val held = stringResource(R.string.ui_call_announce_held)
    val ended = stringResource(R.string.ui_call_announce_ended)
    val recording = stringResource(R.string.ui_call_announce_recording)
    val back = HoldReturn.announcement(stringResource(R.string.ui_hold_announce_back), call)
    return when {
        call.phase == CallPhase.INCOMING && back != null -> back
        call.recording -> recording
        call.phase == CallPhase.HELD -> held
        call.phase == CallPhase.ACTIVE -> connected
        call.phase == CallPhase.ENDED -> ended
        else -> ""
    }
}

/** The dialable part of the remote URI ("+15552014410", "1017"). */
private fun remoteNumber(call: CallUiState): String? {
    val user = call.remoteUri.removePrefix("sip:").removePrefix("sips:").substringBefore("@").takeIf { it.isNotBlank() }
        ?: return null
    if (user.length < 7 || user.any { !it.isDigit() && it != '+' }) return user
    return android.telephony.PhoneNumberUtils.formatNumber(user, java.util.Locale.getDefault().country) ?: user
}

/**
 * Slow colour blobs behind the call (DESIGN.md §5 Android background):
 * primary-container and brand-indigo glows drifting over the background.
 * Still when animations are off.
 */
@Composable
private fun AmbientBlobs(ringing: Boolean) {
    val aria = LocalAriaColors.current
    val reduced = LocalReducedMotion.current
    val t = if (reduced) {
        0.25f
    } else {
        val transition = rememberInfiniteTransition(label = "blobs")
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(if (ringing) 6_000 else 14_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "blobT",
        )
        v
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val a = Offset(w * (0.15f + 0.5f * t), h * (0.22f + 0.08f * t))
                val b = Offset(w * (0.85f - 0.45f * t), h * (0.55f - 0.1f * t))
                drawCircle(
                    Brush.radialGradient(listOf(aria.callGlowA.copy(alpha = 0.55f), Color.Transparent), a, w * 0.75f),
                    radius = w * 0.75f,
                    center = a,
                )
                drawCircle(
                    Brush.radialGradient(listOf(aria.callGlowB.copy(alpha = 0.5f), Color.Transparent), b, w * 0.7f),
                    radius = w * 0.7f,
                    center = b,
                )
            },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CallerAvatar(call: CallUiState, animate: Boolean) {
    val reduced = LocalReducedMotion.current
    val rotation = if (animate && !reduced) {
        val transition = rememberInfiniteTransition(label = "avatarSpin")
        val r by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(20_000, easing = LinearEasing)), label = "spin")
        r
    } else {
        0f
    }
    Box(contentAlignment = Alignment.Center) {
        // An Expressive cookie behind the avatar; it turns slowly while ringing.
        Box(
            Modifier
                .size(Sizes.avatarLarge + Spacing.xl)
                .rotate(rotation)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), MaterialShapes.Cookie12Sided.toShape()),
        )
        Avatar(
            name = if (call.inConference) null else call.displayName,
            key = call.remoteUri,
            size = Sizes.avatarLarge,
        )
    }
}

@Composable
private fun StateLine(call: CallUiState, elapsed: Long, incomingOverride: String? = null) {
    val text = when (call.phase) {
        CallPhase.INCOMING -> incomingOverride ?: stringResource(R.string.ui_call_incoming)
        CallPhase.CONNECTING -> stringResource(R.string.ui_call_calling)
        CallPhase.RINGING -> stringResource(R.string.ui_call_ringing)
        CallPhase.HELD -> stringResource(R.string.ui_call_on_hold)
        CallPhase.ENDED -> call.movedTo?.let { stringResource(R.string.move_moved_to, it) }
            ?: if (call.heldForYou) stringResource(R.string.ui_hold_holding_for_you) else stringResource(R.string.ui_call_ended)
        CallPhase.ACTIVE -> CallStateMapping.statusText(CallPhase.ACTIVE, elapsed)
    }
    val timer = call.phase == CallPhase.ACTIVE
    Text(
        text,
        style = if (timer) AriaType.display.copy(fontFeatureSettings = "tnum") else MaterialTheme.typography.titleMedium,
        color = when (call.phase) {
            CallPhase.HELD -> MaterialTheme.colorScheme.tertiary
            CallPhase.ENDED -> if (call.movedTo != null || call.heldForYou) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            else -> if (timer) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/** "REC" with a pulsing dot, in the brand accent (DESIGN.md §5). */
@Composable
private fun RecordingBadge(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val alpha = if (reduced) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "rec")
        val a by transition.animateFloat(1f, 0.3f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "recAlpha")
        a
    }
    val label = stringResource(R.string.ui_call_recording)
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = label },
        shape = AriaShapes.chip,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(Sizes.statusDot)
                    .alpha(alpha)
                    .background(LocalAriaColors.current.brandAccent, CircleShape),
            )
            Text(stringResource(R.string.ui_call_rec), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun TopRow(
    call: CallUiState,
    quality: CallQuality?,
    onExplainUnencrypted: () -> Unit,
    showMenu: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    menu: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.iconButton),
    ) {
        val encryption = CallEncryption.of(call)
        if (call.phase != CallPhase.INCOMING && (encryption != CallEncryption.UNKNOWN || quality != null)) {
            CallCapsule(
                encryption = encryption,
                quality = quality,
                onExplainUnencrypted = onExplainUnencrypted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (showMenu) {
            Box(Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { onMenu(true) }) {
                    AriaIcon(AriaIcons.More, stringResource(R.string.ui_call_more))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenu(false) },
                    shape = AriaShapes.contentRow,
                ) { menu() }
            }
        }
    }
}

/** Test tags of the call screen's encryption capsule. */
object CallScreenTags {
    const val ENCRYPTED = "call_encrypted"
    const val NOT_ENCRYPTED = "call_not_encrypted"
}

/**
 * "🔒 Encrypted | ▂▄▆ Good": security and call quality in one capsule
 * (DESIGN.md §5, parity with iOS). A call that is not encrypted gets an amber
 * capsule with an open lock instead, never the encrypted look, for as long as
 * it lasts; tapping it explains why.
 */
@Composable
private fun CallCapsule(
    encryption: CallEncryption,
    quality: CallQuality?,
    onExplainUnencrypted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val secureLabel = when (encryption) {
        CallEncryption.ENCRYPTED -> stringResource(R.string.ui_call_encrypted)
        CallEncryption.UNENCRYPTED -> stringResource(R.string.ui_call_not_encrypted)
        CallEncryption.UNKNOWN -> null
    }
    val secureDescription = when (encryption) {
        CallEncryption.ENCRYPTED -> stringResource(R.string.ui_call_encrypted)
        CallEncryption.UNENCRYPTED -> stringResource(R.string.ui_call_not_encrypted_description)
        CallEncryption.UNKNOWN -> null
    }
    val qualityLabel = quality?.let { stringResource(qualityLabelRes(it)) }
    val description = listOfNotNull(secureDescription, qualityLabel?.let { stringResource(R.string.ui_call_quality, it) }).joinToString(", ")
    val explainLabel = stringResource(R.string.ui_call_unencrypted_explain)
    // A one-line capsule: grows with the font scale only up to 1.3x.
    CappedFontScale(max = 1.3f) {
        CapsuleBody(encryption, quality, secureLabel, qualityLabel, description, explainLabel, onExplainUnencrypted, modifier)
    }
}

@Composable
private fun CapsuleBody(
    encryption: CallEncryption,
    quality: CallQuality?,
    secureLabel: String?,
    qualityLabel: String?,
    description: String,
    explainLabel: String,
    onExplainUnencrypted: () -> Unit,
    modifier: Modifier,
) {
    val aria = LocalAriaColors.current
    val unencrypted = encryption == CallEncryption.UNENCRYPTED
    val textColor = if (unencrypted) aria.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val semanticsModifier = modifier
        .testTag(if (unencrypted) CallScreenTags.NOT_ENCRYPTED else CallScreenTags.ENCRYPTED)
        .semantics(mergeDescendants = true) { contentDescription = description }
    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (encryption != CallEncryption.UNKNOWN && secureLabel != null) {
                AriaIcon(
                    if (unencrypted) AriaIcons.NotSecure else AriaIcons.Secure,
                    contentDescription = null,
                    size = Sizes.iconSmall,
                    tint = if (unencrypted) aria.warning else aria.success,
                )
                Text(secureLabel, style = MaterialTheme.typography.labelLarge, color = textColor)
            }
            if (encryption != CallEncryption.UNKNOWN && quality != null) {
                Box(
                    Modifier
                        .padding(horizontal = Spacing.xxs)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(if (unencrypted) aria.onWarningContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant),
                )
            }
            if (quality != null && qualityLabel != null) {
                QualityBars(quality)
                Text(qualityLabel, style = MaterialTheme.typography.labelLarge, color = textColor)
            }
        }
    }
    if (unencrypted) {
        // Solid amber, not translucent: the label keeps its contrast over the
        // call screen's moving colour.
        Surface(
            modifier = semanticsModifier
                .minimumInteractiveComponentSize()
                .clip(AriaShapes.chip)
                .clickable(onClickLabel = explainLabel, role = Role.Button, onClick = onExplainUnencrypted),
            shape = AriaShapes.chip,
            color = aria.warningContainer,
        ) { body() }
    } else {
        Surface(
            modifier = semanticsModifier,
            shape = AriaShapes.chip,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        ) { body() }
    }
}

private fun qualityLabelRes(q: CallQuality): Int = when (q) {
    CallQuality.Good -> R.string.ui_call_quality_good
    CallQuality.Fair -> R.string.ui_call_quality_fair
    CallQuality.Poor -> R.string.ui_call_quality_poor
}

/** Three rising bars, [CallQuality.bars] of them lit (success, amber or error). */
@Composable
private fun QualityBars(quality: CallQuality) {
    val aria = LocalAriaColors.current
    val lit = when (quality) {
        CallQuality.Good -> aria.success
        CallQuality.Fair -> aria.statusConnecting
        CallQuality.Poor -> MaterialTheme.colorScheme.error
    }
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(6.dp, 10.dp, 14.dp).forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(4.dp)
                    .height(h)
                    .background(if (i < quality.bars) lit else off, androidx.compose.foundation.shape.RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** "Opus", "G.711 µ-law" … for an SDP codec name. */
internal fun codecDisplayName(sdp: String): String = when (sdp.lowercase()) {
    "opus" -> "Opus"
    "pcmu" -> "G.711 µ-law"
    "pcma" -> "G.711 A-law"
    "g729" -> "G.729"
    "g722" -> "G.722"
    else -> sdp
}

@Composable
private fun MoreMenuItems(
    call: CallUiState,
    context: CallContext,
    onRecord: () -> Unit,
    onMerge: () -> Unit,
    onSplit: () -> Unit,
    onAudio: () -> Unit,
    onHoldForMe: () -> Unit,
) {
    call.codec?.let { codec ->
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ui_call_codec, codecDisplayName(codec))) },
            leadingIcon = { AriaIcon(AriaIcons.Codecs, null) },
            enabled = false,
            onClick = {},
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(if (call.recording) R.string.ui_call_stop_recording else R.string.ui_call_record)) },
        leadingIcon = {
            AriaIcon(
                if (call.recording) AriaIcons.StopRecord else AriaIcons.Record,
                null,
                tint = LocalAriaColors.current.brandAccent,
            )
        },
        enabled = call.phase == CallPhase.ACTIVE || call.recording,
        onClick = onRecord,
    )
    if (context.canSplit) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ui_call_split)) },
            leadingIcon = { AriaIcon(AriaIcons.Split, null) },
            onClick = onSplit,
        )
    } else {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ui_call_merge)) },
            leadingIcon = { AriaIcon(AriaIcons.Merge, null) },
            enabled = context.canMerge,
            onClick = onMerge,
        )
    }
    DropdownMenuItem(
        text = { Text(stringResource(R.string.ui_call_audio_output)) },
        leadingIcon = { AriaIcon(routeIcon(call.audio.current?.type ?: AudioRouteType.EARPIECE), null) },
        onClick = onAudio,
    )
    if (context.holdForMeAvailable) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ui_hold_title)) },
            leadingIcon = { AriaIcon(AriaIcons.Hourglass, null) },
            enabled = call.phase == CallPhase.ACTIVE,
            onClick = onHoldForMe,
            modifier = Modifier.testTag(HoldTags.OVERFLOW),
        )
    }
}

/** The 3×2 control grid (2 columns at accessibility font sizes). */
@Composable
private fun ControlGrid(
    call: CallUiState,
    canAddCall: Boolean,
    onMute: () -> Unit,
    onKeypad: () -> Unit,
    onSpeaker: () -> Unit,
    onHold: () -> Unit,
    onAddCall: () -> Unit,
    onTransfer: () -> Unit,
    onMove: () -> Unit,
) {
    val columns = if (isAccessibilityFontScale()) 2 else 3
    val route = call.audio.current
    val speakerLabel = when (route?.type) {
        null, AudioRouteType.EARPIECE, AudioRouteType.SPEAKER -> stringResource(R.string.ui_call_speaker)
        else -> route.label
    }
    val speakerIcon = when (route?.type) {
        AudioRouteType.BLUETOOTH, AudioRouteType.WIRED_HEADSET, AudioRouteType.STREAMING -> routeIcon(route.type)
        else -> AriaIcons.Speaker
    }
    FlowRow(
        modifier = Modifier.width(Sizes.callControlCell * columns + Spacing.xs * (columns - 1)),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        maxItemsInEachRow = columns,
    ) {
        CallControlButton(
            icon = AriaIcons.Mic,
            checkedIcon = AriaIcons.MicOff,
            label = stringResource(R.string.ui_call_mute),
            checked = call.muted,
            onClick = onMute,
        )
        CallControlButton(icon = AriaIcons.Keypad, label = stringResource(R.string.ui_call_keypad), onClick = onKeypad)
        CallControlButton(
            icon = speakerIcon,
            label = speakerLabel,
            checked = route?.type == AudioRouteType.SPEAKER,
            onClick = onSpeaker,
        )
        CallControlButton(
            icon = AriaIcons.Hold,
            checkedIcon = AriaIcons.Play,
            label = stringResource(R.string.ui_call_hold),
            checked = call.onHold,
            onClick = onHold,
        )
        CallControlButton(
            icon = AriaIcons.AddCall,
            label = stringResource(R.string.ui_call_add_call),
            enabled = canAddCall,
            onClick = onAddCall,
        )
        CallControlButton(
            icon = AriaIcons.Transfer,
            label = stringResource(R.string.ui_call_transfer),
            enabled = call.phase == CallPhase.ACTIVE || call.phase == CallPhase.HELD,
            onClick = onTransfer,
        )
        CallControlButton(
            icon = AriaIcons.Swap,
            label = stringResource(R.string.move_to),
            enabled = call.phase == CallPhase.ACTIVE || call.phase == CallPhase.HELD,
            onClick = onMove,
        )
    }
}

@Composable
private fun BottomActions(mode: BottomMode, actions: CallActions) {
    when (mode) {
        BottomMode.Incoming -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CallActionButton(
                kind = CallActionKind.Decline,
                onClick = actions.onDecline,
                contentDescription = stringResource(R.string.ui_call_decline),
                label = stringResource(R.string.ui_call_decline),
            )
            CallActionButton(
                kind = CallActionKind.Answer,
                onClick = actions.onAnswer,
                contentDescription = stringResource(R.string.ui_call_answer),
                label = stringResource(R.string.ui_call_answer),
            )
        }
        BottomMode.Waiting -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CallActionButton(
                kind = CallActionKind.Decline,
                onClick = actions.onDecline,
                contentDescription = stringResource(R.string.ui_call_decline),
                label = stringResource(R.string.ui_call_decline),
            )
            CallActionButton(
                kind = CallActionKind.HangUp,
                icon = AriaIcons.Swap,
                onClick = actions.onEndAndAnswer,
                contentDescription = stringResource(R.string.ui_call_end_and_answer),
                label = stringResource(R.string.ui_call_end_and_answer),
            )
            CallActionButton(
                kind = CallActionKind.Answer,
                icon = AriaIcons.Hold,
                onClick = actions.onAnswer,
                contentDescription = stringResource(R.string.ui_call_hold_and_answer),
                label = stringResource(R.string.ui_call_hold_and_answer),
            )
        }
        BottomMode.HangUp -> CallActionButton(
            kind = CallActionKind.HangUp,
            onClick = actions.onHangup,
            contentDescription = stringResource(R.string.ui_call_hang_up),
        )
        BottomMode.Ended -> Spacer(Modifier.height(Sizes.callAction))
    }
}

/**
 * The call that is not on screen, as a tonal card: avatar, name and state,
 * then Swap · Merge · End (DESIGN.md §5 "other-call row").
 */
@Composable
private fun OtherCallCard(
    call: CallUiState,
    foreground: CallUiState,
    canSwap: Boolean,
    canMerge: Boolean,
    onSwap: () -> Unit,
    onMerge: () -> Unit,
    onHangup: () -> Unit,
) {
    val canResume = canSwap && CallWaiting.isConnected(call) && call.phase == CallPhase.HELD &&
        foreground.phase != CallPhase.INCOMING
    val stateText = when {
        call.inConference -> stringResource(R.string.ui_call_in_conference)
        call.phase == CallPhase.HELD -> stringResource(R.string.ui_call_on_hold)
        call.phase == CallPhase.ACTIVE -> stringResource(R.string.ui_call_active)
        else -> CallStateMapping.statusText(call.phase, 0)
    }
    val stacked = isAccessibilityFontScale()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AriaShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
    ) {
        val who: @Composable RowScope.() -> Unit = {
            Avatar(name = call.displayName, key = call.remoteUri)
            Column(
                Modifier
                    .weight(1f)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    call.displayName.ifEmpty { stringResource(R.string.ui_call_unknown) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (stacked) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stateText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (call.phase == CallPhase.HELD) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val actions: @Composable RowScope.() -> Unit = {
            if (canResume) SmallAction(AriaIcons.Swap, stringResource(R.string.ui_call_swap), onSwap)
            if (canMerge) SmallAction(AriaIcons.Merge, stringResource(R.string.ui_call_merge), onMerge)
            val endLabel = stringResource(R.string.ui_call_end_other, call.displayName)
            FilledTonalIconButton(
                onClick = onHangup,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.size(Sizes.iconButton),
            ) {
                AriaIcon(AriaIcons.HangUp, endLabel)
            }
        }
        if (stacked) {
            // Accessibility font sizes: the name gets the full width, actions go underneath.
            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = who)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
                    content = actions,
                )
            }
        } else {
            Row(
                Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                who()
                actions()
            }
        }
    }
}

@Composable
private fun SmallAction(icon: Int, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(Sizes.iconButton)) {
        AriaIcon(icon, label)
    }
}

/** Attended transfer: joins the transferred call and the consultation call. */
@Composable
private fun CompleteTransfer(pair: CallWaiting.TransferPair, onComplete: () -> Unit) {
    val busy = pair.transferred.transferring || pair.consult.transferring
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        AriaButton(
            text = stringResource(R.string.ui_call_complete_transfer),
            onClick = onComplete,
            icon = AriaIcons.Transfer,
            loading = busy,
        )
        Text(
            stringResource(R.string.ui_call_complete_transfer_detail, pair.transferred.displayName, pair.consult.displayName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingNoticeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AriaIcon(AriaIcons.Record, null, tint = LocalAriaColors.current.brandAccent) },
        title = { Text(stringResource(R.string.ui_call_record_notice_title)) },
        text = { Text(stringResource(R.string.ui_call_record_notice_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.ui_call_record)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        shape = AriaShapes.card,
    )
}

/** [CallScreen] driven by [CallManager]: used by the app and by the lock-screen call activity. */
@Composable
fun ManagedCallScreen(call: CallUiState) {
    val context = LocalContext.current
    // An incoming call can be the first call ever: ask for the microphone
    // while it rings, so the answered call does not send silence.
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(call.key) {
        if (Fixtures.active) return@LaunchedEffect
        if (!com.solutions5060.aria.ui.permissions.Permissions.hasMicrophone(context)) {
            micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    val calls by CallManager.calls.collectAsState()
    val callError by CallManager.error.collectAsState()
    val actionError by CallManager.actionError.collectAsState()
    val quality = rememberCallQuality(call)
    val key = call.key
    // The button follows the flag and the PBX's list of calls, which arrive after the call does.
    val holdAvailable by com.solutions5060.aria.hold.HoldForMeCoordinator.available.collectAsState()
    val pbxCalls by com.solutions5060.aria.move.CallMoveCoordinator.calls.collectAsState()
    val holdForMe = remember(holdAvailable, pbxCalls, call, calls) {
        com.solutions5060.aria.hold.HoldForMeCoordinator.canHoldForMe(call, call.inConference || Conference.canSplit(calls))
    }
    // A refusal: the call is untouched and the banner says why.
    val refusal by com.solutions5060.aria.hold.HoldForMeCoordinator.refusal.collectAsState()
    val refusalText = refusal?.let { stringResource(it.message) }
    val snackbar = com.solutions5060.aria.ui.components.LocalSnackbarHostState.current
    LaunchedEffect(refusalText) {
        refusalText?.let {
            // Show first: clearing changes the key and would cancel the snackbar mid-show.
            try {
                snackbar.showSnackbar(it)
            } finally {
                com.solutions5060.aria.hold.HoldForMeCoordinator.clearRefusal()
            }
        }
    }
    // A hold return's wait arrives over the socket or REST, maybe after the ring: absent until then.
    val holdWaits by com.solutions5060.aria.hold.HoldForMeCoordinator.waits.collectAsState()
    val holdTranscripts by com.solutions5060.aria.hold.HoldForMeCoordinator.transcripts.collectAsState()
    val holdWait = remember(holdWaits, call.holdReturnId) {
        call.holdReturnId?.let { com.solutions5060.aria.hold.HoldForMeCoordinator.waitForHoldReturn(it) }
    }
    val holdTranscript = remember(holdWait, holdTranscripts) {
        holdWait?.let { holdTranscripts[it.callId] }.orEmpty()
    }
    CallScreen(
        call = call,
        context = CallContext(
            otherCalls = CallWaiting.others(calls, call),
            canAddCall = CallWaiting.canAddCall(call, calls),
            canConsult = CallWaiting.canConsult(call, calls),
            canMerge = Conference.canMerge(calls),
            canSplit = Conference.canSplit(calls),
            canSwap = Conference.canSwap(calls),
            transferPair = CallWaiting.attendedTransferPair(calls),
            errors = listOfNotNull(callError, actionError),
            quality = quality,
            holdForMeAvailable = holdForMe,
            holdTranscript = holdTranscript,
            holdMaxWaitHours = holdWait?.takeIf { it.ringReason == "max_wait" }
                ?.let { com.solutions5060.aria.hold.HoldReturn.hours(it.maxWaitSecs) },
        ),
        onDismissError = { message ->
            if (message == callError) CallManager.clearError()
            if (message == actionError) CallManager.clearActionError()
        },
        actions = CallActions(
            onAnswer = { CallManager.answerByKey(key) },
            onDecline = { CallManager.rejectByKey(key) },
            onEndAndAnswer = { CallManager.endOthersAndAnswer(key) },
            onHangup = { CallManager.hangup(key) },
            onToggleMute = { CallManager.setMuted(key, !call.muted) },
            onToggleHold = { CallManager.setHold(key, !call.onHold) },
            onDtmf = { CallManager.sendDtmf(key, it) },
            onSelectAudioRoute = { CallManager.selectAudioRoute(key, it) },
            onTransfer = { CallManager.transfer(key, it) },
            onConsult = { CallManager.consult(key, it) },
            onCompleteTransfer = { CallManager.transferAttended() },
            onDismissTransferError = { CallManager.clearTransferError() },
            onAddCall = { CallManager.placeCall(context, it) },
            onSwapTo = { CallManager.swapTo(it) },
            onHangupOther = { CallManager.hangup(it) },
            onMerge = { CallManager.mergeCalls() },
            onSplit = { CallManager.splitConference() },
            onStartRecording = { consent -> CallManager.startRecording(key, consentGiven = consent) },
            onStopRecording = { CallManager.stopRecording(key) },
            recordingConsentNeeded = { CallManager.recordingConsentNeeded() },
            loadMoveTargets = { com.solutions5060.aria.move.CallMoveCoordinator.targets(call.remoteUri) },
            onMoveTo = { com.solutions5060.aria.move.CallMoveCoordinator.moveTo(call.remoteUri, it) },
            loadHoldSettings = { com.solutions5060.aria.hold.HoldForMeCoordinator.service()?.settings() ?: com.solutions5060.aria.hold.HoldSettings.Fallback },
            onHoldForMe = { com.solutions5060.aria.hold.HoldForMeCoordinator.startAsync(call, it) },
            onStillOnHold = {
                call.holdReturnId?.let { holdId ->
                    val ringing = call.phase == CallPhase.INCOMING
                    com.solutions5060.aria.AppScope.scope.launch {
                        com.solutions5060.aria.hold.HoldForMeCoordinator.stillOnHoldForReturn(holdId)
                        // The PBX keeps waiting: this ring is not wanted, whether or not the request got through.
                        if (ringing) CallManager.rejectByKey(key)
                    }
                }
            },
        ),
    )
}

/**
 * Polls `get_media_stats` every 2 s while the call is connected and grades
 * the last interval. The core does not push media stats (on_media_stats is
 * not emitted), so the screen asks while it is visible.
 */
@Composable
private fun rememberCallQuality(call: CallUiState): CallQuality? {
    if (Fixtures.active) return if (call.phase == CallPhase.ACTIVE) CallQuality.Good else null
    var quality by remember(call.key) { mutableStateOf<CallQuality?>(null) }
    LaunchedEffect(call.callId, call.phase) {
        val id = call.callId
        if (id == null || call.phase != CallPhase.ACTIVE) return@LaunchedEffect
        var previous: MediaSample? = null
        while (true) {
            val sample = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.solutions5060.aria.service.SipEngineHolder.engine?.getMediaStats(id) }.getOrNull()
            }?.let { MediaSample(it.packetsReceived.toLong(), it.packetLoss.toLong(), it.jitterMs, it.rttMs) }
            CallQualityGrader.grade(previous, sample)?.let { quality = it }
            if (sample != null) previous = sample
            delay(2_000)
        }
    }
    return quality
}
