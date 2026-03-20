package com.solutions5060.aria.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.messaging.FirebaseMessaging
import com.solutions5060.aria.R
import com.solutions5060.aria.service.PbxApiClient
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.ui.theme.AriaGreen
import uniffi.aria_mobile.*

private const val PREFS_NAME = "aria_prefs"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSignOut: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // Core SIP fields (read-only after QR scan)
    var sipUsername by rememberSaveable { mutableStateOf(prefs.getString("sip_username", "") ?: "") }
    var sipPassword by rememberSaveable { mutableStateOf(prefs.getString("sip_password", "") ?: "") }
    var sipDomain by rememberSaveable { mutableStateOf(prefs.getString("sip_domain", "") ?: "") }
    var sipRegistrar by rememberSaveable { mutableStateOf(prefs.getString("sip_registrar", "") ?: "") }
    var sipDisplayName by rememberSaveable { mutableStateOf(prefs.getString("sip_display_name", "") ?: "") }
    var sipTransport by rememberSaveable { mutableStateOf(prefs.getString("sip_transport", "udp") ?: "udp") }
    var sipPort by rememberSaveable { mutableStateOf(prefs.getString("sip_port", "5060") ?: "5060") }
    var apiUrl by rememberSaveable { mutableStateOf(prefs.getString("api_url", "") ?: "") }
    var tenantDomain by rememberSaveable { mutableStateOf(prefs.getString("tenant_domain", "") ?: "") }

    // PBX auth state
    var jwt by remember { mutableStateOf(prefs.getString("jwt", null)) }
    var extensionId by remember { mutableStateOf(prefs.getString("extension_id", null)) }
    var tenantId by remember { mutableStateOf(prefs.getString("tenant_id", null)) }
    var deviceId by remember { mutableStateOf(prefs.getString("device_id", null)) }
    var gatewayUrl by remember { mutableStateOf(prefs.getString("gateway_url", null)) }

    // UI state
    var isConnecting by remember { mutableStateOf(false) }
    var isRegistered by remember { mutableStateOf(jwt != null && deviceId != null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fcmToken by remember { mutableStateOf<String?>(null) }
    var showQRScanner by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    // Expandable ID fields
    var showFullDeviceId by remember { mutableStateOf(false) }
    var showFullExtensionId by remember { mutableStateOf(false) }

    // Fetch FCM token on first composition
    LaunchedEffect(Unit) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                fcmToken = token
            }
        } catch (_: Exception) { }
    }

    fun performLogin() {
        if (apiUrl.isEmpty() || sipUsername.isEmpty() || sipPassword.isEmpty() || sipDomain.isEmpty()) {
            errorMessage = "Missing required fields. Scan a QR code to configure."
            return
        }

        isConnecting = true
        errorMessage = null

        Thread {
            try {
                val client = PbxApiClient(apiUrl)

                val loginResult = client.extensionLogin(
                    extensionNumber = sipUsername,
                    password = sipPassword,
                    tenantDomain = tenantDomain,
                )
                jwt = loginResult.jwt
                extensionId = loginResult.extensionId
                tenantId = loginResult.tenantId

                prefs.edit()
                    .putString("jwt", loginResult.jwt)
                    .putString("extension_id", loginResult.extensionId)
                    .putString("tenant_id", loginResult.tenantId)
                    .apply()

                val token = fcmToken ?: ""
                val deviceResult = client.registerDevice(
                    jwt = loginResult.jwt,
                    pushToken = token,
                    platform = "android",
                )
                deviceId = deviceResult.deviceId
                gatewayUrl = deviceResult.gatewayUrl

                prefs.edit()
                    .putString("device_id", deviceResult.deviceId)
                    .putString("gateway_url", deviceResult.gatewayUrl)
                    .putString("gateway_token", deviceResult.gatewayToken)
                    .apply()

                val gwUrl = deviceResult.gatewayUrl.ifEmpty { "https://push.ariaroute.com" }
                val gwToken = deviceResult.gatewayToken.ifEmpty { loginResult.jwt }
                val config = GatewayConfig(baseUrl = gwUrl, apiKey = gwToken)
                val engine = AriaMobileEngine(config)
                engine.setAudioBridge(com.solutions5060.aria.service.AudioBridge())
                SipEngineHolder.engine = engine

                val credentials = SipCredentials(
                    username = sipUsername,
                    password = sipPassword,
                    domain = sipDomain,
                    registrar = sipRegistrar.ifEmpty { null },
                    transport = sipTransport,
                    port = sipPort.toUShortOrNull() ?: 5060u,
                    authUsername = null,
                    displayName = sipDisplayName,
                )
                val registration = DeviceRegistration(
                    platform = "android",
                    pushToken = token,
                    bundleId = "com.solutions5060.aria",
                    sip = credentials,
                )
                engine.registerDevice(registration)

                isRegistered = true
                isConnecting = false
            } catch (e: Exception) {
                errorMessage = e.message ?: "Connection failed"
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

                prefs.edit()
                    .putString("sip_username", creds.username)
                    .putString("sip_password", creds.password)
                    .putString("sip_domain", creds.tenantDomain.ifEmpty { creds.server })
                    .putString("sip_registrar", creds.server)
                    .putString("sip_display_name", creds.displayName)
                    .putString("sip_transport", creds.transport.lowercase())
                    .putString("sip_port", creds.port.toString())
                    .putString("api_url", creds.apiUrl)
                    .putString("tenant_domain", creds.tenantDomain)
                    .apply()

                performLogin()
            },
            onDismiss = { showQRScanner = false },
        )
        return
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign Out", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Are you sure you want to sign out? You will need to scan a QR code again to reconnect.",
                    style = MaterialTheme.typography.bodyLarge,
                )
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
                        try {
                            savedDeviceId?.let { SipEngineHolder.engine?.unregisterDevice(it) }
                        } catch (_: Exception) { }
                        onSignOut()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Sign Out", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // Compact profile card — horizontal layout
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Logo
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                        contentDescription = "Aria Logo",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    // Identity
                    Column(modifier = Modifier.weight(1f)) {
                        if (sipDisplayName.isNotEmpty()) {
                            Text(
                                text = sipDisplayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (sipUsername.isNotEmpty()) {
                            Text(
                                text = "Ext $sipUsername · $sipDomain",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Status chip
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isRegistered) {
                            AriaGreen.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isRegistered) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (isRegistered) AriaGreen else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                if (isRegistered) "Connected" else "Disconnected",
                                color = if (isRegistered) AriaGreen else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // Error message
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Connect / Reconnect button
            if (!isRegistered || errorMessage != null) {
                Button(
                    onClick = { performLogin() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isConnecting && sipUsername.isNotEmpty() && sipDomain.isNotEmpty(),
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Connecting...", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (isRegistered) "Reconnect" else "Connect",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Account details
            SectionHeader("Account")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (sipUsername.isNotEmpty()) {
                        SettingsRow("Extension", sipUsername)
                        SettingsDivider()
                        SettingsRow("Domain", sipDomain)
                        SettingsDivider()
                        SettingsRow("Server", sipRegistrar.ifEmpty { sipDomain })
                        SettingsDivider()
                        SettingsRow("Transport", "${sipTransport.uppercase()} : $sipPort")
                    } else {
                        Text(
                            "No account configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            // Provisioning – smaller, less dominant
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = { showQRScanner = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (sipUsername.isEmpty()) "Scan QR Code" else "Re-scan QR",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (sipUsername.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showSignOutDialog = true },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sign Out", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Device info — tappable to expand
            if (deviceId != null || extensionId != null) {
                SectionHeader("Device")

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        deviceId?.let { id ->
                            CopyableIdRow(
                                label = "Device ID",
                                value = id,
                                expanded = showFullDeviceId,
                                onToggle = { showFullDeviceId = !showFullDeviceId },
                                context = context,
                            )
                        }
                        if (deviceId != null && extensionId != null) {
                            SettingsDivider()
                        }
                        extensionId?.let { id ->
                            CopyableIdRow(
                                label = "Extension ID",
                                value = id,
                                expanded = showFullExtensionId,
                                onToggle = { showFullExtensionId = !showFullExtensionId },
                                context = context,
                            )
                        }
                    }
                }
            }

            // About
            SectionHeader("About")

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsRow("App", "Aria Softphone")
                    SettingsDivider()
                    SettingsRow("Version", "v1.0.0")
                    SettingsDivider()
                    SettingsRow("Developer", "5060 Solutions")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CopyableIdRow(
    label: String,
    value: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    context: Context,
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) value else value.take(8) + "…",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = if (expanded) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (expanded) 12.sp else 14.sp,
                )
                if (!expanded) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Tap to expand",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    clipboard.setText(AnnotatedString(value))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
