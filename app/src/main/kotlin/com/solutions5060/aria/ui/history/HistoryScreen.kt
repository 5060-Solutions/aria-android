package com.solutions5060.aria.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class CallHistoryEntry(
    val id: String,
    val remoteUri: String,
    val remoteName: String?,
    val direction: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val missed: Boolean,
) {
    val displayName: String get() = remoteName ?: remoteUri

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("remoteUri", remoteUri)
        put("remoteName", remoteName ?: JSONObject.NULL)
        put("direction", direction)
        put("timestamp", timestamp)
        put("durationSeconds", durationSeconds)
        put("missed", missed)
    }

    companion object {
        fun fromJson(json: JSONObject): CallHistoryEntry = CallHistoryEntry(
            id = json.getString("id"),
            remoteUri = json.getString("remoteUri"),
            remoteName = json.optString("remoteName").takeIf { it.isNotEmpty() && it != "null" },
            direction = json.getString("direction"),
            timestamp = json.getLong("timestamp"),
            durationSeconds = json.getInt("durationSeconds"),
            missed = json.getBoolean("missed"),
        )
    }
}

fun loadHistory(context: android.content.Context): List<CallHistoryEntry> {
    val prefs = context.getSharedPreferences("aria_history", android.content.Context.MODE_PRIVATE)
    val json = prefs.getString("call_history", null) ?: return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).map { CallHistoryEntry.fromJson(array.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }
}

fun saveHistory(context: android.content.Context, history: List<CallHistoryEntry>) {
    val prefs = context.getSharedPreferences("aria_history", android.content.Context.MODE_PRIVATE)
    val array = JSONArray().apply { history.forEach { put(it.toJson()) } }
    prefs.edit().putString("call_history", array.toString()).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onCall: (String) -> Unit) {
    val context = LocalContext.current
    var history by remember { mutableStateOf(loadHistory(context)) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("History") })
        }
    ) { padding ->
        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No call history",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        "Your recent calls will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(history) { entry ->
                    HistoryRow(entry = entry, onCall = { onCall(entry.remoteUri) })
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: CallHistoryEntry, onCall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCall)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Direction icon
        val (icon, color) = when {
            entry.missed -> Icons.AutoMirrored.Filled.CallMissed to Color.Red
            entry.direction == "inbound" -> Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.primary
            else -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.tertiary
        }

        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (entry.missed) Color.Red else MaterialTheme.colorScheme.onSurface
            )
            Text(
                formatDuration(entry.durationSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        IconButton(onClick = onCall) {
            Icon(
                Icons.Default.Call,
                contentDescription = "Call",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds == 0) return "Missed"
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}
