package com.solutions5060.aria.ui.call

import android.content.Context
import android.media.AudioManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.aria_mobile.CallState
import com.solutions5060.aria.service.SipEngineHolder
import kotlinx.coroutines.delay

@Suppress("DEPRECATION")
@Composable
fun CallScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var isMuted by remember { mutableStateOf(false) }
    var isOnHold by remember { mutableStateOf(false) }
    var isSpeaker by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var callerName by remember { mutableStateOf("") }
    var callerUri by remember { mutableStateOf("") }
    var callId by remember { mutableStateOf<String?>(null) }
    var callState by remember { mutableStateOf(CallState.CONNECTING) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showDtmfKeypad by remember { mutableStateOf(false) }
    var rxLevel by remember { mutableFloatStateOf(0f) }
    var txLevel by remember { mutableFloatStateOf(0f) }

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

    // Observe call state from engine
    LaunchedEffect(Unit) {
        while (true) {
            val activeCall = SipEngineHolder.engine?.getActiveCall()
            if (activeCall != null) {
                callId = activeCall.callId
                callerName = activeCall.remoteName ?: ""
                callerUri = activeCall.remoteUri
                callState = activeCall.state
                isMuted = activeCall.muted
                isOnHold = activeCall.onHold
                // Poll audio levels
                try {
                    rxLevel = SipEngineHolder.engine?.getRxAudioLevel(activeCall.callId) ?: 0f
                    txLevel = SipEngineHolder.engine?.getTxAudioLevel(activeCall.callId) ?: 0f
                } catch (_: Exception) {}
            } else if (callState != CallState.IDLE && callState != CallState.CONNECTING) {
                // Call disappeared (remote hangup or ended)
                callState = CallState.ENDED
                delay(1000) // Brief pause so user sees "Call Ended"
                onDismiss()
                break
            } else if (callState == CallState.ENDED || callState == CallState.IDLE) {
                delay(500)
                onDismiss()
                break
            }
            delay(300)
        }
    }

    // Timer — only counts when connected
    LaunchedEffect(callState) {
        if (callState == CallState.CONNECTED) {
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    // Status text
    val statusText = when (callState) {
        CallState.IDLE -> "Call Ended"
        CallState.INCOMING -> "Incoming Call"
        CallState.RINGING -> "Ringing..."
        CallState.CONNECTING -> "Calling..."
        CallState.CONNECTED -> formatDuration(elapsedSeconds)
        CallState.ON_HOLD -> "On Hold"
        CallState.ENDED -> "Call Ended"
    }

    val isRingingOrConnecting = callState == CallState.RINGING || callState == CallState.CONNECTING
    val isActive = callState == CallState.CONNECTED || callState == CallState.ON_HOLD

    // Display name: prefer remoteName, fall back to URI minus sip: prefix
    val displayName = callerName.ifEmpty {
        callerUri.removePrefix("sip:").substringBefore("@")
    }

    // Audio picker dialog
    if (showAudioPicker) {
        AlertDialog(
            onDismissRequest = { showAudioPicker = false },
            title = { Text("Audio Output", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    AudioOutputOption(
                        label = "Earpiece",
                        icon = Icons.Default.PhoneInTalk,
                        selected = !isSpeaker && !audioManager.isBluetoothScoOn,
                        onClick = {
                            audioManager.isSpeakerphoneOn = false
                            audioManager.stopBluetoothSco()
                            isSpeaker = false
                            showAudioPicker = false
                        },
                    )
                    AudioOutputOption(
                        label = "Speaker",
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        selected = isSpeaker,
                        onClick = {
                            audioManager.isSpeakerphoneOn = true
                            isSpeaker = true
                            showAudioPicker = false
                        },
                    )
                    if (audioManager.isBluetoothScoAvailableOffCall) {
                        AudioOutputOption(
                            label = "Bluetooth",
                            icon = Icons.Default.Bluetooth,
                            selected = audioManager.isBluetoothScoOn,
                            onClick = {
                                audioManager.startBluetoothSco()
                                audioManager.isBluetoothScoOn = true
                                isSpeaker = false
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
                    callState == CallState.ON_HOLD -> Color(0xFFFBBF24) // Amber
                    isActive -> Color(0xFF22C55E) // Green
                    else -> Color.White.copy(alpha = 0.6f)
                },
            )

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
                        onClick = {
                            val id = callId ?: return@CallControlButton
                            val newMuted = !isMuted
                            isMuted = newMuted
                            Thread { SipEngineHolder.engine?.setMute(id, newMuted) }.start()
                        }
                    )
                    CallControlButton(
                        icon = if (isSpeaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                        label = "Speaker",
                        isActive = isSpeaker,
                        onClick = {
                            val newSpeaker = !isSpeaker
                            audioManager.isSpeakerphoneOn = newSpeaker
                            isSpeaker = newSpeaker
                        },
                    )
                    CallControlButton(
                        icon = if (isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                        label = if (isOnHold) "Resume" else "Hold",
                        isActive = isOnHold,
                        onClick = {
                            val id = callId ?: return@CallControlButton
                            val newHold = !isOnHold
                            isOnHold = newHold
                            Thread { SipEngineHolder.engine?.setHold(id, newHold) }.start()
                        }
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
                        icon = Icons.Default.Bluetooth,
                        label = "Audio",
                        isActive = false,
                        onClick = { showAudioPicker = true }
                    )
                }
            }

            // DTMF Keypad overlay
            if (showDtmfKeypad) {
                Spacer(modifier = Modifier.height(16.dp))
                DtmfKeypad(onDigit = { digit ->
                    val id = callId ?: return@DtmfKeypad
                    Thread { SipEngineHolder.engine?.sendDtmf(id, digit) }.start()
                })
            }

            Spacer(modifier = Modifier.weight(0.6f))

            // End call button
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Color.Red.copy(alpha = 0.15f))
                )
                FloatingActionButton(
                    onClick = {
                        audioManager.isSpeakerphoneOn = false
                        val id = callId
                        if (id != null) {
                            Thread { SipEngineHolder.engine?.hangup(id) }.start()
                        }
                        onDismiss()
                    },
                    containerColor = Color.Red,
                    shape = CircleShape,
                    modifier = Modifier.size(72.dp),
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 8.dp,
                    ),
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        modifier = Modifier.size(30.dp),
                        tint = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
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

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
