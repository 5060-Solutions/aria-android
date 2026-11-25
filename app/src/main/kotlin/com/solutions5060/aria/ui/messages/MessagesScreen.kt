package com.solutions5060.aria.ui.messages

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.solutions5060.aria.R
import com.solutions5060.aria.ui.adaptive.ListDetail
import com.solutions5060.aria.ui.adaptive.isWideWindow
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.messaging.Conversation
import com.solutions5060.aria.messaging.Inbox
import com.solutions5060.aria.messaging.MessageNumbers
import com.solutions5060.aria.messaging.MessagesMonitor
import com.solutions5060.aria.messaging.MessagingJson
import com.solutions5060.aria.messaging.MessagingRepository
import com.solutions5060.aria.messaging.PrefsUnreadStore
import com.solutions5060.aria.messaging.UnreadTracker
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.AriaScreen
import com.solutions5060.aria.ui.components.Avatar
import com.solutions5060.aria.ui.components.EmptyState
import com.solutions5060.aria.ui.components.PhoneFormat
import com.solutions5060.aria.ui.contacts.Contact
import com.solutions5060.aria.ui.contacts.ContactsSource
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MessagesScreen"

/**
 * The Messages tab: the seat's messaging numbers (chips when there is more
 * than one), the conversations of the chosen one with an unread dot, and a
 * button for a new conversation by number. Refreshes on every event the
 * socket delivers and when the screen is shown again.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MessagesScreen(
    onOpenThread: (didId: String, number: String) -> Unit,
    onCall: (String) -> Unit = {},
    /** A conversation to show at once on a wide window (a notification's link). */
    initialThread: Pair<String, String>? = null,
) {
    // Medium and expanded windows: the conversation opens beside the list.
    val twoPane = isWideWindow()
    var openNumber by rememberSaveable { mutableStateOf(initialThread?.second) }
    var openDid by rememberSaveable { mutableStateOf(initialThread?.first) }
    fun open(did: String, number: String) {
        if (twoPane) {
            openDid = did
            openNumber = number
        } else {
            onOpenThread(did, number)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { if (Fixtures.current == null) MessagingRepository.from(context) else null }
    val tracker = remember { UnreadTracker(PrefsUnreadStore(context)) }
    val loadFailedText = stringResource(R.string.messages_load_failed)

    var inboxes by remember { mutableStateOf<List<Inbox>>(emptyList()) }
    var selectedDid by rememberSaveable { mutableStateOf<String?>(null) }
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var unread by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(repository != null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var newDialog by rememberSaveable { mutableStateOf(false) }
    val contacts by produceState(emptyList<Contact>()) {
        value = withContext(Dispatchers.IO) { runCatching { ContactsSource.load(context) }.getOrDefault(emptyList()) }
    }

    val inbox = inboxes.firstOrNull { it.didId == selectedDid } ?: inboxes.firstOrNull()

    fun refresh() {
        if (repository == null) return
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val boxes = repository.inboxes()
                    val chosen = boxes.firstOrNull { it.didId == selectedDid } ?: boxes.firstOrNull()
                    Triple(boxes, chosen, chosen?.let { repository.conversations(it.didId) })
                }
                val (boxes, chosen, list) = loaded
                inboxes = boxes
                if (chosen != null && list != null) {
                    selectedDid = chosen.didId
                    conversations = list
                    unread = tracker.observe(chosen.didId, list)
                } else {
                    conversations = emptyList()
                    unread = emptySet()
                }
                loadError = null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load messages: ${e.javaClass.simpleName}")
                loadError = e.message ?: loadFailedText
            }
            loading = false
            MessagesMonitor.refresh(context)
        }
    }

    LaunchedEffect(selectedDid) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(Unit) { MessagesMonitor.events.collect { refresh() } }

    if (newDialog && inbox != null) {
        NewConversationDialog(
            onDismiss = { newDialog = false },
            onStart = { number ->
                newDialog = false
                open(inbox.didId, number)
            },
        )
    }

    val listPane: @Composable () -> Unit = { AriaScreen(
        title = stringResource(R.string.ui_tab_messages),
        subtitle = inbox?.number?.let { PhoneFormat.display(it) },
        actions = {
            IconButton(onClick = { refresh() }, enabled = !loading) {
                AriaIcon(AriaIcons.Refresh, stringResource(R.string.ui_refresh))
            }
        },
        floatingActionButton = {
            if (inbox?.enabled == true) {
                FloatingActionButton(onClick = { newDialog = true }) {
                    AriaIcon(AriaIcons.Edit, stringResource(R.string.messages_new))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (inboxes.size > 1) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    inboxes.forEach { box ->
                        FilterChip(
                            selected = box.didId == inbox?.didId,
                            onClick = { selectedDid = box.didId },
                            label = { Text(PhoneFormat.display(box.number)) },
                        )
                    }
                }
            }
            if (inbox != null && !inbox.enabled) {
                Text(
                    stringResource(R.string.messages_inbox_off),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
                )
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    loading && inboxes.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    loadError != null && inboxes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = AriaIcons.Offline,
                            title = stringResource(R.string.messages_unavailable),
                            body = loadError ?: "",
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = { refresh() },
                            actionStyle = AriaButtonStyle.Standard,
                        )
                    }
                    inbox == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = AriaIcons.Messages,
                            title = stringResource(R.string.messages_no_inbox_title),
                            body = stringResource(R.string.messages_no_inbox_body),
                        )
                    }
                    conversations.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = AriaIcons.Messages,
                            title = stringResource(R.string.messages_empty_title),
                            body = stringResource(R.string.messages_empty_body),
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
                    ) {
                        items(conversations, key = { it.remoteNumber }) { c ->
                            ConversationRow(
                                conversation = c,
                                name = ContactsSource.match(c.remoteNumber, contacts)?.name ?: PhoneFormat.display(c.remoteNumber),
                                unread = c.remoteNumber in unread,
                                onClick = { open(inbox.didId, c.remoteNumber) },
                                selected = twoPane && c.remoteNumber == openNumber,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
    }

    if (twoPane) {
        ListDetail(
            list = listPane,
            detail = {
                val number = openNumber
                val did = openDid ?: inbox?.didId
                if (number != null && did != null) {
                    androidx.compose.runtime.key(did, number) {
                        ThreadScreen(didId = did, number = number, onBack = null, onCall = onCall)
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = AriaIcons.Messages,
                            title = stringResource(R.string.ui_pane_conversation_title),
                            body = stringResource(R.string.ui_pane_conversation_body),
                        )
                    }
                }
            },
        )
    } else {
        listPane()
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    name: String,
    unread: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val unreadLabel = stringResource(R.string.messages_unread_marker)
    val preview = conversation.lastBody.ifBlank {
        if (conversation.lastHasMedia) stringResource(R.string.messages_last_photo) else ""
    }
    AriaListItem(
        modifier = if (selected) {
            modifier.background(MaterialTheme.colorScheme.secondaryContainer, AriaShapes.contentRow)
        } else {
            modifier
        },
        headline = name,
        emphasized = unread,
        supporting = preview,
        leading = {
            Box {
                Avatar(name = name, key = conversation.remoteNumber)
                if (unread) {
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
            Text(
                MessagingJson.displayTime(conversation.lastAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.xs),
            )
        },
        onClick = onClick,
    )
}

/** A new conversation by number: the thread opens empty and the first send starts it. */
@Composable
private fun NewConversationDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.messages_new)) },
        shape = AriaShapes.card,
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    invalid = false
                },
                label = { Text(stringResource(R.string.messages_new_number_label)) },
                singleLine = true,
                isError = invalid,
                supportingText = if (invalid) {
                    { Text(stringResource(R.string.messages_new_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val number = MessageNumbers.toE164(text)
                if (number == null) invalid = true else onStart(number)
            }) { Text(stringResource(R.string.messages_new_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
