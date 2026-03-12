package com.solutions5060.aria.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.ui.call.CallScreen
import com.solutions5060.aria.ui.contacts.ContactsScreen
import com.solutions5060.aria.ui.dialer.DialerScreen
import com.solutions5060.aria.ui.history.HistoryScreen
import com.solutions5060.aria.ui.settings.SettingsScreen
import kotlinx.coroutines.delay

@Composable
fun AriaApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    var showCallScreen by remember { mutableStateOf(false) }

    // Poll the engine for active call state
    LaunchedEffect(Unit) {
        while (true) {
            val activeCall = SipEngineHolder.engine?.getActiveCall()
            showCallScreen = activeCall != null
            delay(500)
        }
    }

    if (showCallScreen) {
        CallScreen(onDismiss = { showCallScreen = false })
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Call, contentDescription = "Dialer") },
                        label = { Text("Dialer") },
                        selected = currentRoute == "dialer",
                        onClick = { navController.navigate("dialer") { launchSingleTop = true } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Contacts, contentDescription = "Contacts") },
                        label = { Text("Contacts") },
                        selected = currentRoute == "contacts",
                        onClick = { navController.navigate("contacts") { launchSingleTop = true } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.History, contentDescription = "History") },
                        label = { Text("History") },
                        selected = currentRoute == "history",
                        onClick = { navController.navigate("history") { launchSingleTop = true } }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentRoute == "settings",
                        onClick = { navController.navigate("settings") { launchSingleTop = true } }
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "dialer",
                modifier = Modifier.padding(innerPadding)
            ) {
                composable("dialer") {
                    DialerScreen(onCall = { uri -> showCallScreen = true })
                }
                composable("contacts") {
                    ContactsScreen(onCall = { uri -> showCallScreen = true })
                }
                composable("history") {
                    HistoryScreen(onCall = { uri -> showCallScreen = true })
                }
                composable("settings") {
                    SettingsScreen()
                }
            }
        }
    }
}
