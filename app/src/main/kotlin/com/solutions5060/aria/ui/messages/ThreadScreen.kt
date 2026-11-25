package com.solutions5060.aria.ui.messages

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.solutions5060.aria.R
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.messaging.DeliveryStatus
import com.solutions5060.aria.messaging.DraftKeys
import com.solutions5060.aria.messaging.Inbox
import com.solutions5060.aria.messaging.MessageNumbers
import com.solutions5060.aria.messaging.MessagesMonitor
import com.solutions5060.aria.messaging.MessagingJson
import com.solutions5060.aria.messaging.MessagingNotifier
import com.solutions5060.aria.messaging.MessagingRepository
import com.solutions5060.aria.messaging.PhoneLinks
import com.solutions5060.aria.messaging.PrefsUnreadStore
import com.solutions5060.aria.messaging.SmsSegments
import com.solutions5060.aria.messaging.MessageThread
import com.solutions5060.aria.messaging.ThreadMessage
import com.solutions5060.aria.messaging.UnreadTracker
import com.solutions5060.aria.service.PbxApiClient
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaScreen
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.PhoneFormat
import com.solutions5060.aria.ui.contacts.ContactsSource
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ThreadScreen"

/** A photo chosen for the next message; [uploadedId] is set once the PBX holds it. */
private class PendingPhoto(val photo: PreparedPhoto, var uploadedId: String? = null)

/**
 * One conversation: the messages (newest at the bottom), each outbound one
 * with its delivery status and the server's error when it failed, and the
 * composer with a segment counter and one photo. Numbers in a message are
 * links that call. Reloads on every event the socket delivers.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ThreadScreen(didId: String, number: String, onBack: (() -> Unit)?, onCall: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    val repository = remember { if (Fixtures.current == null) MessagingRepository.from(context) else null }
    val tracker = remember { UnreadTracker(PrefsUnreadStore(context)) }
    val sendFailedText = stringResource(R.string.messages_send_failed)
    val loadFailedText = stringResource(R.string.messages_load_failed)
    val photoFailedText = stringResource(R.string.messages_photo_failed)
    val photoTooLargeText = stringResource(R.string.messages_photo_too_large)

    var thread by remember { mutableStateOf<MessageThread?>(null) }
    var inbox by remember { mutableStateOf<Inbox?>(null) }
    var loading by remember { mutableStateOf(repository != null) }
    var text by rememberSaveable { mutableStateOf("") }
    var photo by remember { mutableStateOf<PendingPhoto?>(null) }
    var sending by remember { mutableStateOf(false) }
    // The sender's own key: kept across a request that got no answer (so a retry is not sent twice), new after any answer.
    val draftKeys = remember { DraftKeys(PrefsUnreadStore(context)) }
    val listState = rememberLazyListState()

    val contactName by produceState<String?>(null, number) {
        value = withContext(Dispatchers.IO) {
            runCatching { ContactsSource.match(number, ContactsSource.load(context))?.name }.getOrNull()
        }
    }
    val title = contactName ?: PhoneFormat.display(number)

    fun reload() {
        if (repository == null) return
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val boxes = if (inbox == null) repository.inboxes() else null
                    boxes to repository.thread(didId, number)
                }
                loaded.first?.let { boxes -> inbox = boxes.firstOrNull { it.didId == didId } }
                thread = loaded.second
                loaded.second.messages.firstOrNull()?.let { tracker.markRead(didId, number, it.createdAt) }
                MessagingNotifier.cancel(context, didId, number)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load thread: ${e.javaClass.simpleName}")
                if (thread == null) snackbar.showSnackbar(e.message ?: loadFailedText)
            }
            loading = false
        }
    }

    LaunchedEffect(didId, number) { reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload() }
    LaunchedEffect(didId, number) {
        MessagesMonitor.events.collect { event ->
            if (event.didId == didId && event.remoteNumber == number) reload()
        }
    }
    // A new message at the bottom scrolls into view.
    LaunchedEffect(thread?.messages?.firstOrNull()?.id) { if (thread != null) listState.animateScrollToItem(0) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val prepared = withContext(Dispatchers.IO) { runCatching { Photos.prepare(context, uri) }.getOrNull() }
            if (prepared == null) snackbar.showSnackbar(photoFailedText) else photo = PendingPhoto(prepared)
        }
    }

    val canSend = inbox?.enabled == true && thread?.optedOut != true && !MessageNumbers.isShortCode(number)
    val count = SmsSegments.count(text.trim())

    fun send() {
        if (repository == null || sending) return
        val body = text.trim()
        val pending = photo
        sending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (pending != null && pending.uploadedId == null) {
                        pending.uploadedId = repository.upload(pending.photo.bytes, pending.photo.contentType, pending.photo.fileName).id
                    }
                    repository.send(didId, number, body, listOfNotNull(pending?.uploadedId), draftKeys.current(didId, number))
                }
                draftKeys.renew(didId, number)
                text = ""
                photo = null
                reload()
            } catch (e: Exception) {
                if (e is PbxApiClient.HttpException) {
                    // The PBX answered: a failed send is kept in the thread with its reason, so show it, and the next send is a new one.
                    if (DraftKeys.isDefinite(e.code)) draftKeys.renew(didId, number)
                    Log.w(TAG, "Send refused: ${e.code}")
                    snackbar.showSnackbar(if (e.code == 413) photoTooLargeText else e.message ?: sendFailedText)
                    reload()
                } else {
                    Log.w(TAG, "Send failed: ${e.javaClass.simpleName}")
                    snackbar.showSnackbar(sendFailedText)
                }
            }
            sending = false
        }
    }

    AriaScreen(
        title = title,
        subtitle = inbox?.number?.let { PhoneFormat.display(it) },
        onBack = onBack,
        actions = {
            if (MessageNumbers.isE164(number)) {
                IconButton(onClick = { onCall(number) }) {
                    AriaIcon(AriaIcons.Call, stringResource(R.string.messages_call, PhoneFormat.display(number)))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Box(Modifier.weight(1f)) {
                val messages = thread?.messages.orEmpty()
                if (loading && thread == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Spacing.screen,
                            vertical = Spacing.xs,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items(messages, key = { it.id }) { message ->
                            Bubble(message, repository, onCall)
                        }
                    }
                }
            }
            thread?.takeIf { it.optedOut }?.let { Notice(stringResource(R.string.messages_opted_out)) }
            if (MessageNumbers.isShortCode(number)) Notice(stringResource(R.string.messages_short_code))
            if (inbox != null && inbox?.enabled == false) Notice(stringResource(R.string.messages_inbox_off))
            if (canSend) {
                Composer(
                    text = text,
                    onText = { text = it },
                    photo = photo,
                    onPickPhoto = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemovePhoto = { photo = null },
                    count = count,
                    sending = sending,
                    canSend = (text.isNotBlank() || photo != null) && !count.tooLong && text.trim().length <= SmsSegments.MAX_BODY_CHARS,
                    onSend = ::send,
                )
            }
        }
    }
}

@Composable
private fun Notice(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
    )
}

@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    photo: PendingPhoto?,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    count: SmsSegments.Count,
    sending: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)) {
            if (photo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AriaIcon(AriaIcons.Photo, null, size = Sizes.iconSmall)
                    Text(
                        stringResource(R.string.messages_photo_attached),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = Spacing.xs),
                    )
                    IconButton(onClick = onRemovePhoto) {
                        AriaIcon(AriaIcons.Close, stringResource(R.string.messages_remove_photo))
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                IconButton(onClick = onPickPhoto, enabled = photo == null && !sending) {
                    AriaIcon(
                        AriaIcons.Photo,
                        stringResource(if (photo == null) R.string.messages_attach_photo else R.string.messages_one_photo),
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.messages_input_hint)) },
                    maxLines = 5,
                    shape = AriaShapes.contentRow,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                FilledIconButton(onClick = onSend, enabled = canSend && !sending, modifier = Modifier.padding(bottom = Spacing.xxs)) {
                    AriaIcon(AriaIcons.Send, stringResource(R.string.messages_send))
                }
            }
            if (text.isNotEmpty()) {
                val segments = pluralStringResource(R.plurals.messages_segments, count.segments, count.segments)
                Text(
                    if (count.tooLong) {
                        stringResource(R.string.messages_too_long, SmsSegments.MAX_SEGMENTS)
                    } else {
                        stringResource(R.string.messages_counter, count.units, count.perSegment, segments)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (count.tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.xxxl, top = Spacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun Bubble(message: ThreadMessage, repository: MessagingRepository?, onCall: (String) -> Unit) {
    val outbound = message.outbound
    val container = if (outbound) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (outbound) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val linkColor = if (outbound) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (outbound) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = container,
            contentColor = content,
            shape = AriaShapes.contentRow,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                message.attachments.forEach { attachment ->
                    if (attachment.isImage && repository != null) {
                        AttachmentImage(repository, attachment, stringResource(R.string.messages_photo_description))
                    } else {
                        Text(
                            stringResource(R.string.messages_attachment_file, attachment.contentType),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (message.body.isNotEmpty()) {
                    Text(linkified(message.body, linkColor, onCall), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val failed = message.status == DeliveryStatus.FAILED
        val status = if (!outbound) {
            null
        } else if (failed) {
            message.errorMessage?.let { stringResource(R.string.messages_status_failed_with, it) }
                ?: stringResource(R.string.messages_status_failed)
        } else {
            stringResource(
                when (message.status) {
                    DeliveryStatus.PENDING -> R.string.messages_status_pending
                    DeliveryStatus.DELIVERED -> R.string.messages_status_delivered
                    else -> R.string.messages_status_sent
                },
            )
        }
        val time = MessagingJson.displayTime(message.createdAt)
        Text(
            if (status != null) "$time · $status" else time,
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xxs, vertical = 2.dp),
        )
    }
}

/** [body] with each phone number in it a link that calls. */
private fun linkified(body: String, color: androidx.compose.ui.graphics.Color, onCall: (String) -> Unit): AnnotatedString {
    val matches = PhoneLinks.find(body)
    if (matches.isEmpty()) return AnnotatedString(body)
    return buildAnnotatedString {
        var at = 0
        matches.forEach { m ->
            append(body.substring(at, m.start))
            withLink(
                LinkAnnotation.Clickable(
                    tag = m.number,
                    styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { MessageNumbers.toE164(m.number)?.let(onCall) },
                ),
            ) { append(body.substring(m.start, m.end)) }
            at = m.end
        }
        append(body.substring(at))
    }
}
