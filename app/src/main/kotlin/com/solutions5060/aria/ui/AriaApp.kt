package com.solutions5060.aria.ui

import android.Manifest
import android.content.Context
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
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Voicemail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.solutions5060.aria.call.CallAlertSetup
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.history.CallHistoryStore
import com.solutions5060.aria.security.SecurePrefs
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.ui.call.ManagedCallScreen
import com.solutions5060.aria.ui.contacts.ContactsScreen
import com.solutions5060.aria.ui.dialer.DialerScreen
import com.solutions5060.aria.ui.history.HistoryScreen
import com.solutions5060.aria.ui.permissions.Permissions
import com.solutions5060.aria.ui.settings.SettingsScreen
import com.solutions5060.aria.ui.settings.TranscriptionScreen
import com.solutions5060.aria.ui.setup.SetupScreen
import com.solutions5060.aria.ui.transcripts.TranscriptsScreen
import com.solutions5060.aria.ui.voicemail.VoicemailScreen

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
    NavTab("voicemail", "Voicemail", Icons.Filled.Voicemail, Icons.Outlined.Voicemail),
    NavTab("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AriaApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // Permissions are requested where they are needed (microphone before the
    // first call, camera at QR scan, contacts on the Contacts tab,
    // notifications once signed in), never all at once on first launch.
    var phase by remember {
        mutableStateOf(
            if ((prefs.getString(KEY_SIP_USERNAME, "") ?: "").isEmpty()) AppPhase.SETUP else AppPhase.MAIN,
        )
    }

    when (phase) {
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
                        .putString("voicemail_access", creds.voicemail)
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
                    CallHistoryStore.clear(context)
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

    // Ask once per launch for whatever stops incoming calls from alerting.
    var alertProblemDismissed by rememberSaveable { mutableStateOf(false) }
    var alertProblem by remember { mutableStateOf<CallAlertSetup.Problem?>(null) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Re-checked on every resume, so returning from settings clears it.
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                alertProblem = CallAlertSetup.problems(context).firstOrNull()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Notifications: asked once after sign-in, with the reason first, because
    // without them an incoming call cannot ring outside the app.
    var showNotificationRationale by remember { mutableStateOf(Permissions.needsNotificationPrompt(context)) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        alertProblem = CallAlertSetup.problems(context).firstOrNull()
    }
    if (showNotificationRationale && call == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Allow notifications") },
            text = {
                Text(
                    "Aria uses notifications to ring for incoming calls and to show the call you're on. " +
                        "Without them you can only answer calls while the app is open.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    alertProblemDismissed = true
                    Permissions.markNotificationPromptShown(context)
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    Permissions.markNotificationPromptShown(context)
                }) { Text("Not now") }
            },
        )
    }

    val problem = alertProblem
    if (problem != null && !alertProblemDismissed && !showNotificationRationale && call == null) {
        AlertDialog(
            onDismissRequest = { alertProblemDismissed = true },
            title = { Text("Incoming calls need attention") },
            text = {
                Text(
                    when (problem) {
                        CallAlertSetup.Problem.NOTIFICATIONS_OFF ->
                            "Notifications are off for Aria, so incoming calls can't be answered from the lock screen or " +
                                "another app. Turn on notifications to see and answer calls."
                        CallAlertSetup.Problem.FULL_SCREEN_DENIED ->
                            "Aria isn't allowed to show calls full screen, so on a locked phone calls appear only as a " +
                                "notification. Allow full-screen notifications to see the call screen."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    alertProblemDismissed = true
                    try {
                        context.startActivity(CallAlertSetup.settingsIntent(context, problem))
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not open settings: ${e.message}")
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { alertProblemDismissed = true }) { Text("Not now") }
            },
        )
    }

    // Calls go through Telecom; CallManager reports any setup failure. The
    // microphone is requested before the first call, with the reason first.
    var micRationaleFor by remember { mutableStateOf<String?>(null) }
    var pendingCall by remember { mutableStateOf<String?>(null) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val number = pendingCall
        pendingCall = null
        callError = when {
            !granted -> "Aria needs microphone access to make calls. You can allow it in Settings > Apps > Aria > Permissions."
            number != null -> CallManager.placeCall(context, number)
            else -> null
        }
    }
    fun placeCall(number: String) {
        if (Permissions.hasMicrophone(context)) {
            callError = CallManager.placeCall(context, number)
        } else {
            micRationaleFor = number
        }
    }
    micRationaleFor?.let { number ->
        AlertDialog(
            onDismissRequest = { micRationaleFor = null },
            title = { Text("Microphone access") },
            text = { Text("Aria uses the microphone only during calls, so the other person can hear you.") },
            confirmButton = {
                TextButton(onClick = {
                    micRationaleFor = null
                    pendingCall = number
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { micRationaleFor = null }) { Text("Cancel") } },
        )
    }

    val activeCall = call
    if (activeCall != null) {
        ManagedCallScreen(activeCall)
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
                composable("voicemail") {
                    VoicemailScreen(onCall = { number -> placeCall(number) })
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
