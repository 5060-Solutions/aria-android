package com.solutions5060.aria.ui.settings

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

    // Fetch FCM token on first composition
    LaunchedEffect(Unit) {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                fcmToken = token
            }
        } catch (_: Exception) {
            // Firebase not initialized — push won't work but app still functions
        }
    }

    // Auto-connect if we have credentials but no JWT yet
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

                // Step 1: Extension login
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

                // Step 2: Device registration
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
                    .apply()

                // Step 3: Initialize SIP engine with gateway for push
                val gwUrl = deviceResult.gatewayUrl.ifEmpty { "https://push.ariaroute.com" }
                val config = GatewayConfig(baseUrl = gwUrl, apiKey = loginResult.jwt)
                val engine = AriaMobileEngine(config)
                SipEngineHolder.engine = engine

                // Register with the SIP server directly
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
                sipDomain = creds.server
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
                    .putString("sip_domain", creds.server)
                    .putString("sip_registrar", creds.server)
                    .putString("sip_display_name", creds.displayName)
                    .putString("sip_transport", creds.transport.lowercase())
                    .putString("sip_port", creds.port.toString())
                    .putString("api_url", creds.apiUrl)
                    .putString("tenant_domain", creds.tenantDomain)
                    .apply()

                // Auto-trigger login after QR scan
                performLogin()
            },
            onDismiss = { showQRScanner = false },
        )
        return
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out? You will need to scan a QR code again to reconnect.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        // Unregister device from PBX
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
                        // Unregister from gateway
                        try {
                            savedDeviceId?.let { SipEngineHolder.engine?.unregisterDevice(it) }
                        } catch (_: Exception) { }
                        onSignOut()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Aria branding header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "Aria Logo",
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Aria",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Account section
            Text(
                "Account",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (sipUsername.isNotEmpty()) {
                        SettingsRow("Extension", sipUsername)
                        SettingsRow("Display Name", sipDisplayName.ifEmpty { "---" })
                        SettingsRow("Domain", sipDomain)
                        SettingsRow("Transport", sipTransport.uppercase())
                        SettingsRow("Port", sipPort)
                    } else {
                        Text(
                            "No account configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            // QR Code / Reconfigure button
            FilledTonalButton(
                onClick = { showQRScanner = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (sipUsername.isEmpty()) "Scan QR Code to Configure" else "Scan New QR Code")
            }

            // Status section
            Text(
                "Status",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Registration", style = MaterialTheme.typography.bodyMedium)
                        if (isRegistered) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = AriaGreen.copy(alpha = 0.15f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = AriaGreen,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Connected",
                                        color = AriaGreen,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        } else {
                            Text(
                                "Disconnected",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    deviceId?.let { id ->
                        SettingsRow("Device ID", id.take(12) + "...")
                    }

                    extensionId?.let { id ->
                        SettingsRow("Extension ID", id.take(12) + "...")
                    }
                }
            }

            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Connect / Reconnect button
            if (!isRegistered || errorMessage != null) {
                Button(
                    onClick = { performLogin() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isConnecting && sipUsername.isNotEmpty() && sipDomain.isNotEmpty(),
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connecting...")
                    } else {
                        Text(if (isRegistered) "Reconnect" else "Connect")
                    }
                }
            }

            // About section
            Text(
                "About",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SettingsRow("App", "Aria Softphone")
                    SettingsRow("Version", "v1.0.0")
                    SettingsRow("Developer", "5060 Solutions")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sign Out button
            if (sipUsername.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showSignOutDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
