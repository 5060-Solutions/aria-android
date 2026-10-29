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
import android.os.SystemClock
import com.solutions5060.aria.call.AudioRouteOption
import com.solutions5060.aria.call.AudioRouteType
import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallStateMapping
import com.solutions5060.aria.call.CallUiState
import com.solutions5060.aria.ui.SecureWindow
import com.solutions5060.aria.service.SipEngineHolder
import kotlinx.coroutines.delay

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
) {
    val isSpeaker = call.audio.isSpeaker

    // Shown over the lock screen for incoming calls.
    SecureWindow()
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var showAudioPicker by remember { mutableStateOf(false) }
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
                }
            }

            // DTMF Keypad overlay
            if (showDtmfKeypad) {
                Spacer(modifier = Modifier.height(16.dp))
                DtmfKeypad(onDigit = onDtmf)
            }

            Spacer(modifier = Modifier.weight(0.6f))

            if (phase == CallPhase.INCOMING) {
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
