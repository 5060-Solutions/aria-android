package com.solutions5060.aria.ui.contacts

import android.Manifest
import android.content.ContentResolver
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class Contact(
    val name: String,
    val phoneNumber: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onCall: (String) -> Unit) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var hasPermission by remember { mutableStateOf(false) }

    // Load contacts
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            contacts = loadContacts(context.contentResolver)
        }
    }

    // Request permission
    LaunchedEffect(Unit) {
        // In production, use rememberPermissionState from Accompanist
        // For now, assume permission is granted or handle in the activity
        hasPermission = true
    }

    val filteredContacts = if (searchQuery.isEmpty()) contacts
    else contacts.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.phoneNumber.contains(searchQuery)
    }

    Scaffold(
        topBar = {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = {},
                active = false,
                onActiveChange = {},
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                placeholder = { Text("Search contacts") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {}
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No contacts found",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filteredContacts) { contact ->
                    ContactRow(
                        contact = contact,
                        onCall = { onCall(contact.phoneNumber) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, onCall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCall)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contact.name.take(1).uppercase(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                contact.phoneNumber,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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

private fun loadContacts(contentResolver: ContentResolver): List<Contact> {
    val contacts = mutableListOf<Contact>()
    val cursor = contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        ),
        null, null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    )

    cursor?.use {
        val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

        while (it.moveToNext()) {
            val name = it.getString(nameIdx) ?: continue
            val number = it.getString(numberIdx) ?: continue
            contacts.add(Contact(name = name, phoneNumber = number))
        }
    }

    return contacts.distinctBy { it.name + it.phoneNumber }
}
