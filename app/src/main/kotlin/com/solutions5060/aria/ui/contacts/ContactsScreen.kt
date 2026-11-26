package com.solutions5060.aria.ui.contacts

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.ui.call.CallSheet
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.AriaScreen
import com.solutions5060.aria.ui.components.Avatar
import com.solutions5060.aria.ui.components.EmptyState
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DESIGN.md §5 Contacts: search at the top, favourites as a horizontal
 * carousel, then an A–Z list of people. Opening a person shows each number
 * with a call button. Without access, the empty state offers "Allow access".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(onCall: (String) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(ContactsSource.hasPermission(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var contacts by remember { mutableStateOf(Fixtures.current?.contacts ?: emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<ContactPerson?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        denied = !granted
    }
    LaunchedEffect(Unit) {
        // Asked here, where it is needed, the first time the tab opens.
        if (!hasPermission && !askedOnce) {
            askedOnce = true
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) contacts = withContext(Dispatchers.IO) { ContactsSource.load(context) }
    }

    val people = remember(contacts) { ContactGrouping.people(contacts) }
    val shown = remember(people, query) { ContactGrouping.search(people, query) }
    val favorites = remember(people) { people.filter { it.starred } }

    open?.let { person ->
        ContactSheet(person = person, onCall = { number -> open = null; onCall(number) }, onDismiss = { open = null })
    }

    AriaScreen(title = stringResource(R.string.ui_tab_contacts)) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !hasPermission -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = AriaIcons.Contacts,
                        title = stringResource(R.string.ui_contacts_access_title),
                        body = stringResource(R.string.ui_contacts_access_body),
                        actionLabel = stringResource(R.string.ui_contacts_allow),
                        onAction = {
                            if (denied) {
                                // Denied twice: Android will not ask again; open App info instead.
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            } else {
                                permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        },
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
                ) {
                    item(key = "search") {
                        SearchField(
                            query = query,
                            onQueryChange = { query = it },
                            modifier = Modifier
                                .padding(horizontal = Spacing.screen)
                                .padding(bottom = Spacing.sm),
                        )
                    }
                    if (query.isBlank() && favorites.isNotEmpty()) {
                        item(key = "favorites") {
                            Favorites(favorites, onOpen = { open = it })
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                icon = AriaIcons.Search,
                                title = stringResource(if (query.isBlank()) R.string.ui_contacts_empty else R.string.ui_contacts_no_match),
                                body = stringResource(if (query.isBlank()) R.string.ui_contacts_empty_body else R.string.ui_contacts_no_match_body),
                            )
                        }
                    }
                    ContactGrouping.byLetter(shown).forEach { (letter, group) ->
                        item(key = "letter-$letter") {
                            Text(
                                letter,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = Spacing.screen + Spacing.xxs, top = Spacing.md, bottom = Spacing.xs)
                                    .semantics { heading() },
                            )
                        }
                        items(group, key = { "p-" + it.key }) { person ->
                            AriaListItem(
                                headline = person.name,
                                supporting = listOfNotNull(
                                    person.numbers.first().label,
                                    com.solutions5060.aria.ui.components.PhoneFormat.display(person.numbers.first().phoneNumber),
                                    if (person.numbers.size > 1) {
                                        pluralStringResource(R.plurals.ui_contacts_more_numbers, person.numbers.size - 1, person.numbers.size - 1)
                                    } else {
                                        null
                                    },
                                ).joinToString(" · "),
                                leading = { Avatar(person.name, key = person.key) },
                                trailing = {
                                    IconButton(onClick = { onCall(person.numbers.first().phoneNumber) }) {
                                        AriaIcon(
                                            AriaIcons.Call,
                                            stringResource(R.string.ui_history_call_name, person.name),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                                onClick = { open = person },
                                onClickLabel = stringResource(R.string.ui_contacts_show_numbers),
                                modifier = Modifier
                                    .padding(horizontal = Spacing.screen)
                                    .animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.ui_contacts_search)) },
        leadingIcon = { AriaIcon(AriaIcons.Search, null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    AriaIcon(AriaIcons.Close, stringResource(R.string.ui_contacts_clear_search))
                }
            }
        } else {
            null
        },
        singleLine = true,
        shape = AriaShapes.pill,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

/** Starred people as a horizontal carousel of large avatars. */
@Composable
private fun Favorites(people: List<ContactPerson>, onOpen: (ContactPerson) -> Unit) {
    Column {
        Text(
            stringResource(R.string.ui_contacts_favorites),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = Spacing.screen + Spacing.xxs, bottom = Spacing.xs)
                .semantics { heading() },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(people, key = { it.key }) { person ->
                Column(
                    modifier = Modifier
                        .width(Sizes.callControlCell - Spacing.xs)
                        .clip(AriaShapes.card)
                        .clickable(role = Role.Button, onClick = { onOpen(person) })
                        .padding(vertical = Spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Avatar(person.name, key = person.key, size = 64.dp, shape = AriaShapes.card)
                    Text(
                        person.name.substringBefore(' '),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** A person's numbers, each with a call button. */
@Composable
private fun ContactSheet(person: ContactPerson, onCall: (String) -> Unit, onDismiss: () -> Unit) {
    CallSheet(title = person.name, onDismiss = onDismiss) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Avatar(person.name, key = person.key, size = Sizes.avatarLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.rowGap)) {
            person.numbers.forEach { number ->
                AriaListItem(
                    headline = com.solutions5060.aria.ui.components.PhoneFormat.display(number.phoneNumber),
                    supporting = number.label,
                    trailing = {
                        FilledTonalIconButton(onClick = { onCall(number.phoneNumber) }) {
                            AriaIcon(AriaIcons.Call, stringResource(R.string.ui_history_call_name, number.phoneNumber))
                        }
                    },
                    onClick = { onCall(number.phoneNumber) },
                    onClickLabel = stringResource(R.string.ui_dialer_call),
                )
            }
        }
    }
}
