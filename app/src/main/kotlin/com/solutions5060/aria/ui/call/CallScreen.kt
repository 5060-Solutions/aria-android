package com.solutions5060.aria.ui.call

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.solutions5060.aria.call.AudioRouteOption
import com.solutions5060.aria.call.AudioRouteType
import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallStateMapping
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.call.CallUiState
import com.solutions5060.aria.call.CallWaiting
import com.solutions5060.aria.ui.SecureWindow
import com.solutions5060.aria.service.SipEngineHolder
import kotlinx.coroutines.delay
import uniffi.aria_mobile.CallDirection

@Composable
fun CallScreen(
    call: CallUiState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleHold: () -> Unit,
    onDtmf: (String) -> Unit,
    onSelectAudioRoute: (AudioRouteOption) -> Unit,
    onTransfer: (String) -> Unit,
    onDismissTransferError: () -> Unit,
    otherCalls: List<CallUiState> = emptyList(),
    canAddCall: Boolean = false,
    canConsult: Boolean = false,
    transferPair: CallWaiting.TransferPair? = null,
    callError: String? = null,
    onEndAndAnswer: () -> Unit = {},
    onSwapTo: (String) -> Unit = {},
    onHangupOther: (String) -> Unit = {},
    onAddCall: (String) -> String? = { null },
    onConsult: (String) -> Unit = {},
    onCompleteTransfer: () -> Unit = {},
    onDismissCallError: () -> Unit = {},
) {
    val isSpeaker = call.audio.isSpeaker

    // Shown over the lock screen for incoming calls.
    SecureWindow()
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var showAddCall by remember { mutableStateOf(false) }
    var addCallError by remember { mutableStateOf<String?>(null) }
    /** "Consult first" was tapped; the sheet closes once that call exists. */
    var consultRequested by remember { mutableStateOf(false) }
    var showDtmfKeypad by remember { mutableStateOf(false) }
    var rxLevel by remember { mutableFloatStateOf(0f) }
    var txLevel by remember { mutableFloatStateOf(0f) }

    val phase = call.phase
    val callId = call.callId
    val isMuted = call.muted
    val isOnHold = call.onHold

    // Pulsing animation for ringing/connecting states
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Call state comes from engine events via CallManager; only the level
    // meters are sampled, since the engine has no event for them.
    LaunchedEffect(callId, phase) {
        if (callId == null || phase != CallPhase.ACTIVE) return@LaunchedEffect
        while (true) {
            val engine = SipEngineHolder.engine ?: break
            try {
                rxLevel = engine.getRxAudioLevel(callId)
                txLevel = engine.getTxAudioLevel(callId)
            } catch (_: Exception) {}
            delay(300)
        }
    }

    // Timer — counts from the Connected event
    LaunchedEffect(call.connectedAtElapsed, phase) {
        val connectedAt = call.connectedAtElapsed ?: return@LaunchedEffect
        while (phase == CallPhase.ACTIVE || phase == CallPhase.HELD) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - connectedAt) / 1000
            delay(1000)
        }
    }

    // Status text
    val statusText = CallStateMapping.statusText(phase, elapsedSeconds)

    val isRingingOrConnecting = phase == CallPhase.RINGING || phase == CallPhase.CONNECTING || phase == CallPhase.INCOMING
    val isActive = phase == CallPhase.ACTIVE || phase == CallPhase.HELD

    val displayName = call.displayName

    // Audio picker dialog
    // Close the transfer dialog if the call is no longer one that can be transferred.
    if (showTransfer && phase != CallPhase.ACTIVE && phase != CallPhase.HELD) showTransfer = false
    // The consultation call is on its way: it now has the screen.
    if (consultRequested && otherCalls.isNotEmpty()) {
        consultRequested = false
        showTransfer = false
    }
    if (showTransfer) {
        TransferDialog(
            transferring = call.transferring,
            error = call.transferError,
            canConsult = canConsult,
            onTransfer = onTransfer,
            onConsult = {
                consultRequested = true
                onConsult(it)
            },
            onDismiss = {
                if (!call.transferring) {
                    showTransfer = false
                    consultRequested = false
                    onDismissTransferError()
                }
            },
        )
    }

    if (showAddCall && !canAddCall) showAddCall = false
    if (showAddCall) {
        NumberEntryDialog(
            title = "Add call",
            confirmLabel = "Call",
            busy = false,
            busyText = null,
            error = addCallError,
            onConfirm = { number ->
                addCallError = onAddCall(number)
                if (addCallError == null) showAddCall = false
            },
            onDismiss = {
                showAddCall = false
                addCallError = null
            },
        )
    }

    if (showAudioPicker) {
        AlertDialog(
            onDismissRequest = { showAudioPicker = false },
            title = { Text("Audio Output", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    if (call.audio.options.isEmpty()) {
                        Text("No audio outputs reported yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                    call.audio.options.forEach { option ->
                        AudioOutputOption(
                            label = option.label,
                            icon = routeIcon(option.type),
                            selected = option.id == call.audio.currentId,
                            onClick = {
                                onSelectAudioRoute(option)
                                showAudioPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioPicker = false }) {
                    Text("Close")
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1040),
                        Color(0xFF0C1929),
                        Color(0xFF0A0F1A),
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            callError?.let { message ->
                CallBanner(message = message, onDismiss = onDismissCallError)
                Spacer(modifier = Modifier.height(12.dp))
            }

            // The other call: held while this one is up, or the one this
            // ringing call would join.
            otherCalls.forEach { other ->
                OtherCallRow(
                    call = other,
                    foreground = call,
                    onSwap = { onSwapTo(other.key) },
                    onHangup = { onHangupOther(other.key) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.weight(0.8f))

            // Avatar with pulse ring during ringing
            Box(contentAlignment = Alignment.Center) {
                if (isRingingOrConnecting) {
                    // Animated pulse rings
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f))
                    )
                    Box(
                        modifier = Modifier
                            .size(115.dp)
                            .scale(pulseScale * 0.95f)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    )
                }
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF6366F1),
                                    Color(0xFF8B5CF6),
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Show initials if we have a name
                    if (displayName.isNotEmpty() && displayName[0].isLetter()) {
                        Text(
                            text = displayName.take(1).uppercase(),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = Color.White,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Caller name
            Text(
                text = displayName.ifEmpty { "Unknown" },
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Status
            Text(
                text = statusText,
                fontSize = 16.sp,
                fontWeight = if (isRingingOrConnecting) FontWeight.Normal else FontWeight.Medium,
                color = when {
                    phase == CallPhase.HELD -> Color(0xFFFBBF24) // Amber
                    isActive -> Color(0xFF22C55E) // Green
                    else -> Color.White.copy(alpha = 0.6f)
                },
            )

            // Media encryption, as negotiated (not as configured)
            if (isActive && call.encrypted != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val encrypted = call.encrypted
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (encrypted) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (encrypted) Color(0xFF22C55E) else Color(0xFFFBBF24),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (encrypted) "Encrypted" else "Not encrypted",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }

            // Audio level meters
            if (isActive) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // RX (speaker) level
                    Text("RX", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    AudioLevelBar(
                        level = (rxLevel / 5000f).coerceIn(0f, 1f),
                        color = Color(0xFF22C55E),
                        modifier = Modifier.weight(1f),
                    )
                    // TX (mic) level
                    Text("TX", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                    AudioLevelBar(
                        level = (txLevel / 5000f).coerceIn(0f, 1f),
                        color = Color(0xFF6366F1),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Controls — only show when call is active
            if (isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallControlButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isMuted) "Unmute" else "Mute",
                        isActive = isMuted,
                        onClick = onToggleMute
                    )
                    CallControlButton(
                        icon = if (isSpeaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                        label = "Speaker",
                        isActive = isSpeaker,
                        onClick = { call.audio.speakerToggleTarget()?.let(onSelectAudioRoute) },
                    )
                    CallControlButton(
                        icon = if (isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                        label = if (isOnHold) "Resume" else "Hold",
                        isActive = isOnHold,
                        onClick = onToggleHold
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallControlButton(
                        icon = Icons.Default.Dialpad,
                        label = "Keypad",
                        isActive = showDtmfKeypad,
                        onClick = { showDtmfKeypad = !showDtmfKeypad }
                    )
                    CallControlButton(
                        icon = routeIcon(call.audio.current?.type ?: AudioRouteType.EARPIECE),
                        label = call.audio.current?.label ?: "Audio",
                        isActive = false,
                        onClick = { showAudioPicker = true }
                    )
                    // The PBX only acts on a REFER from the side that answered,
                    // so a call this phone placed cannot be transferred.
                    if (call.direction == CallDirection.INBOUND) {
                        CallControlButton(
                            icon = Icons.AutoMirrored.Filled.PhoneForwarded,
                            label = "Transfer",
                            isActive = showTransfer,
                            onClick = { showTransfer = true }
                        )
                    }
                    if (canAddCall) {
                        CallControlButton(
                            icon = Icons.Default.AddIcCall,
                            label = "Add call",
                            isActive = showAddCall,
                            onClick = {
                                addCallError = null
                                showAddCall = true
                            }
                        )
                    }
                }
            }

            // Attended transfer: an answered call and a call placed to the
            // transfer target are both up, so the PBX can join them.
            if (transferPair != null) {
                Spacer(modifier = Modifier.height(20.dp))
                CompleteTransfer(
                    pair = transferPair,
                    onComplete = onCompleteTransfer,
                    onDismissError = onDismissTransferError,
                )
            }

            // DTMF Keypad overlay
            if (showDtmfKeypad) {
                Spacer(modifier = Modifier.height(16.dp))
                DtmfKeypad(onDigit = onDtmf)
            }

            Spacer(modifier = Modifier.weight(0.6f))

            if (phase == CallPhase.INCOMING && otherCalls.isNotEmpty()) {
                // Call waiting: answer holds the call in progress, or ends it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    LabeledRoundCallButton(
                        icon = Icons.Default.CallEnd,
                        label = "Decline",
                        color = Color.Red,
                        onClick = onDecline,
                    )
                    LabeledRoundCallButton(
                        icon = Icons.Default.PhoneCallback,
                        label = "End & Answer",
                        color = Color(0xFFF59E0B),
                        onClick = onEndAndAnswer,
                    )
                    LabeledRoundCallButton(
                        icon = Icons.Default.Call,
                        label = "Hold & Answer",
                        color = Color(0xFF22C55E),
                        onClick = onAnswer,
                    )
                }
            } else if (phase == CallPhase.INCOMING) {
                // Answer / decline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundCallButton(
                        icon = Icons.Default.CallEnd,
                        contentDescription = "Decline",
                        color = Color.Red,
                        onClick = onDecline,
                    )
                    RoundCallButton(
                        icon = Icons.Default.Call,
                        contentDescription = "Answer",
                        color = Color(0xFF22C55E),
                        onClick = onAnswer,
                    )
                }
            } else if (phase != CallPhase.ENDED) {
                // End call button
                RoundCallButton(
                    icon = Icons.Default.CallEnd,
                    contentDescription = "End Call",
                    color = Color.Red,
                    onClick = onHangup,
                )
            } else {
                Spacer(modifier = Modifier.height(84.dp))
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

/**
 * Transfer: a number field, or a number picked from contacts. "Transfer" is
 * blind: finding out whether it worked can take up to about 45 s, so the
 * dialog stays up with progress until it does; on success the call ends and
 * the call screen closes. "Consult first" instead calls the target, holding
 * this call, so the user can talk to them before completing the transfer.
 */
@Composable
private fun TransferDialog(
    transferring: Boolean,
    error: String?,
    canConsult: Boolean,
    onTransfer: (String) -> Unit,
    onConsult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NumberEntryDialog(
        title = "Transfer call",
        confirmLabel = "Transfer",
        busy = transferring,
        busyText = "Transferring... This can take up to 45 seconds.",
        error = error,
        onConfirm = onTransfer,
        onDismiss = onDismiss,
        secondaryLabel = if (canConsult) "Consult first" else null,
        onSecondary = onConsult,
    )
}

/** A number field with a contact picker, for transfer and add call. */
@Composable
private fun NumberEntryDialog(
    title: String,
    confirmLabel: String,
    busy: Boolean,
    busyText: String?,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var target by remember { mutableStateOf("") }
    val transferring = busy

    // ACTION_PICK on phone numbers grants read access to the one picked row,
    // so this needs no contacts permission of its own.
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) target = cursor.getString(0).orEmpty()
            }
        } catch (_: Exception) {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Number or extension") },
                    singleLine = true,
                    enabled = !transferring,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        pickContact.launch(
                            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                        )
                    },
                    enabled = !transferring,
                ) {
                    Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose contact")
                }
                if (transferring && busyText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(busyText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Row {
                if (secondaryLabel != null) {
                    TextButton(
                        onClick = { onSecondary(target) },
                        enabled = !transferring && target.isNotBlank(),
                    ) { Text(secondaryLabel) }
                }
                TextButton(
                    onClick = { onConfirm(target) },
                    enabled = !transferring && target.isNotBlank(),
                ) { Text(confirmLabel) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !transferring) { Text("Cancel") }
        },
    )
}

/** A dismissable message at the top of the call screen, e.g. a second call that failed. */
@Composable
private fun CallBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF7F1D1D).copy(alpha = 0.8f),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.White)
            }
        }
    }
}

/**
 * The call that is not on screen: its name and state, Swap (or Resume) to
 * bring it back once both calls are answered, and End.
 */
@Composable
private fun OtherCallRow(
    call: CallUiState,
    foreground: CallUiState,
    onSwap: () -> Unit,
    onHangup: () -> Unit,
) {
    val canResume = CallWaiting.isConnected(call) && call.phase == CallPhase.HELD &&
        foreground.phase != CallPhase.INCOMING
    val swapLabel = if (foreground.phase == CallPhase.ACTIVE) "Swap" else "Resume"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.08f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (call.phase == CallPhase.HELD) Icons.Default.Pause else Icons.Default.Call,
                contentDescription = null,
                tint = if (call.phase == CallPhase.HELD) Color(0xFFFBBF24) else Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    call.displayName.ifEmpty { "Unknown" },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    CallStateMapping.statusText(call.phase, 0).takeIf { call.phase != CallPhase.ACTIVE } ?: "Active",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
            if (canResume) {
                TextButton(onClick = onSwap) {
                    Icon(Icons.Default.SwapCalls, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(swapLabel)
                }
            }
            IconButton(onClick = onHangup) {
                Icon(Icons.Default.CallEnd, contentDescription = "End this call", tint = Color(0xFFEF4444))
            }
        }
    }
}

/** "Complete transfer" for an answered call and a consultation call, with progress and errors. */
@Composable
private fun CompleteTransfer(
    pair: CallWaiting.TransferPair,
    onComplete: () -> Unit,
    onDismissError: () -> Unit,
) {
    val transferring = pair.transferred.transferring || pair.consult.transferring
    val error = pair.transferred.transferError ?: pair.consult.transferError
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onComplete,
            enabled = !transferring,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
        ) {
            if (transferring) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Text("Transferring...")
            } else {
                Icon(Icons.AutoMirrored.Filled.PhoneForwarded, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Complete transfer")
            }
        }
        Text(
            "Connects ${pair.transferred.displayName} with ${pair.consult.displayName}",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            CallBanner(message = it, onDismiss = onDismissError)
        }
    }
}

private fun routeIcon(type: AudioRouteType): ImageVector = when (type) {
    AudioRouteType.EARPIECE -> Icons.Default.PhoneInTalk
    AudioRouteType.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
    AudioRouteType.BLUETOOTH -> Icons.Default.Bluetooth
    AudioRouteType.WIRED_HEADSET -> Icons.Default.Headset
    AudioRouteType.STREAMING -> Icons.Default.Cast
}

@Composable
private fun RoundCallButton(
    icon: ImageVector,
    contentDescription: String,
    color: Color,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
        )
        FloatingActionButton(
            onClick = onClick,
            containerColor = color,
            shape = CircleShape,
            modifier = Modifier.size(72.dp),
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 8.dp,
            ),
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(30.dp),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun LabeledRoundCallButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundCallButton(icon = icon, contentDescription = label, color = color, onClick = onClick)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
private fun AudioOutputOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp)
            if (selected) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) Color.White else Color.White.copy(alpha = 0.12f)
                )
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isActive) Color(0xFF1A1040) else Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun DtmfKeypad(onDigit: (String) -> Unit) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { digit ->
                    Surface(
                        onClick = { onDigit(digit) },
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = digit,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun AudioLevelBar(
    level: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.1f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = level)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/** [CallScreen] driven by [CallManager]: used by the app and by the lock-screen call activity. */
@Composable
fun ManagedCallScreen(call: CallUiState) {
    val context = LocalContext.current
    val calls by CallManager.calls.collectAsState()
    val callError by CallManager.error.collectAsState()
    CallScreen(
        otherCalls = CallWaiting.others(calls, call),
        canAddCall = CallWaiting.canAddCall(call, calls),
        canConsult = CallWaiting.canConsult(call, calls),
        transferPair = CallWaiting.attendedTransferPair(calls),
        callError = callError,
        onEndAndAnswer = { CallManager.endOthersAndAnswer() },
        onSwapTo = { CallManager.swapTo(it) },
        onHangupOther = { CallManager.hangup(it) },
        onAddCall = { CallManager.placeCall(context, it) },
        onConsult = { CallManager.consult(it) },
        onCompleteTransfer = { CallManager.transferAttended() },
        onDismissCallError = { CallManager.clearError() },
        call = call,
        onAnswer = { CallManager.answer() },
        onDecline = { CallManager.reject() },
        onHangup = { CallManager.hangup() },
        onToggleMute = { CallManager.setMuted(!call.muted) },
        onToggleHold = { CallManager.setHold(!call.onHold) },
        onDtmf = { CallManager.sendDtmf(it) },
        onSelectAudioRoute = { CallManager.selectAudioRoute(it) },
        onTransfer = { CallManager.transfer(it) },
        onDismissTransferError = { CallManager.clearTransferError() },
    )
}
