package com.solutions5060.aria.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.solutions5060.aria.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.solutions5060.aria.call.CallAlertSetup
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.call.EmergencyRouting
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.history.CallHistoryStore
import com.solutions5060.aria.provisioning.DeepLink
import com.solutions5060.aria.provisioning.DeepLinks
import com.solutions5060.aria.provisioning.ProvisioningCredentials
import com.solutions5060.aria.ui.setup.ManualSetupScreen
import com.solutions5060.aria.ui.setup.SetupLinkScreen
import com.solutions5060.aria.security.SecurePrefs
import com.solutions5060.aria.service.EngineManager
import kotlinx.coroutines.launch
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.service.ConnectionStatus
import com.solutions5060.aria.service.GatewayConnection
import com.solutions5060.aria.ui.connection.ConnectionViewModel
import com.solutions5060.aria.pbx.SelfServiceState
import com.solutions5060.aria.ui.forwarding.ForwardingScreen
import com.solutions5060.aria.ui.forwarding.SelfServiceViewModel
import com.solutions5060.aria.ui.settings.CallingSection
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solutions5060.aria.ui.call.ManagedCallScreen
import com.solutions5060.aria.ui.shell.AriaShell
import com.solutions5060.aria.ui.contacts.ContactsScreen
import com.solutions5060.aria.ui.dialer.DialerScreen
import com.solutions5060.aria.ui.history.HistoryScreen
import com.solutions5060.aria.ui.permissions.Permissions
import com.solutions5060.aria.ui.settings.SettingsScreen
import com.solutions5060.aria.ui.settings.TranscriptionScreen
import com.solutions5060.aria.ui.setup.SetupScreen
import com.solutions5060.aria.ui.transcripts.TranscriptsScreen
import com.solutions5060.aria.ui.voicemail.VoicemailScreen
import com.solutions5060.aria.ui.messages.MessagesScreen
import com.solutions5060.aria.ui.messages.ThreadScreen

private const val TAG = "AriaApp"

/** Test tag on the "Call" button of the tel:/sip: confirmation. */
const val DIAL_LINK_CONFIRM_TAG = "dial_link_confirm"
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

@Composable
fun AriaApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // Permissions are requested where they are needed (microphone before the
    // first call, camera at QR scan, contacts on the Contacts tab,
    // notifications once signed in), never all at once on first launch.
    val fixture = Fixtures.current
    var phase by remember {
        mutableStateOf(
            when {
                fixture != null -> if (fixture.account == null) AppPhase.SETUP else AppPhase.MAIN
                (prefs.getString(KEY_SIP_USERNAME, "") ?: "").isEmpty() -> AppPhase.SETUP
                else -> AppPhase.MAIN
            },
        )
    }

    // Saves an account from any setup path (QR, setup link, manual) the same way.
    fun saveCredentials(creds: ProvisioningCredentials) {
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
    }

    fun clearAccount() {
        prefs.edit().clear().apply()
        SecurePrefs.clear(context)
        CallHistoryStore.clear(context)
        // Transcripts belong to the account too (the speech models stay).
        SipEngineHolder.engine?.let { engine ->
            com.solutions5060.aria.AppScope.scope.launch {
                runCatching { engine.aiClearInsights() }
            }
        }
        EngineManager.reset()
        GatewayConnection.onSignedOut()
        // Recordings are this account's calls; they go with it.
        com.solutions5060.aria.recording.RecordingsRepository(context).deleteAll()
        com.solutions5060.aria.voicemail.VoicemailMonitor.clear(context)
        com.solutions5060.aria.messaging.MessagesMonitor.clear(context)
        com.solutions5060.aria.ui.messages.AttachmentCache.clear()
        SelfServiceState.clear()
        // The mirrored Hold for me default is the account's too.
        com.solutions5060.aria.hold.PrefsGreetingMirror(context).clear()
    }

    var manualSetup by rememberSaveable { mutableStateOf(false) }
    // A new value starts MainApp afresh, so a replaced account gets a new engine.
    var session by remember { mutableIntStateOf(0) }
    val pendingLink by DeepLinks.pending.collectAsState()

    // A setup link (opened from another app, a web page or a QR code) is
    // confirmed before anything else, signed in or not.
    (pendingLink as? DeepLink.Provision)?.let { link ->
        SetupLinkScreen(
            link = link.link,
            replacingAccount = phase == AppPhase.MAIN,
            onAccount = { creds ->
                if (fixture == null) {
                    if (phase == AppPhase.MAIN) clearAccount()
                    saveCredentials(creds)
                }
                manualSetup = false
                session++
                phase = AppPhase.MAIN
                DeepLinks.consume(link)
            },
            onDismiss = { DeepLinks.consume(link) },
        )
        return
    }

    when (phase) {
        AppPhase.SETUP -> {
            if (manualSetup) {
                ManualSetupScreen(
                    onBack = { manualSetup = false },
                    onSignedIn = { creds ->
                        saveCredentials(creds)
                        manualSetup = false
                        phase = AppPhase.MAIN
                    },
                )
            } else {
                SetupScreen(
                    onCredentialsScanned = { creds ->
                        saveCredentials(creds)
                        phase = AppPhase.MAIN
                    },
                    onManualSetup = { manualSetup = true },
                    onSetupLinkScanned = { link -> DeepLinks.offer(DeepLink.Provision(link)) },
                )
            }
        }

        AppPhase.MAIN -> key(session) {
            MainApp(
                prefs = prefs,
                onSignOut = {
                    if (fixture != null) {
                        phase = AppPhase.SETUP
                        return@MainApp
                    }
                    clearAccount()
                    com.solutions5060.aria.move.CallMoveCoordinator.reset()
                    com.solutions5060.aria.hold.HoldForMeCoordinator.reset()
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
    val fixture = Fixtures.current
    var callError by remember { mutableStateOf<String?>(null) }
    val connectionViewModel = viewModel<ConnectionViewModel>()
    val liveConnection by connectionViewModel.state.collectAsState()
    val connection = fixture?.account?.let { Fixtures.connectionState(it) } ?: liveConnection
    // DND moon and forwarding arrow on the status chip.
    val selfServiceViewModel = viewModel<SelfServiceViewModel>()
    val selfService by selfServiceViewModel.state.collectAsState()
    val voicemailUnread by com.solutions5060.aria.voicemail.VoicemailMonitor.unread.collectAsState()
    // "Call dropped — connection lost" after the core ends a call it could not keep.
    val callNotice by CallManager.notice.collectAsState()
    val snackbar = com.solutions5060.aria.ui.components.LocalSnackbarHostState.current
    val droppedText = stringResource(R.string.call_dropped_connection_lost)
    LaunchedEffect(callNotice) {
        if (callNotice == com.solutions5060.aria.call.CallNotice.DROPPED) {
            CallManager.clearNotice()
            snackbar.showSnackbar(droppedText)
        }
    }
    // A tab named by a notification (new voicemail).
    val tabLink by DeepLinks.pending.collectAsState()
    (tabLink as? DeepLink.OpenTab)?.let { link ->
        LaunchedEffect(link) {
            navController.navigate(link.route) { launchSingleTop = true }
            DeepLinks.consume(link)
        }
    }
    LaunchedEffect(Unit) { selfServiceViewModel.refresh() }
    val messagesUnread by com.solutions5060.aria.messaging.MessagesMonitor.unread.collectAsState()
    // Text messages: the unread count and the event socket, only while the app is in the foreground.
    if (fixture == null) {
        androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
            val job = com.solutions5060.aria.AppScope.scope.launch {
                com.solutions5060.aria.messaging.MessagesMonitor.run(context)
            }
            onStopOrDispose { job.cancel() }
        }
    }
    // A conversation named by a text-message notification.
    (tabLink as? DeepLink.OpenThread)?.let { link ->
        LaunchedEffect(link) {
            navController.navigate("messages/thread/${link.didId}/${android.net.Uri.encode(link.number)}") { launchSingleTop = true }
            DeepLinks.consume(link)
        }
    }
    val call by CallManager.state.collectAsState()
    val callSetupError by CallManager.error.collectAsState()

    // Re-initialize the engine on cold start if we have saved credentials
    LaunchedEffect(Unit) {
        // Fixture mode never starts the engine.
        if (fixture != null) return@LaunchedEffect
        // Builds the engine if needed; later returns to the foreground are
        // handled process-wide (AriaApplication).
        GatewayConnection.onForeground(context)
    }

    // Ask once per launch for whatever stops incoming calls from alerting.
    var alertProblemDismissed by rememberSaveable { mutableStateOf(false) }
    var alertProblem by remember { mutableStateOf<CallAlertSetup.Problem?>(null) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Re-checked on every resume, so returning from settings clears it.
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && fixture == null) {
                alertProblem = CallAlertSetup.problems(context).firstOrNull()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Notifications: asked once after sign-in, with the reason first, because
    // without them an incoming call cannot ring outside the app.
    var showNotificationRationale by remember {
        mutableStateOf(fixture == null && Permissions.needsNotificationPrompt(context))
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        alertProblem = CallAlertSetup.problems(context).firstOrNull()
    }
    if (showNotificationRationale && call == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.perm_notifications_title)) },
            text = {
                Text(
                    stringResource(R.string.perm_notifications_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    alertProblemDismissed = true
                    Permissions.markNotificationPromptShown(context)
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.perm_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotificationRationale = false
                    Permissions.markNotificationPromptShown(context)
                }) { Text(stringResource(R.string.perm_not_now)) }
            },
        )
    }

    val problem = alertProblem
    if (problem != null && !alertProblemDismissed && !showNotificationRationale && call == null) {
        AlertDialog(
            onDismissRequest = { alertProblemDismissed = true },
            title = { Text(stringResource(R.string.alert_title)) },
            text = {
                Text(
                    when (problem) {
                        CallAlertSetup.Problem.NOTIFICATIONS_OFF ->
                            stringResource(R.string.alert_notifications_off)
                        CallAlertSetup.Problem.BACKGROUND_RESTRICTED ->
                            stringResource(R.string.alert_background_restricted)
                        CallAlertSetup.Problem.FULL_SCREEN_DENIED ->
                            stringResource(R.string.alert_full_screen_denied)
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
                }) { Text(stringResource(R.string.alert_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { alertProblemDismissed = true }) { Text(stringResource(R.string.perm_not_now)) }
            },
        )
    }

    // Calls go through Telecom; CallManager reports any setup failure. The
    // microphone is requested before the first call, with the reason first.
    var micRationaleFor by remember { mutableStateOf<String?>(null) }
    var pendingCall by remember { mutableStateOf<String?>(null) }
    val micDenied = stringResource(R.string.mic_denied)
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val number = pendingCall
        pendingCall = null
        callError = when {
            !granted -> micDenied
            number != null -> CallManager.placeCall(context, number)
            else -> null
        }
    }
    fun placeCall(number: String) {
        // Fixture mode never places a call; the emergency hand-off to the
        // native dialer is not a call and still runs, so it can be tested.
        if (fixture != null && !EmergencyRouting.isEmergency(number)) return
        // Emergency numbers go to the native dialer, so no microphone prompt.
        if (Permissions.hasMicrophone(context) || EmergencyRouting.isEmergency(number)) {
            callError = CallManager.placeCall(context, number)
        } else {
            micRationaleFor = number
        }
    }
    micRationaleFor?.let { number ->
        AlertDialog(
            onDismissRequest = { micRationaleFor = null },
            title = { Text(stringResource(R.string.mic_title)) },
            text = { Text(stringResource(R.string.mic_body)) },
            confirmButton = {
                TextButton(onClick = {
                    micRationaleFor = null
                    pendingCall = number
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.perm_continue)) }
            },
            dismissButton = { TextButton(onClick = { micRationaleFor = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // tel:/sip: from another app: prefill the dialer and ask before calling.
    val pendingLink by DeepLinks.pending.collectAsState()
    var prefill by remember { mutableStateOf<String?>(null) }
    (pendingLink as? DeepLink.Dial)?.let { link ->
        LaunchedEffect(link) {
            prefill = link.target
            navController.navigate("dialer") { launchSingleTop = true }
        }
        if (call == null) {
            AlertDialog(
                onDismissRequest = { DeepLinks.consume(link) },
                title = { Text(stringResource(R.string.dial_link_title, link.target.removePrefix("sip:"))) },
                text = { Text(stringResource(R.string.dial_link_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            DeepLinks.consume(link)
                            placeCall(link.target)
                        },
                        modifier = Modifier.testTag(DIAL_LINK_CONFIRM_TAG),
                    ) { Text(stringResource(R.string.action_call)) }
                },
                dismissButton = {
                    TextButton(onClick = { DeepLinks.consume(link) }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }

    val wide = com.solutions5060.aria.ui.adaptive.isWideWindow()
    val activeCall = call
    if (activeCall != null) {
        ManagedCallScreen(activeCall)
    } else {
        AriaShell(
            currentRoute = currentRoute,
            voicemailUnread = fixture?.voicemail?.count { !it.read } ?: voicemailUnread,
            messagesUnread = messagesUnread,
            showNavigation = wide || currentRoute?.startsWith("messages/thread/") != true,
            onSelectTab = { tab ->
                navController.navigate(tab.route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        ) { innerPadding ->
            androidx.compose.foundation.layout.Column(Modifier.padding(innerPadding)) {
            // The banners sit above the tab content, which has no top inset of its own; when any shows, they
            // clear the status bar and the screens below skip that inset (so a top bar does not pad twice).
            val moveShown by com.solutions5060.aria.move.CallMoveCoordinator.banner.collectAsState()
            val holdAvailable by com.solutions5060.aria.hold.HoldForMeCoordinator.available.collectAsState()
            val holdWaits by com.solutions5060.aria.hold.HoldForMeCoordinator.waits.collectAsState()
            val holdNotice by com.solutions5060.aria.hold.HoldForMeCoordinator.notice.collectAsState()
            val bannersShown = moveShown.isNotEmpty() ||
                (holdAvailable && holdWaits.values.any { it.isOpen }) || holdNotice != null
            androidx.compose.foundation.layout.Column(
                if (bannersShown) Modifier.statusBarsPadding() else Modifier,
            ) {
                com.solutions5060.aria.ui.move.CallMoveBanner()
                com.solutions5060.aria.ui.hold.HoldWaitCards()
                com.solutions5060.aria.ui.hold.HoldNoticeBanner(onCallAgain = { placeCall(it) })
            }
            val moveSnackbar = com.solutions5060.aria.ui.components.LocalSnackbarHostState.current
            val moveTexts = com.solutions5060.aria.move.MoveMessage.entries.associateWith { stringResource(moveMessageRes(it)) }
            val moveWithDetail = stringResource(R.string.move_err_with_detail, "%1\$s", "%2\$s")
            val currentMoveTexts by androidx.compose.runtime.rememberUpdatedState(moveTexts to moveWithDetail)
            LaunchedEffect(Unit) {
                com.solutions5060.aria.move.CallMoveCoordinator.notices.collect { notice ->
                    val (texts, withDetail) = currentMoveTexts
                    val base = texts.getValue(notice.message)
                    val text = notice.detail?.let { withDetail.replace("%1\$s", base).replace("%2\$s", it) } ?: base
                    moveSnackbar.showSnackbar(text)
                }
            }
            NavHost(
                navController = navController,
                startDestination = fixture?.startTab ?: "dialer",
                modifier = Modifier.weight(1f).then(
                    if (bannersShown) Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.Companion.statusBars) else Modifier,
                ),
            ) {
                composable("dialer") {
                    DialerScreen(
                        onCall = { number -> placeCall(number) },
                        displayName = fixture?.account?.displayName ?: prefs.getString(KEY_SIP_DISPLAY_NAME, "") ?: "",
                        extensionNumber = fixture?.account?.extension ?: prefs.getString(KEY_SIP_USERNAME, "") ?: "",
                        domain = fixture?.account?.domain ?: prefs.getString(KEY_SIP_DOMAIN, "") ?: "",
                        connection = connection,
                        onOpenDiagnostics = { navController.navigate("diagnostics") },
                        dnd = selfService.doNotDisturb == true,
                        forwarding = selfService.forwardingOn,
                        prefill = prefill,
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
                composable("messages") {
                    MessagesScreen(
                        onOpenThread = { did, number ->
                            navController.navigate("messages/thread/$did/${android.net.Uri.encode(number)}")
                        },
                        onCall = { placeCall(it) },
                    )
                }
                composable(
                    "messages/thread/{did}/{number}",
                    arguments = listOf(
                        androidx.navigation.navArgument("did") { type = androidx.navigation.NavType.StringType },
                        androidx.navigation.navArgument("number") { type = androidx.navigation.NavType.StringType },
                    ),
                ) { entry ->
                    val did = entry.arguments?.getString("did").orEmpty()
                    val number = entry.arguments?.getString("number").orEmpty()
                    if (wide) {
                        // Wide windows show the thread beside the list.
                        MessagesScreen(
                            onOpenThread = { _, _ -> },
                            onCall = { placeCall(it) },
                            initialThread = did to number,
                        )
                    } else ThreadScreen(
                        didId = did,
                        number = number,
                        onBack = { navController.popBackStack() },
                        onCall = { placeCall(it) },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onSignOut = onSignOut,
                        onOpenTranscription = { navController.navigate("transcription") },
                        onOpenTranscripts = { navController.navigate("transcripts") },
                        callingSection = {
                            androidx.compose.foundation.layout.Column(
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                    com.solutions5060.aria.ui.theme.Spacing.sectionGap,
                                ),
                            ) {
                                CallingSection(
                                    onOpenForwarding = { navController.navigate("forwarding") },
                                    onOpenCodecs = { navController.navigate("codecs") },
                                    more = {
                                        com.solutions5060.aria.ui.settings.CallHandoffRow(
                                            onOpen = { navController.navigate("call-handoff") },
                                        )
                                        com.solutions5060.aria.ui.settings.HoldForMeRow()
                                    },
                                )
                                com.solutions5060.aria.ui.recordings.RecordingsSection(
                                    onOpen = { navController.navigate("recordings") },
                                )
                                com.solutions5060.aria.ui.connection.DiagnosticsSection(
                                    onOpen = { navController.navigate("diagnostics") },
                                )
                            }
                        },
                    )
                }
                composable("recordings") {
                    com.solutions5060.aria.ui.recordings.RecordingsScreen(onBack = { navController.popBackStack() })
                }
                composable("diagnostics") {
                    com.solutions5060.aria.ui.connection.DiagnosticsScreen(onBack = { navController.popBackStack() })
                }
                composable("codecs") {
                    com.solutions5060.aria.ui.settings.CodecsScreen(onBack = { navController.popBackStack() })
                }
                composable("call-handoff") {
                    com.solutions5060.aria.ui.settings.CallHandoffScreen(onBack = { navController.popBackStack() })
                }
                composable("forwarding") {
                    ForwardingScreen(onBack = { navController.popBackStack() })
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
}

private fun moveMessageRes(m: com.solutions5060.aria.move.MoveMessage): Int = when (m) {
    com.solutions5060.aria.move.MoveMessage.NOT_FOUND -> R.string.move_err_not_found
    com.solutions5060.aria.move.MoveMessage.NOT_MOVABLE -> R.string.move_err_not_movable
    com.solutions5060.aria.move.MoveMessage.DEVICE_UNAVAILABLE -> R.string.move_err_device_unavailable
    com.solutions5060.aria.move.MoveMessage.FAILED -> R.string.move_err_failed
}
