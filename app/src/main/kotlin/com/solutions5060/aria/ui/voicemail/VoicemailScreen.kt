package com.solutions5060.aria.ui.voicemail

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solutions5060.aria.voicemail.VoicemailJson
import com.solutions5060.aria.voicemail.VoicemailMessage
import com.solutions5060.aria.voicemail.VoicemailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VoicemailScreen"

/**
 * The user's voicemail from the PBX: list, play, mark read, delete.
 *
 * Audio is streamed with a single-message grant token rather than the
 * session JWT, so the credential in the player's URL can fetch nothing else
 * and expires within minutes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicemailScreen(onCall: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { VoicemailRepository(context) }
    val accessNumber = remember { repository.accessNumber() }

    var messages by remember { mutableStateOf<List<VoicemailMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var preparingId by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<VoicemailMessage?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPlayback() {
        player?.release()
        player = null
        playingId = null
        preparingId = null
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                messages = withContext(Dispatchers.IO) { repository.messages() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load voicemail: ${e.message}")
                error = e.message ?: "Could not load voicemail."
            }
            loading = false
        }
    }

    fun markRead(message: VoicemailMessage) {
        if (message.read) return
        messages = messages.map { if (it.id == message.id) it.copy(read = true) else it }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.markRead(message.id) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark voicemail read: ${e.message}")
            }
        }
    }

    fun play(message: VoicemailMessage) {
        stopPlayback()
        preparingId = message.id
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) { repository.audioUrl(message.id) }
                if (preparingId != message.id) return@launch
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(url)
                    setOnPreparedListener {
                        preparingId = null
                        playingId = message.id
                        it.start()
                        markRead(message)
                    }
                    setOnCompletionListener { stopPlayback() }
                    setOnErrorListener { _, what, _ ->
                        Log.w(TAG, "Playback error $what")
                        error = "Could not play this message."
                        stopPlayback()
                        true
                    }
                    prepareAsync()
                }
                player = mp
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start playback: ${e.message}")
                error = e.message ?: "Could not play this message."
                stopPlayback()
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    confirmDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete voicemail?") },
            text = { Text("The message from ${VoicemailJson.displayCaller(message.callerId)} will be deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (playingId == message.id || preparingId == message.id) stopPlayback()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { repository.delete(message.id) }
                            messages = messages.filterNot { it.id == message.id }
                        } catch (e: Exception) {
                            error = e.message ?: "Could not delete the message."
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Voicemail", style = MaterialTheme.typography.titleLarge)
                },
                actions = {
                    if (accessNumber != null) {
                        IconButton(onClick = { onCall(accessNumber) }) {
                            Icon(Icons.Default.Call, contentDescription = "Call voicemail")
                        }
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            error?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            when {
                loading && messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Voicemail,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (error == null) "No voicemail" else "Voicemail unavailable",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(messages, key = { it.id }) { message ->
                        VoicemailRow(
                            message = message,
                            expanded = expandedId == message.id,
                            playing = playingId == message.id,
                            preparing = preparingId == message.id,
                            onToggle = { expandedId = if (expandedId == message.id) null else message.id },
                            onPlayPause = {
                                if (playingId == message.id || preparingId == message.id) stopPlayback() else play(message)
                            },
                            onCallBack = { onCall(VoicemailJson.displayCaller(message.callerId)) },
                            onDelete = { confirmDelete = message },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicemailRow(
    message: VoicemailMessage,
    expanded: Boolean,
    playing: Boolean,
    preparing: Boolean,
    onToggle: () -> Unit,
    onPlayPause: () -> Unit,
    onCallBack: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Unread marker
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (message.read) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.primary,
                        CircleShape,
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    VoicemailJson.displayCaller(message.callerId),
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 17.sp,
                    fontWeight = if (message.read) FontWeight.Normal else FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${VoicemailJson.displayTime(message.createdAt)} · ${VoicemailJson.displayDuration(message.durationSecs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(48.dp)) {
                if (preparing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        message.transcription?.let { text ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp),
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onCallBack) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Call back")
                }
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }
}
