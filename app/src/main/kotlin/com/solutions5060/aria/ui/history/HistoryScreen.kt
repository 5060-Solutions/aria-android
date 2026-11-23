package com.solutions5060.aria.ui.history

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.solutions5060.aria.R
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.history.CallHistoryEntry
import com.solutions5060.aria.history.CallHistoryRecorder
import com.solutions5060.aria.history.CallHistoryStore
import com.solutions5060.aria.history.HistoryRepository
import com.solutions5060.aria.ui.SecureWindow
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
import com.solutions5060.aria.ui.theme.LocalAriaColors
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * DESIGN.md §5 History: All / Missed, grouped by day; each row has an avatar,
 * the name or number, a direction icon (missed in error colour), the time
 * and an info button. Swipe right to call back, left to delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onCall: (String) -> Unit) {
    // Call parties and times are personal data: keep them out of screenshots
    // and the recents thumbnail.
    SecureWindow()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    val fixtureHistory = Fixtures.current?.history
    val local = remember { fixtureHistory ?: CallHistoryStore.load(context) }
    var deleted by remember { mutableStateOf(emptySet<String>()) }
    // The local list at once, then merged with the PBX's call records.
    val loaded by produceState(local) {
        if (fixtureHistory == null) {
            value = withContext(Dispatchers.IO) { HistoryRepository(context).merged(local) }
        }
    }
    val history = loaded.filterNot { it.id in deleted }
    // Names for numbers the PBX sent without one, from the address book.
    val contacts by produceState(emptyList<com.solutions5060.aria.ui.contacts.Contact>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { com.solutions5060.aria.ui.contacts.ContactsSource.load(context) }.getOrDefault(emptyList())
        }
    }
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.All) }
    var info by remember { mutableStateOf<CallHistoryEntry?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(history, filter) { HistoryGrouping.byDay(HistoryGrouping.filter(history, filter), zone) }
    val deletedMessage = stringResource(R.string.ui_history_deleted)
    val undo = stringResource(R.string.ui_undo)

    fun delete(entry: CallHistoryEntry) {
        deleted = deleted + entry.id
        scope.launch {
            val result = snackbar.showSnackbar(deletedMessage, actionLabel = undo, withDismissAction = false)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                deleted = deleted - entry.id
            } else if (fixtureHistory == null) {
                withContext(Dispatchers.IO) {
                    CallHistoryStore.save(context, CallHistoryStore.load(context).filterNot { it.id == entry.id })
                }
            }
        }
    }

    info?.let { entry ->
        CallInfoSheet(
            entry = entry,
            calls = HistoryGrouping.callsWith(entry, history),
            onCall = {
                info = null
                onCall(entry.dialNumber)
            },
            onDismiss = { info = null },
        )
    }

    AriaScreen(title = stringResource(R.string.ui_tab_history)) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ConnectedToggleGroup(
                options = listOf(
                    ToggleOption(stringResource(R.string.ui_history_all)),
                    ToggleOption(stringResource(R.string.ui_history_missed)),
                ),
                selectedIndex = filter.ordinal,
                onSelect = { filter = HistoryFilter.entries[it] },
                modifier = Modifier
                    .padding(horizontal = Spacing.screen)
                    .padding(bottom = Spacing.sm),
            )
            if (days.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (filter == HistoryFilter.Missed) {
                        EmptyState(
                            icon = AriaIcons.Missed,
                            title = stringResource(R.string.ui_history_empty_missed),
                            body = stringResource(R.string.ui_history_empty_missed_body),
                        )
                    } else {
                        EmptyState(
                            icon = AriaIcons.History,
                            title = stringResource(R.string.ui_history_empty),
                            body = stringResource(R.string.ui_history_empty_body),
                        )
                    }
                }
            } else {
                val today = LocalDate.now(zone)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
                ) {
                    days.forEach { day ->
                        item(key = "day-${day.date}") {
                            DayHeader(day.date, today)
                        }
                        items(day.entries, key = { it.id }) { entry ->
                            SwipeableHistoryRow(
                                entry = entry,
                                name = nameFor(entry, contacts),
                                onCall = { onCall(entry.dialNumber) },
                                onDelete = { delete(entry) },
                                onInfo = { info = entry },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
    val label = when (HistoryGrouping.relativeDay(date, today)) {
        HistoryGrouping.RelativeDay.Today -> stringResource(R.string.ui_today)
        HistoryGrouping.RelativeDay.Yesterday -> stringResource(R.string.ui_yesterday)
        null -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)).substringBeforeLast(',').let {
            if (date.year == today.year) it else date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Spacing.xxs, top = Spacing.md, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** Direction glyph and its tint: missed in error, others neutral. */
@Composable
internal fun directionIcon(entry: CallHistoryEntry): Pair<Int, androidx.compose.ui.graphics.Color> = when {
    entry.missed || entry.rejected -> AriaIcons.Missed to MaterialTheme.colorScheme.error
    entry.direction == "inbound" -> AriaIcons.Incoming to MaterialTheme.colorScheme.onSurfaceVariant
    else -> AriaIcons.Outgoing to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun directionLabel(entry: CallHistoryEntry): String = stringResource(
    when {
        entry.missed -> R.string.ui_history_dir_missed
        entry.rejected -> R.string.ui_history_dir_declined
        entry.direction == "inbound" -> R.string.ui_history_dir_incoming
        else -> R.string.ui_history_dir_outgoing
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableHistoryRow(
    entry: CallHistoryEntry,
    name: String,
    onCall: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onCall()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    true
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    val aria = LocalAriaColors.current
    val callBack = stringResource(R.string.ui_history_call_back)
    val delete = stringResource(R.string.ui_delete)
    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(callBack) { onCall(); true },
                CustomAccessibilityAction(delete) { onDelete(); true },
            )
        },
        backgroundContent = {
            val direction = state.dismissDirection
            val color by animateColorAsState(
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> aria.answerFill
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                    SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.surfaceContainer
                },
                label = "swipeBg",
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .background(color, AriaShapes.contentRow)
                    .padding(horizontal = Spacing.xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (direction == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
            ) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    AriaIcon(AriaIcons.Call, null, tint = aria.onCallFill)
                } else if (direction == SwipeToDismissBoxValue.EndToStart) {
                    AriaIcon(AriaIcons.Delete, null, tint = MaterialTheme.colorScheme.onError)
                }
            }
        },
    ) {
        HistoryRow(entry, name, onCall, onInfo)
    }
}

@Composable
private fun HistoryRow(entry: CallHistoryEntry, name: String, onCall: () -> Unit, onInfo: () -> Unit) {
    val context = LocalContext.current
    val (icon, tint) = directionIcon(entry)
    val missed = entry.missed || entry.rejected
    val time = remember(entry.timestamp) { DateFormat.getTimeFormat(context).format(Date(entry.timestamp)) }
    AriaListItem(
        headline = name,
        headlineColor = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        supporting = CallHistoryRecorder.summary(entry),
        supportingIcon = icon,
        supportingIconTint = tint,
        supportingIconDescription = directionLabel(entry),
        leading = { Avatar(name, key = entry.remoteUri) },
        trailing = {
            Text(
                time,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onInfo) {
                AriaIcon(
                    AriaIcons.Info,
                    stringResource(R.string.ui_history_details, name),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onCall,
        onClickLabel = stringResource(R.string.ui_history_call_back),
    )
}

/** The PBX's name, else the address book's, else the number formatted for reading. */
internal fun nameFor(entry: CallHistoryEntry, contacts: List<com.solutions5060.aria.ui.contacts.Contact>): String =
    entry.remoteName
        ?: com.solutions5060.aria.ui.contacts.ContactsSource.match(entry.dialNumber, contacts)?.name
        ?: com.solutions5060.aria.ui.components.PhoneFormat.display(entry.dialNumber)
