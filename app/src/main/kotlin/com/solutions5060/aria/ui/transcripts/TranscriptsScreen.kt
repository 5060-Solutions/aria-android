package com.solutions5060.aria.ui.transcripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.ai.CallTranscription
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.ui.SecureWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.aria_mobile.AiCallInsight
import uniffi.aria_mobile.AiInsightSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browse transcripts of past calls.
 *
 * The list carries no transcript text — [AiInsightSummary] is deliberately just
 * the row — so opening one is what decrypts it. Everything here reads from the
 * encrypted store in the Rust core; nothing is cached in this layer.
 */
@Composable
fun TranscriptsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val engine = SipEngineHolder.engine
    val scope = rememberCoroutineScope()

    // Transcripts are recordings of calls in text form.
    SecureWindow()

    var rows by remember { mutableStateOf<List<AiInsightSummary>>(emptyList()) }
    var open by remember { mutableStateOf<AiCallInsight?>(null) }
    var loading by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }

    suspend fun refresh() {
        rows = withContext(Dispatchers.IO) {
            // Initialising is what opens the encrypted store, so it has to
            // happen before the first read on a cold start.
            CallTranscription.init(context)
            engine?.aiInsights(100u, 0u) ?: emptyList()
        }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    open?.let { insight ->
        TranscriptDetail(insight = insight, onDismiss = { open = null })
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Call transcripts",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (rows.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
            }
        }

        when {
            loading -> CircularProgressIndicator(Modifier.padding(24.dp))

            rows.isEmpty() -> Text(
                "No transcripts yet. Turn on \"Transcribe my calls\" in " +
                    "Settings › Transcription, and calls from then on will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    TranscriptRow(
                        row = row,
                        onOpen = {
                            scope.launch {
                                open = withContext(Dispatchers.IO) { engine?.aiInsight(row.callId) }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { engine?.aiDeleteInsight(row.callId) }
                                refresh()
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all transcripts?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { engine?.aiClearInsights() }
                        refresh()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TranscriptRow(
    row: AiInsightSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(formatTimestamp(row.createdAt), fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(formatDuration(row.durationSecs.toInt()))
                        // Surface a partial result rather than showing it as if
                        // it were a finished transcript.
                        if (row.status != "complete") append(" · ${row.status}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun TranscriptDetail(insight: AiCallInsight, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTimestamp(insight.createdAt),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Back") }
        }

        insight.summaryHeadline?.let { headline ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(headline, fontWeight = FontWeight.Medium)
                    insight.summaryPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (insight.segments.isEmpty()) {
            Text(
                "No speech was recognised in this call.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        insight.segments.forEach { seg ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    if (seg.speaker == "local") "You" else "Them",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(seg.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun formatTimestamp(epochSeconds: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(epochSeconds * 1000))

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
