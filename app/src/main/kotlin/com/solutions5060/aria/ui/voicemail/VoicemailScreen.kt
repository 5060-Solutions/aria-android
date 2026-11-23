package com.solutions5060.aria.ui.voicemail

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.ui.SecureWindow
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonSize
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.AriaScreen
import com.solutions5060.aria.ui.components.Avatar
import com.solutions5060.aria.ui.components.ConnectedToggleGroup
import com.solutions5060.aria.ui.components.EmptyState
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.ToggleOption
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import com.solutions5060.aria.voicemail.VoicemailJson
import com.solutions5060.aria.voicemail.VoicemailMessage
import com.solutions5060.aria.voicemail.VoicemailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "VoicemailScreen"

/** m:ss for the player. */
fun playerTime(ms: Int): String = VoicemailJson.displayDuration(ms / 1000)

/**
 * DESIGN.md §5 Voicemail: rows with an unread dot, caller, duration and
 * date. Expanding a row shows the inline player (scrubber, 1×/1.5×/2×,
 * speaker toggle), the transcription, and Call back / Delete.
 *
 * Audio streams with a single-message grant token rather than the session
 * JWT, so the credential in the player's URL can fetch nothing else and
 * expires within minutes.
 */
@Composable
fun VoicemailScreen(onCall: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current

    // Caller ids and transcribed messages.
    SecureWindow()
    val repository = remember { VoicemailRepository(context) }
    val accessNumber = remember { repository.accessNumber() }
    val fixture = Fixtures.current
    val loadFailedText = stringResource(R.string.ui_voicemail_load_failed)
    val playFailedText = stringResource(R.string.ui_voicemail_play_failed)
    val deleteFailedText = stringResource(R.string.ui_voicemail_delete_failed)

    var messages by remember { mutableStateOf(fixture?.voicemail ?: emptyList()) }
    var loading by remember { mutableStateOf(fixture == null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var expandedId by rememberSaveable { mutableStateOf(fixture?.voicemailPlayingId) }
    var confirmDelete by remember { mutableStateOf<VoicemailMessage?>(null) }
    val player = remember { VoicemailPlayer(context) }
    val contacts by androidx.compose.runtime.produceState(emptyList<com.solutions5060.aria.ui.contacts.Contact>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { com.solutions5060.aria.ui.contacts.ContactsSource.load(context) }.getOrDefault(emptyList())
        }
    }

    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.tick()
            delay(250)
        }
    }
    LaunchedEffect(Unit) {
        val playingId = fixture?.voicemailPlayingId ?: return@LaunchedEffect
        val message = messages.firstOrNull { it.id == playingId } ?: return@LaunchedEffect
        player.showFixture(playingId, atMs = 12_000, totalMs = message.durationSecs * 1000)
    }

    fun markRead(message: VoicemailMessage) {
        if (message.read) return
        messages = messages.map { if (it.id == message.id) it.copy(read = true) else it }
        if (fixture != null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repository.markRead(message.id) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mark voicemail read: ${e.javaClass.simpleName}")
            }
        }
    }

    player.onError = { message -> scope.launch { snackbar.showSnackbar(message) } }
    player.onStarted = { id -> messages.firstOrNull { it.id == id }?.let(::markRead) }

    fun refresh() {
        if (fixture != null) return
        scope.launch {
            loading = true
            loadError = null
            try {
                messages = withContext(Dispatchers.IO) { repository.messages() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load voicemail: ${e.javaClass.simpleName}")
                loadError = e.message ?: loadFailedText
            }
            loading = false
        }
    }

    fun playPause(message: VoicemailMessage) {
        expandedId = message.id
        if (fixture != null) {
            player.toggleFixture(message.id, message.durationSecs * 1000)
            markRead(message)
            return
        }
        when {
            player.messageId == message.id && player.playing -> player.pause()
            player.messageId == message.id && !player.preparing -> player.resume()
            else -> scope.launch {
                try {
                    val url = withContext(Dispatchers.IO) { repository.audioUrl(message.id) }
                    player.play(message.id, url)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get audio: ${e.javaClass.simpleName}")
                    snackbar.showSnackbar(e.message ?: playFailedText)
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    confirmDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { AriaIcon(AriaIcons.Delete, null) },
            title = { Text(stringResource(R.string.ui_voicemail_delete_title)) },
            text = { Text(stringResource(R.string.ui_voicemail_delete_body, VoicemailJson.displayCaller(message.callerId))) },
            shape = AriaShapes.card,
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (player.messageId == message.id) player.stop()
                    if (fixture != null) {
                        messages = messages.filterNot { it.id == message.id }
                        return@TextButton
                    }
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { repository.delete(message.id) }
                            messages = messages.filterNot { it.id == message.id }
                        } catch (e: Exception) {
                            snackbar.showSnackbar(e.message ?: deleteFailedText)
                        }
                    }
                }) { Text(stringResource(R.string.ui_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    AriaScreen(
        title = stringResource(R.string.ui_tab_voicemail),
        actions = {
            if (accessNumber != null) {
                IconButton(onClick = { onCall(accessNumber) }) {
                    AriaIcon(AriaIcons.Call, stringResource(R.string.ui_voicemail_call_box))
                }
            }
            IconButton(onClick = { refresh() }, enabled = !loading) {
                AriaIcon(AriaIcons.Refresh, stringResource(R.string.ui_refresh))
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading && messages.isEmpty() -> LoadingIndicatorCentered()
                messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (loadError != null) {
                        EmptyState(
                            icon = AriaIcons.Offline,
                            title = stringResource(R.string.ui_voicemail_unavailable),
                            body = loadError ?: "",
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = { refresh() },
                            actionStyle = AriaButtonStyle.Standard,
                        )
                    } else {
                        EmptyState(
                            icon = AriaIcons.Voicemail,
                            title = stringResource(R.string.ui_voicemail_empty),
                            body = stringResource(R.string.ui_voicemail_empty_body),
                        )
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
                ) {
                    items(messages, key = { it.id }) { message ->
                        VoicemailRow(
                            message = message,
                            caller = callerName(message, contacts),
                            expanded = expandedId == message.id,
                            player = player,
                            onToggle = { expandedId = if (expandedId == message.id) null else message.id },
                            onPlayPause = { playPause(message) },
                            onCallBack = { onCall(VoicemailJson.displayCaller(message.callerId)) },
                            onDelete = { confirmDelete = message },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingIndicatorCentered() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoicemailRow(
    message: VoicemailMessage,
    caller: String,
    expanded: Boolean,
    player: VoicemailPlayer,
    onToggle: () -> Unit,
    onPlayPause: () -> Unit,
    onCallBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = player.messageId == message.id
    val playing = loaded && player.playing
    val preparing = loaded && player.preparing
    val unreadLabel = stringResource(R.string.ui_voicemail_unread_marker)
    AriaListItem(
        modifier = modifier,
        headline = caller,
        emphasized = !message.read,
        supporting = "${VoicemailJson.displayDuration(message.durationSecs)} · ${VoicemailJson.displayTime(message.createdAt)}",
        leading = {
            Box {
                Avatar(name = caller, key = message.callerId)
                if (!message.read) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(Sizes.badgeDot + 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                            .padding(2.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .semantics { contentDescription = unreadLabel },
                    )
                }
            }
        },
        trailing = {
            PlayButton(playing = playing, preparing = preparing, onClick = onPlayPause, caller = caller)
        },
        onClick = onToggle,
        onClickLabel = stringResource(if (expanded) R.string.ui_collapse else R.string.ui_expand),
        below = {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    InlinePlayer(message, player, loaded)
                    message.transcription?.let { text ->
                        Surface(shape = AriaShapes.contentRow, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                                    AriaIcon(AriaIcons.Transcript, null, size = Sizes.iconSmall, tint = MaterialTheme.colorScheme.tertiary)
                                    Text(
                                        stringResource(R.string.ui_voicemail_transcription),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                Text(text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        AriaButton(
                            stringResource(R.string.ui_history_call_back),
                            onCallBack,
                            style = AriaButtonStyle.Standard,
                            icon = AriaIcons.Call,
                            modifier = Modifier.weight(1f),
                        )
                        AriaButton(
                            stringResource(R.string.ui_delete),
                            onDelete,
                            style = AriaButtonStyle.Destructive,
                            size = AriaButtonSize.Secondary,
                            icon = AriaIcons.Delete,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayButton(playing: Boolean, preparing: Boolean, onClick: () -> Unit, caller: String) {
    val label = stringResource(if (playing) R.string.ui_voicemail_pause else R.string.ui_voicemail_play, caller)
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(Sizes.iconButton),
        shapes = androidx.compose.material3.IconButtonDefaults.shapes(),
    ) {
        if (preparing) {
            LoadingIndicator(Modifier.size(Sizes.icon), color = MaterialTheme.colorScheme.onPrimary)
        } else {
            AriaIcon(if (playing) AriaIcons.Hold else AriaIcons.Play, label)
        }
    }
}

@Composable
private fun InlinePlayer(message: VoicemailMessage, player: VoicemailPlayer, loaded: Boolean) {
    val total = if (loaded && player.durationMs > 0) player.durationMs else message.durationSecs * 1000
    val position = if (loaded) player.positionMs.coerceAtMost(total) else 0
    val positionLabel = stringResource(R.string.ui_voicemail_position, playerTime(position), playerTime(total))
    Column {
        Slider(
            value = if (total > 0) position / total.toFloat() else 0f,
            onValueChange = { if (loaded) player.seekTo((it * total).toInt()) },
            enabled = loaded,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = positionLabel },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playerTime(position), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(playerTime(total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ConnectedToggleGroup(
                options = VoicemailSpeeds.map { ToggleOption(speedLabel(it)) },
                selectedIndex = VoicemailSpeeds.indexOf(player.speed).coerceAtLeast(0),
                onSelect = { player.changeSpeed(VoicemailSpeeds[it]) },
            )
            val speakerLabel = stringResource(R.string.ui_call_speaker)
            val on = stringResource(R.string.ui_state_on)
            val off = stringResource(R.string.ui_state_off)
            FilledTonalIconToggleButton(
                checked = player.speaker,
                onCheckedChange = { player.changeSpeaker(it) },
                modifier = Modifier
                    .size(Sizes.iconButton)
                    .semantics { stateDescription = if (player.speaker) on else off },
            ) {
                AriaIcon(if (player.speaker) AriaIcons.Speaker else AriaIcons.Earpiece, speakerLabel)
            }
        }
    }
}

/** The address book's name for a voicemail's caller, else the number formatted for reading. */
internal fun callerName(message: VoicemailMessage, contacts: List<com.solutions5060.aria.ui.contacts.Contact>): String {
    val number = VoicemailJson.displayCaller(message.callerId)
    return com.solutions5060.aria.ui.contacts.ContactsSource.match(number, contacts)?.name
        ?: com.solutions5060.aria.ui.components.PhoneFormat.display(number)
}
