package com.solutions5060.aria.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
import com.solutions5060.aria.ui.setup.SetupScreen
import com.solutions5060.aria.ui.splash.SplashScreen
import kotlinx.coroutines.delay

private const val PREFS_NAME = "aria_prefs"
private const val KEY_SIP_USERNAME = "sip_username"
private const val KEY_SIP_PASSWORD = "sip_password"
private const val KEY_SIP_DOMAIN = "sip_domain"
private const val KEY_SIP_REGISTRAR = "sip_registrar"
private const val KEY_SIP_DISPLAY_NAME = "sip_display_name"
private const val KEY_SIP_TRANSPORT = "sip_transport"
private const val KEY_SIP_PORT = "sip_port"
private const val KEY_API_URL = "api_url"

private enum class AppPhase {
    SPLASH,
    PERMISSIONS,
    SETUP,
    MAIN,
}

@Composable
fun AriaApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var phase by remember { mutableStateOf(AppPhase.SPLASH) }
    var permissionsRequested by remember { mutableStateOf(false) }

    // Build the list of permissions to request
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Move forward regardless of results — individual screens handle their own
        permissionsRequested = true
        val username = prefs.getString(KEY_SIP_USERNAME, "") ?: ""
        phase = if (username.isEmpty()) AppPhase.SETUP else AppPhase.MAIN
    }

    // Advance from permissions phase
    LaunchedEffect(phase) {
        if (phase == AppPhase.PERMISSIONS) {
            // Check if all permissions already granted
            val allGranted = requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                permissionsRequested = true
                val username = prefs.getString(KEY_SIP_USERNAME, "") ?: ""
                phase = if (username.isEmpty()) AppPhase.SETUP else AppPhase.MAIN
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }
    }

    when (phase) {
        AppPhase.SPLASH -> {
            SplashScreen(onFinished = { phase = AppPhase.PERMISSIONS })
        }

        AppPhase.PERMISSIONS -> {
            // Show splash still while the system dialog is up
            SplashScreen(onFinished = {})
        }

        AppPhase.SETUP -> {
            SetupScreen(
                onCredentialsScanned = { creds ->
                    prefs.edit()
                        .putString(KEY_SIP_USERNAME, creds.username)
                        .putString(KEY_SIP_PASSWORD, creds.password)
                        .putString(KEY_SIP_DOMAIN, creds.server)
                        .putString(KEY_SIP_REGISTRAR, creds.server)
                        .putString(KEY_SIP_DISPLAY_NAME, creds.displayName)
                        .putString(KEY_SIP_TRANSPORT, creds.transport)
                        .putString(KEY_SIP_PORT, creds.port.toString())
                        .putString(KEY_API_URL, creds.apiUrl)
                        .apply()
                    phase = AppPhase.MAIN
                },
                onManualSetup = {
                    phase = AppPhase.MAIN
                },
            )
        }

        AppPhase.MAIN -> {
            MainApp(
                prefs = prefs,
                onSignOut = {
                    // Clear all preferences
                    prefs.edit().clear().apply()
                    // Clear engine
                    SipEngineHolder.engine = null
                    phase = AppPhase.SETUP
                },
            )
        }
    }
}

@Composable
private fun MainApp(
    prefs: android.content.SharedPreferences,
    onSignOut: () -> Unit,
) {
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
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Dialpad, contentDescription = "Dialer") },
                        label = { Text("Dialer") },
                        selected = currentRoute == "dialer",
                        onClick = { navController.navigate("dialer") { launchSingleTop = true } },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Contacts, contentDescription = "Contacts") },
                        label = { Text("Contacts") },
                        selected = currentRoute == "contacts",
                        onClick = { navController.navigate("contacts") { launchSingleTop = true } },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.History, contentDescription = "History") },
                        label = { Text("History") },
                        selected = currentRoute == "history",
                        onClick = { navController.navigate("history") { launchSingleTop = true } },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentRoute == "settings",
                        onClick = { navController.navigate("settings") { launchSingleTop = true } },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
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
                    SettingsScreen(onSignOut = onSignOut)
                }
            }
        }
    }
}
