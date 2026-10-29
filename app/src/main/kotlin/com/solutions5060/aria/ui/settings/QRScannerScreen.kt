package com.solutions5060.aria.ui.settings

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.solutions5060.aria.provisioning.ProvisioningCredentials
import com.solutions5060.aria.provisioning.parseProvisioningUri
import com.solutions5060.aria.provisioning.validateProvisioning
import java.util.concurrent.Executors

private const val TAG = "QRScanner"

@Composable
fun QRScannerScreen(
    onCredentialsScanned: (ProvisioningCredentials) -> Unit,
    onDismiss: () -> Unit,
) {
    var hasCameraPermission by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // A valid code waiting for the user to confirm where it signs them in.
    var pending by remember { mutableStateOf<ProvisioningCredentials?>(null) }
    // Cleared after the first detection so one code is handled once; re-armed
    // if the user declines the confirmation.
    val scanArmed = remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            permissionDenied -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Camera permission is required to scan QR codes.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onDismiss) {
                        Text("Go Back")
                    }
                }
            }

            hasCameraPermission -> {
                CameraPreviewWithAnalysis(
                    flashEnabled = flashEnabled,
                    scanArmed = scanArmed,
                    onBarcodeDetected = { rawValue ->
                        val creds = parseProvisioningUri(rawValue)
                        when {
                            creds == null ->
                                errorMessage = "Invalid QR code. Expected an Aria provisioning code."
                            else -> {
                                val rejection = validateProvisioning(creds)
                                if (rejection != null) {
                                    // Untrusted / insecure endpoint — do not auto-login.
                                    errorMessage = rejection
                                } else {
                                    // Anyone can print a QR code. Show where it
                                    // points before logging in with it.
                                    errorMessage = null
                                    pending = creds
                                }
                            }
                        }
                    },
                )

                pending?.let { creds ->
                    ProvisioningConfirmDialog(
                        creds = creds,
                        onConfirm = {
                            pending = null
                            onCredentialsScanned(creds)
                        },
                        onCancel = {
                            pending = null
                            scanArmed.value = true
                        },
                    )
                }

                // Overlay controls
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    // Close button - top start
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.small,
                            ),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close scanner",
                            tint = Color.White,
                        )
                    }

                    // Flash toggle - top end
                    IconButton(
                        onClick = { flashEnabled = !flashEnabled },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(
                                Color.Black.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.small,
                            ),
                    ) {
                        Icon(
                            if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle flash",
                            tint = Color.White,
                        )
                    }

                    // Instruction text - bottom center
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        errorMessage?.let { msg ->
                            Text(
                                msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .background(
                                        Color.Black.copy(alpha = 0.7f),
                                        shape = MaterialTheme.shapes.small,
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            "Point camera at Aria provisioning QR code",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .background(
                                    Color.Black.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.small,
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }

            else -> {
                // Waiting for permission response
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Shows the endpoints a scanned code will sign in to. The password is never
 * shown; the dialog window is FLAG_SECURE so the account details stay out of
 * screenshots and the recents thumbnail.
 */
@Composable
private fun ProvisioningConfirmDialog(
    creds: ProvisioningCredentials,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Sign in with this code?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Only continue if you trust the source of this QR code.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ConfirmRow("API host", creds.apiHost)
                ConfirmRow("Domain", creds.tenantDomain.ifEmpty { creds.server })
                ConfirmRow("SIP server", "${creds.server}:${creds.port}")
                ConfirmRow("Transport", creds.transport.uppercase())
                ConfirmRow("User", creds.username)
                if (creds.transport != "tls") {
                    Text(
                        "This code uses unencrypted ${creds.transport.uppercase()} signalling.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Sign In") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CameraPreviewWithAnalysis(
    flashEnabled: Boolean,
    scanArmed: MutableState<Boolean>,
    onBarcodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    LaunchedEffect(flashEnabled) {
        cameraControl?.enableTorch(flashEnabled)
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            val executor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val barcodeScanner = BarcodeScanning.getClient()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    processImage(imageProxy, barcodeScanner) { rawValue ->
                        if (scanArmed.value) {
                            scanArmed.value = false
                            onBarcodeDetected(rawValue)
                        }
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                    cameraControl = camera.cameraControl
                } catch (e: Exception) {
                    Log.e(TAG, "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImage(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onDetected: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            for (barcode in barcodes) {
                if (barcode.valueType == Barcode.TYPE_TEXT || barcode.valueType == Barcode.TYPE_URL) {
                    barcode.rawValue?.let { onDetected(it) }
                }
            }
        }
        .addOnFailureListener { e ->
            Log.e(TAG, "Barcode scanning failed", e)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}
