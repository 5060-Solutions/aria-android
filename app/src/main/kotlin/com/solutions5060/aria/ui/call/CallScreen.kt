package com.solutions5060.aria.ui.call

import android.content.Context
import android.media.AudioManager
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
    var callerName by remember { mutableStateOf("Unknown Caller") }
    var callId by remember { mutableStateOf<String?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }

    // Observe call state from engine
    LaunchedEffect(Unit) {
        while (true) {
            val activeCall = SipEngineHolder.engine?.getActiveCall()
            if (activeCall != null) {
                callId = activeCall.callId
                callerName = activeCall.remoteName ?: activeCall.remoteUri
                isConnected = activeCall.state == CallState.CONNECTED
                isMuted = activeCall.muted
                isOnHold = activeCall.onHold
            } else {
                onDismiss()
                break
            }
            delay(500)
        }
    }

    // Timer
    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    // Audio picker dialog
    if (showAudioPicker) {
        AlertDialog(
            onDismissRequest = { showAudioPicker = false },
            title = { Text("Audio Output") },
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
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            // Avatar
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Caller name
            Text(
                text = callerName,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Duration
            Text(
                text = formatDuration(elapsedSeconds),
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CallControlButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = "Mute",
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
                    onLongClick = { showAudioPicker = true },
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

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CallControlButton(
                    icon = Icons.Default.Dialpad,
                    label = "Keypad",
                    isActive = false,
                    onClick = { /* Show DTMF keypad */ }
                )
                CallControlButton(
                    icon = Icons.Default.Bluetooth,
                    label = "Audio",
                    isActive = false,
                    onClick = { showAudioPicker = true }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // End call button
            FloatingActionButton(
                onClick = {
                    // Reset audio
                    audioManager.isSpeakerphoneOn = false
                    val id = callId
                    if (id != null) {
                        Thread { SipEngineHolder.engine?.hangup(id) }.start()
                    }
                    onDismiss()
                },
                containerColor = Color.Red,
                shape = CircleShape,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = "End Call",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
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
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (selected) {
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
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
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) Color.White else Color.White.copy(alpha = 0.2f)
                )
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (isActive) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
