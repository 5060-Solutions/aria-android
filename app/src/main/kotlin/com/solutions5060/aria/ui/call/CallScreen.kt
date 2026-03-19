package com.solutions5060.aria.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.aria_mobile.CallState
import com.solutions5060.aria.service.SipEngineHolder
import kotlinx.coroutines.delay

@Composable
fun CallScreen(onDismiss: () -> Unit) {
    var isMuted by remember { mutableStateOf(false) }
    var isOnHold by remember { mutableStateOf(false) }
    var isSpeaker by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var callerName by remember { mutableStateOf("Unknown Caller") }
    var callId by remember { mutableStateOf<String?>(null) }
    var isConnected by remember { mutableStateOf(false) }

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
                    icon = Icons.Default.VolumeUp,
                    label = "Speaker",
                    isActive = isSpeaker,
                    onClick = { isSpeaker = !isSpeaker }
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
            }

            Spacer(modifier = Modifier.weight(1f))

            // End call button
            FloatingActionButton(
                onClick = {
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
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
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
