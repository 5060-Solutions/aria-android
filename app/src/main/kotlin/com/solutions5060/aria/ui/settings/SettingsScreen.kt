package com.solutions5060.aria.ui.settings

import android.content.Context
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import com.solutions5060.aria.BuildConfig
import androidx.core.content.edit
import com.solutions5060.aria.AppLanguage
import com.solutions5060.aria.AriaApplication
import com.solutions5060.aria.R
import com.solutions5060.aria.provisioning.DEFAULT_SIP_TRANSPORT
import com.solutions5060.aria.provisioning.defaultSipPort
import com.solutions5060.aria.security.SecurePrefs
import com.solutions5060.aria.security.SensitiveClipboard
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.PbxApiClient
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.ui.SecureWindow
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.AriaScreen
import com.solutions5060.aria.ui.components.AriaSwitchRow
import com.solutions5060.aria.ui.components.Avatar
import com.solutions5060.aria.ui.components.ConnectedToggleGroup
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.RegistrationStatus
import com.solutions5060.aria.ui.components.RowGroup
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.components.RowShape
import com.solutions5060.aria.ui.components.SectionCard
import com.solutions5060.aria.ui.components.StatusDot
import com.solutions5060.aria.ui.components.ToggleOption
import com.solutions5060.aria.ui.connection.toRegistrationStatus
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import com.solutions5060.aria.ui.theme.ThemeMode
import com.solutions5060.aria.ui.theme.UiPreferences
import kotlinx.coroutines.launch

private const val PREFS_NAME = "aria_prefs"

@Composable
fun SettingsScreen(
    onSignOut: () -> Unit = {},
    onOpenTranscription: () -> Unit = {},
    onOpenTranscripts: () -> Unit = {},
    /** The PBX-side Calling group (DND, forwarding, …); see CallingSection. */
    callingSection: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // Renders the account and device identifiers.
    SecureWindow()

    val missingFieldsText = stringResource(R.string.ui_settings_missing_fields)
    val connectionErrorText = stringResource(R.string.ui_status_error)

    // Debug fixture mode: sample account, no network.
    val fixture = com.solutions5060.aria.fixtures.Fixtures.current?.account

    // Core SIP fields (read-only after QR scan)
    var sipUsername by rememberSaveable { mutableStateOf(fixture?.extension ?: prefs.getString("sip_username", "") ?: "") }
    // Password is sensitive: read from encrypted storage and keep it out of the
    // saveable instance-state bundle (use remember, not rememberSaveable).
    var sipPassword by remember { mutableStateOf(SecurePrefs.getString(context, "sip_password", "") ?: "") }
    var sipDomain by rememberSaveable { mutableStateOf(fixture?.domain ?: prefs.getString("sip_domain", "") ?: "") }
    var sipRegistrar by rememberSaveable { mutableStateOf(fixture?.server ?: prefs.getString("sip_registrar", "") ?: "") }
    var sipDisplayName by rememberSaveable {
        mutableStateOf(fixture?.displayName ?: prefs.getString("sip_display_name", "") ?: "")
    }
    var sipTransport by rememberSaveable {
        mutableStateOf(fixture?.transport ?: prefs.getString("sip_transport", null) ?: DEFAULT_SIP_TRANSPORT)
    }
    var sipPort by rememberSaveable {
        mutableStateOf(fixture?.port?.toString() ?: prefs.getString("sip_port", null) ?: defaultSipPort(sipTransport).toString())
    }
    var apiUrl by rememberSaveable { mutableStateOf(prefs.getString("api_url", "") ?: "") }
    var tenantDomain by rememberSaveable { mutableStateOf(prefs.getString("tenant_domain", "") ?: "") }

    // PBX auth state
    var jwt by remember { mutableStateOf(SecurePrefs.getString(context, "jwt", null)) }
    var extensionId by remember { mutableStateOf(fixture?.extensionId ?: prefs.getString("extension_id", null)) }
    var tenantId by remember { mutableStateOf(prefs.getString("tenant_id", null)) }
    var deviceId by remember { mutableStateOf(fixture?.deviceId ?: prefs.getString("device_id", null)) }
    var gatewayUrl by remember { mutableStateOf(prefs.getString("gateway_url", null)) }

    // UI state
    var isConnecting by remember { mutableStateOf(false) }
    var isRegistered by remember { mutableStateOf(fixture?.registered ?: (jwt != null && deviceId != null)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fcmToken by remember { mutableStateOf<String?>(null) }
    var showQRScanner by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }


    // Fetch FCM token on first composition
    LaunchedEffect(Unit) {
        if (fixture != null) return@LaunchedEffect
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                fcmToken = token
            }
        } catch (_: Exception) { }
    }

    fun performLogin() {
        if (fixture != null) return
        if (apiUrl.isEmpty() || sipUsername.isEmpty() || sipPassword.isEmpty() || sipDomain.isEmpty()) {
            errorMessage = missingFieldsText
            return
        }

        isConnecting = true
        errorMessage = null

        Thread {
            try {
                EngineManager.login(context, fcmToken)
                jwt = SecurePrefs.getString(context, "jwt", null)
                extensionId = prefs.getString("extension_id", null)
                tenantId = prefs.getString("tenant_id", null)
                deviceId = prefs.getString("device_id", null)
                gatewayUrl = prefs.getString("gateway_url", null)

                isRegistered = true
                isConnecting = false
            } catch (e: Exception) {
                errorMessage = e.message ?: connectionErrorText
                isConnecting = false
            }
        }.start()
    }

    if (showQRScanner) {
        QRScannerScreen(
            onCredentialsScanned = { creds ->
                sipDomain = creds.tenantDomain.ifEmpty { creds.server }
                sipRegistrar = creds.server
                sipPort = creds.port.toString()
                sipUsername = creds.username
                sipPassword = creds.password
                sipDisplayName = creds.displayName
                sipTransport = creds.transport.lowercase()
                apiUrl = creds.apiUrl
                tenantDomain = creds.tenantDomain
                showQRScanner = false

                prefs.edit {
                    putString("sip_username", creds.username)
                    putString("sip_domain", creds.tenantDomain.ifEmpty { creds.server })
                    putString("sip_registrar", creds.server)
                    putString("sip_display_name", creds.displayName)
                    putString("sip_transport", creds.transport.lowercase())
                    putString("sip_port", creds.port.toString())
                    putString("api_url", creds.apiUrl)
                    putString("tenant_domain", creds.tenantDomain)
                    putString("voicemail_access", creds.voicemail)
                }
                // SIP password is sensitive — store encrypted.
                SecurePrefs.putString(context, "sip_password", creds.password)

                performLogin()
            },
            onDismiss = { showQRScanner = false },
        )
        return
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            icon = { AriaIcon(AriaIcons.Logout, null) },
            title = { Text(stringResource(R.string.ui_settings_sign_out_title)) },
            shape = AriaShapes.card,
            text = {
                Text(stringResource(R.string.ui_settings_sign_out_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        val savedJwt = jwt
                        val savedDeviceId = deviceId
                        val savedApiUrl = apiUrl
                        if (savedJwt != null && savedDeviceId != null && savedApiUrl.isNotEmpty()) {
                            Thread {
                                try {
                                    PbxApiClient(savedApiUrl).unregisterDevice(savedJwt, savedDeviceId)
                                } catch (_: Exception) { }
                            }.start()
                        }
                        // By the gateway's own id for this device, off the main thread.
                        com.solutions5060.aria.service.EngineManager.unregisterFromGateway(context)
                        onSignOut()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.ui_settings_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    val connection = com.solutions5060.aria.service.GatewayConnection.state.collectAsState().value
    val status = if (fixture != null) {
        if (fixture.registered) RegistrationStatus.Registered else RegistrationStatus.Error
    } else {
        connection.toRegistrationStatus()
    }
    val appearance by UiPreferences.appearance(context).collectAsState()

    AriaScreen(title = stringResource(R.string.ui_tab_settings)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            // 1. Account
            RowGroup(title = stringResource(R.string.ui_settings_account)) {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Avatar(sipDisplayName.ifBlank { null }, key = sipUsername, size = 56.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                            Text(
                                sipDisplayName.ifBlank { stringResource(R.string.ui_settings_no_account) },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (sipUsername.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.ui_settings_ext_domain, sipUsername, sipDomain),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                modifier = Modifier.padding(top = Spacing.xxs),
                            ) {
                                StatusDot(status)
                                Text(
                                    stringResource(status.label),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                errorMessage?.let { error ->
                    AriaListItem(
                        headline = stringResource(R.string.ui_connection_last_error),
                        supporting = error,
                        leading = {
                            RowIcon(
                                AriaIcons.Error,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                container = MaterialTheme.colorScheme.errorContainer,
                            )
                        },
                    )
                }
                if (sipUsername.isNotEmpty()) {
                    AriaListItem(
                        headline = stringResource(R.string.ui_settings_server),
                        supporting = sipRegistrar.ifEmpty { sipDomain },
                        leading = { RowIcon(AriaIcons.Server) },
                    )
                    AriaListItem(
                        headline = stringResource(R.string.ui_connection_transport),
                        supporting = "${sipTransport.uppercase()} · $sipPort",
                        leading = { RowIcon(AriaIcons.Secure) },
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    if (!isRegistered || errorMessage != null) {
                        AriaButton(
                            text = stringResource(if (isRegistered) R.string.ui_connection_reconnect else R.string.ui_settings_connect),
                            onClick = { performLogin() },
                            style = AriaButtonStyle.Standard,
                            icon = AriaIcons.Sync,
                            loading = isConnecting,
                            enabled = sipUsername.isNotEmpty() && sipDomain.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AriaButton(
                        text = stringResource(if (sipUsername.isEmpty()) R.string.ui_settings_scan_qr else R.string.ui_settings_rescan_qr),
                        onClick = { showQRScanner = true },
                        style = AriaButtonStyle.Standard,
                        icon = AriaIcons.QrScan,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 2. Calling (PBX: DND, forwarding, …)
            callingSection()

            // 4. Appearance
            RowGroup(title = stringResource(R.string.ui_settings_appearance)) {
                SectionCard {
                    Text(
                        stringResource(R.string.ui_settings_theme),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = Spacing.sm),
                    )
                    ConnectedToggleGroup(
                        options = listOf(
                            ToggleOption(stringResource(R.string.ui_settings_theme_system)),
                            ToggleOption(stringResource(R.string.ui_settings_theme_light)),
                            ToggleOption(stringResource(R.string.ui_settings_theme_dark)),
                        ),
                        selectedIndex = appearance.themeMode.ordinal,
                        onSelect = { UiPreferences.setThemeMode(context, ThemeMode.entries[it]) },
                        fill = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LanguagePicker()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    AriaSwitchRow(
                        headline = stringResource(R.string.ui_settings_wallpaper_colors),
                        supporting = stringResource(R.string.ui_settings_wallpaper_colors_desc),
                        checked = appearance.dynamicColor,
                        onCheckedChange = { UiPreferences.setDynamicColor(context, it) },
                        leading = { RowIcon(AriaIcons.Palette) },
                    )
                }
            }

            // 5. Transcription
            RowGroup(title = stringResource(R.string.ui_settings_transcription)) {
                AriaListItem(
                    headline = stringResource(R.string.ui_settings_on_device),
                    supporting = stringResource(R.string.ui_settings_on_device_desc),
                    leading = { RowIcon(AriaIcons.Transcribe) },
                    trailing = { AriaIcon(AriaIcons.ChevronRight, null) },
                    onClick = onOpenTranscription,
                    shape = RowShape.Pill,
                )
                AriaListItem(
                    headline = stringResource(R.string.ui_settings_transcripts),
                    supporting = stringResource(R.string.ui_settings_transcripts_desc),
                    leading = { RowIcon(AriaIcons.Transcript) },
                    trailing = { AriaIcon(AriaIcons.ChevronRight, null) },
                    onClick = onOpenTranscripts,
                    shape = RowShape.Pill,
                )
            }

            // 6. Diagnostics
            if (deviceId != null || extensionId != null) {
                RowGroup(title = stringResource(R.string.ui_settings_device)) {
                    deviceId?.let { id ->
                        CopyableIdRow(stringResource(R.string.ui_settings_device_id), id, AriaIcons.Diagnostics)
                    }
                    extensionId?.let { id ->
                        CopyableIdRow(stringResource(R.string.ui_settings_extension_id), id, AriaIcons.Person)
                    }
                }
            }

            // 7. About
            RowGroup(title = stringResource(R.string.ui_settings_about)) {
                AriaListItem(
                    headline = stringResource(R.string.ui_settings_version),
                    supporting = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    leading = { RowIcon(AriaIcons.Info) },
                )
                AriaListItem(
                    headline = stringResource(R.string.ui_settings_developer),
                    supporting = "5060 Solutions",
                    leading = {
                        Image(
                            painterResource(R.drawable.ic_aria_logo),
                            contentDescription = null,
                            modifier = Modifier
                                .size(Sizes.avatar)
                                .clip(AriaShapes.chip),
                        )
                    },
                )
            }

            if (sipUsername.isNotEmpty()) {
                AriaButton(
                    text = stringResource(R.string.ui_settings_sign_out),
                    onClick = { showSignOutDialog = true },
                    style = AriaButtonStyle.Destructive,
                    icon = AriaIcons.Logout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** An identifier row: shortened, tap to copy (clears from the clipboard after a minute). */
@Composable
private fun CopyableIdRow(label: String, value: String, @DrawableRes icon: Int) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.ui_settings_copied)
    AriaListItem(
        headline = label,
        supporting = value.take(8) + "…",
        leading = { RowIcon(icon) },
        trailing = { AriaIcon(AriaIcons.Copy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = {
            SensitiveClipboard.copy(context, value)
            scope.launch { snackbar.showSnackbar(copied) }
        },
        onClickLabel = stringResource(R.string.ui_settings_copy),
    )
}

/** App language: System default first, then each language in its own name. */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val systemDefault = stringResource(R.string.ui_settings_language_system)
    val current = AppLanguage.current()
    AriaListItem(
        headline = stringResource(R.string.ui_settings_language),
        supporting = current?.let { AppLanguage.nativeName(it) } ?: systemDefault,
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.ui_settings_language)) },
            text = {
                Column {
                    (listOf<String?>(null) + AppLanguage.TAGS).forEach { tag ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    open = false
                                    AppLanguage.set(tag)
                                    (context.applicationContext as? AriaApplication)?.createNotificationChannels()
                                }
                                .padding(vertical = Spacing.sm),
                        ) {
                            RadioButton(selected = tag == current, onClick = null)
                            Text(
                                tag?.let { AppLanguage.nativeName(it) } ?: systemDefault,
                                modifier = Modifier.padding(start = Spacing.md),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
