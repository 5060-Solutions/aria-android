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
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.ui.call.CallScreen
import com.solutions5060.aria.ui.contacts.ContactsScreen
import com.solutions5060.aria.ui.dialer.DialerScreen
import com.solutions5060.aria.ui.history.HistoryScreen
import com.solutions5060.aria.ui.settings.SettingsScreen
import com.solutions5060.aria.ui.setup.SetupScreen
import com.solutions5060.aria.ui.splash.SplashScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import uniffi.aria_mobile.AudioCodec
import uniffi.aria_mobile.SipCredentials

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
                        .putString(KEY_SIP_PASSWORD, creds.password)
                        .putString(KEY_SIP_DOMAIN, creds.tenantDomain.ifEmpty { creds.server })
                        .putString(KEY_SIP_REGISTRAR, creds.server)
                        .putString(KEY_SIP_DISPLAY_NAME, creds.displayName)
                        .putString(KEY_SIP_TRANSPORT, creds.transport)
                        .putString(KEY_SIP_PORT, creds.port.toString())
                        .putString(KEY_API_URL, creds.apiUrl)
                        .putString("tenant_domain", creds.tenantDomain)
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
                    prefs.edit().clear().apply()
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
    var callError by remember { mutableStateOf<String?>(null) }
    var engineReady by remember { mutableStateOf(SipEngineHolder.engine != null) }

    // Observe incoming call from notification tap (reactive state)
    val incomingToken by com.solutions5060.aria.MainActivity.incomingCallToken
    LaunchedEffect(incomingToken, engineReady) {
        val token = incomingToken ?: return@LaunchedEffect
        if (!engineReady) return@LaunchedEffect

        com.solutions5060.aria.MainActivity.incomingCallToken.value = null
        val callerUri = com.solutions5060.aria.MainActivity.incomingCallerUri.value ?: "Unknown"
        Log.d(TAG, "Accepting incoming call: $callerUri (token: ${token.take(8)})")

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                SipEngineHolder.engine?.acceptIncomingCall(token, listOf(
                    uniffi.aria_mobile.AudioCodec.PCMU,
                    uniffi.aria_mobile.AudioCodec.PCMA,
                ))
                showCallScreen = true
                Log.d(TAG, "Incoming call accepted")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept incoming call: ${e.message}", e)
                callError = "Failed to answer: ${e.message}"
            }
        }
    }

    // Re-initialize the engine on cold start if we have saved credentials
    LaunchedEffect(Unit) {
        if (SipEngineHolder.engine == null) {
            val username = prefs.getString(KEY_SIP_USERNAME, "") ?: ""
            val password = prefs.getString(KEY_SIP_PASSWORD, "") ?: ""
            val domain = prefs.getString(KEY_SIP_DOMAIN, "") ?: ""
            val apiUrl = prefs.getString(KEY_API_URL, "") ?: ""
            val tenantDomain = prefs.getString("tenant_domain", "") ?: ""

            if (username.isEmpty() || domain.isEmpty()) {
                // No credentials at all
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        // Get FCM push token for incoming call notifications
                        val fcmToken = try {
                            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not get FCM token: ${e.message}")
                            ""
                        }

                        var gwUrl = prefs.getString("gateway_url", null) ?: ""
                        var gwToken = prefs.getString("gateway_token", null) ?: ""

                        // If we don't have a gateway token, do a full login first
                        if (gwToken.isEmpty() && apiUrl.isNotEmpty()) {
                            Log.d(TAG, "No gateway token — performing full login")
                            val client = com.solutions5060.aria.service.PbxApiClient(apiUrl)
                            val loginResult = client.extensionLogin(username, password, tenantDomain)
                            val deviceResult = client.registerDevice(
                                jwt = loginResult.jwt,
                                pushToken = fcmToken,
                                platform = "android",
                            )
                            gwUrl = deviceResult.gatewayUrl.ifEmpty { "https://push.ariaroute.com" }
                            gwToken = deviceResult.gatewayToken.ifEmpty { loginResult.jwt }
                            prefs.edit()
                                .putString("jwt", loginResult.jwt)
                                .putString("extension_id", loginResult.extensionId)
                                .putString("tenant_id", loginResult.tenantId)
                                .putString("device_id", deviceResult.deviceId)
                                .putString("gateway_url", gwUrl)
                                .putString("gateway_token", gwToken)
                                .apply()
                            Log.d(TAG, "Full login completed, gwUrl=$gwUrl, gwToken=${gwToken.take(20)}...")
                        }

                        Log.d(TAG, "Engine init: gwUrl=$gwUrl, gwToken empty=${gwToken.isEmpty()}")
                        if (gwUrl.isNotEmpty() && gwToken.isNotEmpty()) {
                            val config = uniffi.aria_mobile.GatewayConfig(
                                baseUrl = gwUrl,
                                apiKey = gwToken,
                            )
                            val engine = uniffi.aria_mobile.AriaMobileEngine(config)
                            engine.setAudioBridge(com.solutions5060.aria.service.AudioBridge())
                            SipEngineHolder.engine = engine

                            val registrar = prefs.getString(KEY_SIP_REGISTRAR, "") ?: ""
                            val transport = prefs.getString(KEY_SIP_TRANSPORT, "udp") ?: "udp"
                            val port = prefs.getString(KEY_SIP_PORT, "5060") ?: "5060"
                            val displayName = prefs.getString(KEY_SIP_DISPLAY_NAME, "") ?: ""

                            val creds = uniffi.aria_mobile.SipCredentials(
                                username = username,
                                password = password,
                                domain = domain,
                                registrar = registrar.ifEmpty { null },
                                transport = transport,
                                port = port.toUShortOrNull() ?: 5060u,
                                authUsername = null,
                                displayName = displayName,
                            )
                            val reg = uniffi.aria_mobile.DeviceRegistration(
                                platform = "android",
                                pushToken = fcmToken,
                                bundleId = "com.solutions5060.aria",
                                sip = creds,
                            )
                            engine.registerDevice(reg)
                            engineReady = true
                            Log.d(TAG, "Engine initialized successfully")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to init engine on startup: ${e.message}", e)
                        callError = "Connection failed: ${e.message}"
                    }
                }
            }
        } else {
            engineReady = true
        }
    }

    // Poll the engine for active call state
    LaunchedEffect(Unit) {
        while (true) {
            val activeCall = SipEngineHolder.engine?.getActiveCall()
            if (activeCall != null && !showCallScreen) {
                showCallScreen = true
            }
            delay(500)
        }
    }

    // Place a call via the engine
    fun placeCall(number: String) {
        val engine = SipEngineHolder.engine
        if (engine == null) {
            callError = "Not connected. Go to Settings and connect first."
            return
        }

        val domain = prefs.getString(KEY_SIP_DOMAIN, "") ?: ""
        val username = prefs.getString(KEY_SIP_USERNAME, "") ?: ""
        val password = prefs.getString(KEY_SIP_PASSWORD, "") ?: ""
        val transport = prefs.getString(KEY_SIP_TRANSPORT, "udp") ?: "udp"
        val port = prefs.getString(KEY_SIP_PORT, "5060") ?: "5060"
        val registrar = prefs.getString(KEY_SIP_REGISTRAR, "") ?: ""
        val displayName = prefs.getString(KEY_SIP_DISPLAY_NAME, "") ?: ""

        if (domain.isEmpty() || username.isEmpty()) {
            callError = "Missing SIP credentials. Scan a QR code in Settings."
            return
        }

        callError = null
        showCallScreen = true

        val sipUri = "sip:$number@$domain"
        val credentials = SipCredentials(
            username = username,
            password = password,
            domain = domain,
            registrar = registrar.ifEmpty { null },
            transport = transport,
            port = port.toUShortOrNull() ?: 5060u,
            authUsername = null,
            displayName = displayName,
        )

        Thread {
            try {
                Log.d(TAG, "Placing call to $sipUri")
                engine.makeCall(sipUri, credentials, listOf(AudioCodec.PCMU, AudioCodec.PCMA, AudioCodec.OPUS))
                Log.d(TAG, "Call initiated successfully")
            } catch (e: Exception) {
                Log.e(TAG, "makeCall failed: ${e.message}", e)
                callError = "Call failed: ${e.message}"
                showCallScreen = false
            }
        }.start()
    }

    if (showCallScreen) {
        CallScreen(onDismiss = { showCallScreen = false })
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
                        callError = callError,
                        onDismissError = { callError = null },
                    )
                }
                composable("contacts") {
                    ContactsScreen(onCall = { number -> placeCall(number) })
                }
                composable("history") {
                    HistoryScreen(onCall = { number -> placeCall(number) })
                }
                composable("settings") {
                    SettingsScreen(onSignOut = onSignOut)
                }
            }
        }
    }
}
