package com.solutions5060.aria.ui.settings

import android.Manifest
import android.net.Uri
import android.util.Base64
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private const val TAG = "QRScanner"

data class ProvisioningCredentials(
    val server: String,
    val port: Int,
    val username: String,
    val password: String,
    val displayName: String,
    val transport: String,
    val voicemail: String,
    val apiUrl: String,
    val tenantDomain: String,
)

fun parseProvisioningUri(uriString: String): ProvisioningCredentials? {
    return try {
        Log.d(TAG, "Parsing provisioning URI: $uriString")
        val uri = Uri.parse(uriString)
        if (uri.scheme != "aria" || uri.host != "provision") return null

        val server = uri.getQueryParameter("server") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 5060
        val user = uri.getQueryParameter("user") ?: return null
        val passEncoded = uri.getQueryParameter("pass") ?: return null
        val password = String(Base64.decode(passEncoded, Base64.DEFAULT))
        val name = uri.getQueryParameter("name") ?: ""
        val transport = uri.getQueryParameter("transport") ?: "udp"
        val voicemail = uri.getQueryParameter("vm") ?: ""
        val apiUrl = uri.getQueryParameter("api") ?: ""
        val tenantDomain = uri.getQueryParameter("tenant") ?: ""

        Log.d(TAG, "Parsed params: server=$server, user=$user, name=$name, api=$apiUrl, tenant=$tenantDomain")

        ProvisioningCredentials(
            server = server,
            port = port,
            username = user,
            password = password,
            displayName = name,
            transport = transport,
            voicemail = voicemail,
            tenantDomain = tenantDomain,
            apiUrl = apiUrl,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse provisioning URI: $uriString", e)
        null
    }
}

@Composable
fun QRScannerScreen(
    onCredentialsScanned: (ProvisioningCredentials) -> Unit,
    onDismiss: () -> Unit,
) {
    var hasCameraPermission by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                    onBarcodeDetected = { rawValue ->
                        val creds = parseProvisioningUri(rawValue)
                        if (creds != null) {
                            onCredentialsScanned(creds)
                        } else {
                            errorMessage = "Invalid QR code. Expected an Aria provisioning code."
                        }
                    },
                )

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

@Composable
private fun CameraPreviewWithAnalysis(
    flashEnabled: Boolean,
    onBarcodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }
    val hasDetected = remember { mutableStateOf(false) }

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
                        if (!hasDetected.value) {
                            hasDetected.value = true
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
