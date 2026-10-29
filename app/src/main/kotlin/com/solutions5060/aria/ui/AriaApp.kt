package com.solutions5060.aria.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.security.SecurePrefs
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.ui.call.CallScreen
import com.solutions5060.aria.ui.contacts.ContactsScreen
import com.solutions5060.aria.ui.dialer.DialerScreen
import com.solutions5060.aria.ui.history.HistoryScreen
import com.solutions5060.aria.ui.settings.SettingsScreen
import com.solutions5060.aria.ui.settings.TranscriptionScreen
import com.solutions5060.aria.ui.setup.SetupScreen
import com.solutions5060.aria.ui.transcripts.TranscriptsScreen
import com.solutions5060.aria.ui.splash.SplashScreen

private const val TAG = "AriaApp"
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

private data class NavTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navTabs = listOf(
    NavTab("dialer", "Dialer", Icons.Filled.Dialpad, Icons.Filled.Dialpad),
    NavTab("contacts", "Contacts", Icons.Filled.Contacts, Icons.Outlined.Contacts),
    NavTab("history", "History", Icons.Filled.History, Icons.Outlined.History),
    NavTab("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AriaApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var phase by remember { mutableStateOf(AppPhase.SPLASH) }
    var permissionsRequested by remember { mutableStateOf(false) }

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
        permissionsRequested = true
        val username = prefs.getString(KEY_SIP_USERNAME, "") ?: ""
        phase = if (username.isEmpty()) AppPhase.SETUP else AppPhase.MAIN
    }

    LaunchedEffect(phase) {
        if (phase == AppPhase.PERMISSIONS) {
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
            SplashScreen(onFinished = {})
        }

        AppPhase.SETUP -> {
            SetupScreen(
                onCredentialsScanned = { creds ->
                    // SIP domain = tenant domain (used in From/To headers for auth)
                    // SIP registrar = server address (where to send REGISTER)
                    prefs.edit()
                        .putString(KEY_SIP_USERNAME, creds.username)
                        .putString(KEY_SIP_DOMAIN, creds.tenantDomain.ifEmpty { creds.server })
                        .putString(KEY_SIP_REGISTRAR, creds.server)
                        .putString(KEY_SIP_DISPLAY_NAME, creds.displayName)
                        .putString(KEY_SIP_TRANSPORT, creds.transport)
                        .putString(KEY_SIP_PORT, creds.port.toString())
                        .putString(KEY_API_URL, creds.apiUrl)
                        .putString("tenant_domain", creds.tenantDomain)
                        .apply()
                    // SIP password is sensitive — store it encrypted.
                    SecurePrefs.putString(context, KEY_SIP_PASSWORD, creds.password)
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
                    prefs.edit().clear().apply()
                    SecurePrefs.clear(context)
                    EngineManager.reset()
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

    val context = LocalContext.current
    var callError by remember { mutableStateOf<String?>(null) }
    var engineReady by remember { mutableStateOf(SipEngineHolder.engine != null) }
    val call by CallManager.state.collectAsState()
    val callSetupError by CallManager.error.collectAsState()

    // Re-initialize the engine on cold start if we have saved credentials
    LaunchedEffect(Unit) {
        if (SipEngineHolder.engine != null) {
            engineReady = true
            return@LaunchedEffect
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                engineReady = EngineManager.ensureEngine(context) != null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init engine on startup: ${e.message}", e)
                callError = "Connection failed: ${e.message}"
            }
        }
    }

    // Calls go through Telecom; CallManager reports any setup failure.
    fun placeCall(number: String) {
        callError = CallManager.placeCall(context, number)
    }

    val activeCall = call
    if (activeCall != null) {
        CallScreen(
            call = activeCall,
            onAnswer = { CallManager.answer() },
            onDecline = { CallManager.reject() },
            onHangup = { CallManager.hangup() },
            onToggleMute = { CallManager.setMuted(!activeCall.muted) },
            onToggleHold = { CallManager.setHold(!activeCall.onHold) },
            onDtmf = { CallManager.sendDtmf(it) },
            onSelectAudioRoute = { CallManager.selectAudioRoute(it) },
        )
    } else {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    navTabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = {
                                Text(
                                    tab.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) { launchSingleTop = true }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "dialer",
                modifier = Modifier.padding(innerPadding)
            ) {
                composable("dialer") {
                    DialerScreen(
                        onCall = { number -> placeCall(number) },
                        displayName = prefs.getString(KEY_SIP_DISPLAY_NAME, "") ?: "",
                        extensionNumber = prefs.getString(KEY_SIP_USERNAME, "") ?: "",
                        domain = prefs.getString(KEY_SIP_DOMAIN, "") ?: "",
                        isConnected = engineReady,
                        callError = callError ?: callSetupError,
                        onDismissError = {
                            callError = null
                            CallManager.clearError()
                        },
                    )
                }
                composable("contacts") {
                    ContactsScreen(onCall = { number -> placeCall(number) })
                }
                composable("history") {
                    HistoryScreen(onCall = { number -> placeCall(number) })
                }
                composable("settings") {
                    SettingsScreen(
                        onSignOut = onSignOut,
                        onOpenTranscription = { navController.navigate("transcription") },
                        onOpenTranscripts = { navController.navigate("transcripts") },
                    )
                }
                composable("transcription") {
                    TranscriptionScreen(onBack = { navController.popBackStack() })
                }
                composable("transcripts") {
                    TranscriptsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
