package com.solutions5060.aria.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solutions5060.aria.R
import com.solutions5060.aria.ui.settings.QRScannerScreen
import com.solutions5060.aria.ui.settings.ProvisioningCredentials

@Composable
fun SetupScreen(
    onCredentialsScanned: (ProvisioningCredentials) -> Unit,
    onManualSetup: () -> Unit,
) {
    var showQRScanner by remember { mutableStateOf(false) }

    if (showQRScanner) {
        QRScannerScreen(
            onCredentialsScanned = onCredentialsScanned,
            onDismiss = { showQRScanner = false },
        )
        return
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = "Aria Logo",
                modifier = Modifier.size(96.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to Aria",
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Scan a QR code from your admin panel to get started.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = { showQRScanner = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(horizontal = 24.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Scan QR Code", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onManualSetup) {
                Text("Or configure manually")
            }

            Spacer(modifier = Modifier.weight(1.5f))
        }
    }
}
