package com.solutions5060.aria.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import com.solutions5060.aria.bridge.*
import com.solutions5060.aria.service.SipEngineHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var gatewayUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var sipUsername by rememberSaveable { mutableStateOf("") }
    var sipPassword by rememberSaveable { mutableStateOf("") }
    var sipDomain by rememberSaveable { mutableStateOf("") }
    var sipRegistrar by rememberSaveable { mutableStateOf("") }
    var sipDisplayName by rememberSaveable { mutableStateOf("") }
    var sipTransport by rememberSaveable { mutableStateOf("udp") }
    var sipPort by rememberSaveable { mutableStateOf("5060") }
    var sipAuthUsername by rememberSaveable { mutableStateOf("") }

    var showPassword by remember { mutableStateOf(false) }
    var isRegistering by remember { mutableStateOf(false) }
    var isRegistered by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fcmToken by remember { mutableStateOf<String?>(null) }
    var showQRScanner by remember { mutableStateOf(false) }

    // Fetch FCM token on first composition
    LaunchedEffect(Unit) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            fcmToken = token
        }
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
                showQRScanner = false
            },
            onDismiss = { showQRScanner = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Gateway section
            Text(
                "Push Gateway",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp)
            )

            OutlinedTextField(
                value = gatewayUrl,
                onValueChange = { gatewayUrl = it },
                label = { Text("Gateway URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // SIP Account section
            Text(
                "SIP Account",
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = { showQRScanner = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan QR Code to Configure")
            }

            OutlinedTextField(
                value = sipDisplayName,
                onValueChange = { sipDisplayName = it },
                label = { Text("Display Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = sipUsername,
                onValueChange = { sipUsername = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = sipPassword,
                onValueChange = { sipPassword = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = "Toggle password"
                        )
                    }
                }
            )

            OutlinedTextField(
                value = sipDomain,
                onValueChange = { sipDomain = it },
                label = { Text("Domain") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            OutlinedTextField(
                value = sipRegistrar,
                onValueChange = { sipRegistrar = it },
                label = { Text("Registrar (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = sipAuthUsername,
                onValueChange = { sipAuthUsername = it },
                label = { Text("Auth Username (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Transport picker
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("udp", "tcp", "tls").forEach { transport ->
                    FilterChip(
                        selected = sipTransport == transport,
                        onClick = { sipTransport = transport },
                        label = { Text(transport.uppercase()) }
                    )
                }
            }

            OutlinedTextField(
                value = sipPort,
                onValueChange = { sipPort = it },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Status
            Text(
                "Status",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Registration")
                Text(
                    if (isRegistered) "Connected" else "Disconnected",
                    color = if (isRegistered) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            deviceId?.let { id ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Device ID")
                    Text(
                        id.take(8) + "...",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            errorMessage?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Register button
            Button(
                onClick = {
                    isRegistering = true
                    errorMessage = null

                    val config = GatewayConfig(baseUrl = gatewayUrl, apiKey = apiKey)
                    val engine = AriaMobileEngine(config)
                    SipEngineHolder.engine = engine

                    val credentials = SipCredentials(
                        username = sipUsername,
                        password = sipPassword,
                        domain = sipDomain,
                        registrar = sipRegistrar.ifEmpty { null },
                        transport = sipTransport,
                        port = sipPort.toUShortOrNull() ?: 5060u,
                        authUsername = sipAuthUsername.ifEmpty { null },
                        displayName = sipDisplayName
                    )

                    Thread {
                        try {
                            val registration = DeviceRegistration(
                                platform = "android",
                                pushToken = fcmToken ?: "",
                                sip = credentials
                            )
                            val response = engine.registerDevice(registration)
                            deviceId = response.deviceId
                            isRegistered = true
                            isRegistering = false
                        } catch (e: Exception) {
                            errorMessage = e.message
                            isRegistering = false
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRegistering &&
                    gatewayUrl.isNotEmpty() && apiKey.isNotEmpty() &&
                    sipUsername.isNotEmpty() && sipPassword.isNotEmpty() && sipDomain.isNotEmpty()
            ) {
                if (isRegistering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Registering...")
                } else {
                    Text(if (isRegistered) "Re-register" else "Register")
                }
            }

            if (isRegistered) {
                OutlinedButton(
                    onClick = {
                        deviceId?.let { SipEngineHolder.engine?.unregisterDevice(it) }
                        isRegistered = false
                        deviceId = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Unregister")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Aria Softphone")
                Text("v1.0.0", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Text(
                "5060 Solutions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
