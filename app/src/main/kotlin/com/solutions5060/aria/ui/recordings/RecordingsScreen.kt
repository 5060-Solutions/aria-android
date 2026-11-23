package com.solutions5060.aria.ui.recordings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.rounded.Share
import androidx.core.content.FileProvider
import com.solutions5060.aria.R
import com.solutions5060.aria.recording.Recording
import com.solutions5060.aria.recording.RecordingsRepository
import com.solutions5060.aria.ui.SecureWindow
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.EmptyState
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RecordingsTags {
    const val LIST = "recordings_list"
    const val EMPTY = "recordings_empty"
    const val ROW = "settings_recordings"
}

/**
 * Call recordings kept on this phone (`filesDir/recordings`): play in the
 * system player, share, or delete. Nothing is uploaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    // File names carry the other party's number.
    SecureWindow()
    val context = LocalContext.current
    val repo = remember { RecordingsRepository(context) }
    var version by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }
    LaunchedEffect(version) { list = withContext(Dispatchers.IO) { repo.list() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recordings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        AriaIcon(AriaIcons.Back, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (list.isEmpty()) {
            EmptyState(
                icon = AriaIcons.Recordings,
                title = stringResource(R.string.recordings_empty_title),
                body = stringResource(R.string.recordings_empty_body),
                modifier = Modifier
                    .padding(padding)
                    .testTag(RecordingsTags.EMPTY),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = Spacing.screen)
                    .testTag(RecordingsTags.LIST),
                verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
            ) {
                items(list, key = { it.name }) { r ->
                    val when_ = DateUtils.formatDateTime(
                        context, r.modifiedAtMillis,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                    )
                    val length = r.durationSeconds?.let { DateUtils.formatElapsedTime(it.toLong()) }
                    AriaListItem(
                        headline = r.name.removeSuffix(".wav").substringAfter('-').substringAfter('-').ifEmpty { r.name },
                        supporting = listOfNotNull(when_, length, Formatter.formatShortFileSize(context, r.sizeBytes)).joinToString(" · "),
                        onClick = {
                            val uri = FileProvider.getUriForFile(context, context.packageName + RecordingsRepository.AUTHORITY_SUFFIX, r.file)
                            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/wav")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(view)
                            } catch (_: ActivityNotFoundException) {
                                repo.shareIntent(context, r)?.let(context::startActivity)
                            }
                        },
                        onClickLabel = stringResource(R.string.recordings_play),
                        trailing = {
                            IconButton(onClick = { repo.shareIntent(context, r)?.let(context::startActivity) }) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Rounded.Share,
                                    contentDescription = stringResource(R.string.recordings_share),
                                )
                            }
                            IconButton(onClick = { pendingDelete = r }) {
                                AriaIcon(AriaIcons.Delete, contentDescription = stringResource(R.string.recordings_delete))
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = { Text(stringResource(R.string.recordings_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(r)
                    pendingDelete = null
                    version++
                }) { Text(stringResource(R.string.recordings_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Settings > Audio & recording (DESIGN.md §5 Settings 3): the way into [RecordingsScreen]. */
@Composable
fun RecordingsSection(onOpen: () -> Unit) {
    com.solutions5060.aria.ui.components.RowGroup(stringResource(R.string.settings_audio_recording)) {
        AriaListItem(
            headline = stringResource(R.string.recordings_title),
            supporting = stringResource(R.string.recordings_row_desc),
            leading = { com.solutions5060.aria.ui.components.RowIcon(AriaIcons.Recordings) },
            trailing = { AriaIcon(AriaIcons.ChevronRight, contentDescription = null) },
            onClick = onOpen,
            shape = com.solutions5060.aria.ui.components.RowShape.Pill,
            modifier = Modifier.testTag(RecordingsTags.ROW),
        )
    }
}
