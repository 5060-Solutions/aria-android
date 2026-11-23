package com.solutions5060.aria.ui.settings

import androidx.compose.ui.res.stringResource
import com.solutions5060.aria.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.solutions5060.aria.ai.CallTranscription
import com.solutions5060.aria.service.SipEngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uniffi.aria_mobile.AiDownloadProgress
import uniffi.aria_mobile.AiModel

/**
 * Manage on-device transcription: pick a model, download it, delete it.
 *
 * Transcription runs entirely on the handset. Call audio is tapped inside the
 * Rust core, where it is already decoded, and never reaches this layer — the
 * screen only ever deals with model management and finished text.
 */
@Composable
fun TranscriptionScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val engine = SipEngineHolder.engine

    // `ai_available()` is a runtime probe rather than a build-flavour guess, so
    // one APK can ship with or without the models compiled in.
    val supported = remember { engine?.aiAvailable() ?: false }

    var initError by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<AiModel>>(emptyList()) }
    var progress by remember { mutableStateOf<Map<String, AiDownloadProgress>>(emptyMap()) }
    var transcribeCalls by remember { mutableStateOf(CallTranscription.isEnabled(context)) }

    // Models live under the app's own files directory, which the OS clears on
    // uninstall — the core never picks a path itself.
    LaunchedEffect(supported) {
        if (!supported || engine == null) return@LaunchedEffect
        try {
            CallTranscription.init(context)
            models = engine.aiModels()
        } catch (e: Exception) {
            initError = e.message ?: "could not start on-device AI"
        }
    }

    // Downloads report progress by polling rather than pushing, so this is the
    // UI's own timer rather than a callback across the FFI boundary.
    LaunchedEffect(models) {
        if (engine == null) return@LaunchedEffect
        while (true) {
            val active = models.filter { !it.installed }
            if (active.isNotEmpty()) {
                progress = active.mapNotNull { m ->
                    engine.aiDownloadProgress(m.id)?.let { m.id to it }
                }.toMap()
                if (progress.values.any { it.state == "completed" }) {
                    models = engine.aiModels()
                }
            }
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.transcription_title), style = MaterialTheme.typography.headlineSmall)

        Text(
            stringResource(R.string.transcription_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!supported) {
            // Say plainly that this build cannot do it, rather than showing an
            // empty list that looks like a loading failure.
            Card(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.transcription_unsupported),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        initError?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.transcription_start_failed, it),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Capturing call audio is a recording, so it stays off until the user
        // asks for it — having a model installed is not consent on its own.
        val sttInstalled = models.any { it.kind == "stt" && it.installed }
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.transcription_toggle), fontWeight = FontWeight.Medium)
                    Text(
                        if (sttInstalled) {
                            stringResource(R.string.transcription_toggle_on)
                        } else {
                            stringResource(R.string.transcription_toggle_needs_model)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = transcribeCalls && sttInstalled,
                    enabled = sttInstalled,
                    onCheckedChange = {
                        transcribeCalls = it
                        CallTranscription.setEnabled(context, it)
                    },
                )
            }
        }

        if (models.isEmpty() && initError == null) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        }

        models.forEach { model ->
            ModelRow(
                model = model,
                progress = progress[model.id],
                onDownload = { engine?.aiStartDownload(model.id) },
                onCancel = { engine?.aiCancelDownload(model.id) },
                onDelete = {
                    engine?.aiDeleteModel(model.id)
                    models = engine?.aiModels() ?: emptyList()
                    // Without a speech model there is nothing to capture for.
                    if (models.none { it.kind == "stt" && it.installed }) {
                        transcribeCalls = false
                        CallTranscription.setEnabled(context, false)
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: AiModel,
    progress: AiDownloadProgress?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, fontWeight = FontWeight.Medium)
                    Text(
                        "${if (model.kind == "llm") stringResource(R.string.transcription_kind_summaries) else stringResource(R.string.transcription_kind_speech)} · " +
                            humanBytes(model.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val downloading = progress != null &&
                    progress.state != "completed" && progress.state != "failed"

                when {
                    model.installed -> TextButton(onClick = onDelete) { Text(stringResource(R.string.transcription_remove)) }
                    downloading -> TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    // A model this phone cannot run stays visible with the
                    // reason attached: users understand a locked door, but not
                    // an option that silently disappeared.
                    !model.available -> Text(
                        stringResource(R.string.transcription_unavailable),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.transcription_download)) }
                }
            }

            if (!model.available && model.unavailableReason != null) {
                Text(
                    model.unavailableReason!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            progress?.let { p ->
                if (p.state != "completed") {
                    val fraction =
                        if (p.totalBytes > 0uL) {
                            p.downloadedBytes.toFloat() / p.totalBytes.toFloat()
                        } else {
                            0f
                        }
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        p.error ?: "${humanBytes(p.downloadedBytes)} of ${humanBytes(p.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun humanBytes(n: ULong): String {
    val units = listOf("B", "KB", "MB", "GB")
    var v = n.toDouble()
    var u = 0
    while (v >= 1024 && u < units.lastIndex) {
        v /= 1024
        u++
    }
    return if (u == 0) "$n B" else String.format("%.1f %s", v, units[u])
}
