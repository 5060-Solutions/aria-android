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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.solutions5060.aria.R
import com.solutions5060.aria.provisioning.ProvisioningCredentials
import com.solutions5060.aria.provisioning.parseProvisioningUri
import com.solutions5060.aria.provisioning.validateProvisioning
import com.solutions5060.aria.ui.SecureWindow
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.EmptyState
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.LocalReducedMotion
import com.solutions5060.aria.ui.theme.Radii
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import java.util.concurrent.Executors

private const val TAG = "QRScanner"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QRScannerScreen(
    onCredentialsScanned: (ProvisioningCredentials) -> Unit,
    onDismiss: () -> Unit,
    /** A v=1 setup link: previewed and redeemed through the core, not parsed here. */
    onSetupLinkScanned: ((String) -> Unit)? = null,
) {
    // The camera frames hold the provisioning code, password included.
    SecureWindow()

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

    val context = LocalContext.current
    val invalidCode = stringResource(R.string.ui_qr_invalid)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            permissionDenied -> Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = AriaIcons.Camera,
                    title = stringResource(R.string.ui_qr_camera_title),
                    body = stringResource(R.string.ui_qr_camera_body),
                    actionLabel = stringResource(R.string.ui_qr_open_settings),
                    onAction = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )
                AriaButton(
                    stringResource(R.string.action_back),
                    onClick = onDismiss,
                    style = AriaButtonStyle.Plain,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.xs),
                    icon = AriaIcons.Back,
                )
            }

            hasCameraPermission -> {
                CameraPreviewWithAnalysis(
                    flashEnabled = flashEnabled,
                    scanArmed = scanArmed,
                    onBarcodeDetected = { rawValue ->
                        val creds = parseProvisioningUri(rawValue)
                        when {
                            onSetupLinkScanned != null &&
                                com.solutions5060.aria.provisioning.DeepLinks.isV1ProvisionLink(rawValue) ->
                                onSetupLinkScanned(rawValue.trim())
                            creds == null -> errorMessage = invalidCode
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
                ScannerOverlay(
                    flashEnabled = flashEnabled,
                    error = errorMessage,
                    onClose = onDismiss,
                    onToggleFlash = { flashEnabled = !flashEnabled },
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
            }

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(color = Color.White)
            }
        }
    }
}

/** Viewfinder side. */
private val Viewfinder = 264.dp

/**
 * The scanner chrome over the camera: a dimmed scrim with a clear rounded
 * viewfinder, primary corner brackets and a sweeping scan line (still when
 * animations are off), tonal close and flashlight buttons, and the
 * instruction or error in a capsule.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScannerOverlay(
    flashEnabled: Boolean,
    error: String?,
    onClose: () -> Unit,
    onToggleFlash: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val primary = MaterialTheme.colorScheme.primaryContainer
    val sweep = if (reduced) {
        0.5f
    } else {
        val t = rememberInfiniteTransition(label = "scan")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800), RepeatMode.Reverse), label = "scanLine")
        v
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val side = Viewfinder.toPx()
            val left = (size.width - side) / 2
            val top = (size.height - side) / 2.4f
            val r = Radii.sheet.toPx()
            drawRect(Color.Black.copy(alpha = 0.55f))
            drawRoundRect(
                Color.Transparent,
                topLeft = Offset(left, top),
                size = Size(side, side),
                cornerRadius = CornerRadius(r),
                blendMode = BlendMode.Clear,
            )
            // Corner brackets
            val len = side * 0.18f
            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            listOf(
                Triple(Offset(left, top), 180f, 0),
                Triple(Offset(left + side - 2 * r, top), 270f, 1),
                Triple(Offset(left + side - 2 * r, top + side - 2 * r), 0f, 2),
                Triple(Offset(left, top + side - 2 * r), 90f, 3),
            ).forEach { (o, start, _) ->
                drawArc(primary, start, 90f, false, o, Size(2 * r, 2 * r), style = stroke)
            }
            drawLine(primary, Offset(left + r, top), Offset(left + r + len, top), stroke.width, StrokeCap.Round)
            drawLine(primary, Offset(left + side - r - len, top), Offset(left + side - r, top), stroke.width, StrokeCap.Round)
            drawLine(primary, Offset(left + r, top + side), Offset(left + r + len, top + side), stroke.width, StrokeCap.Round)
            drawLine(primary, Offset(left + side - r - len, top + side), Offset(left + side - r, top + side), stroke.width, StrokeCap.Round)
            drawLine(primary, Offset(left, top + r), Offset(left, top + r + len), stroke.width, StrokeCap.Round)
            drawLine(primary, Offset(left, top + side - r - len), Offset(left, top + side - r), stroke.width, StrokeCap.Round)
            drawLine(primary, Offset(left + side, top + r), Offset(left + side, top + r + len), stroke.width, StrokeCap.Round)
            drawLine(primary, Offset(left + side, top + side - r - len), Offset(left + side, top + side - r), stroke.width, StrokeCap.Round)
            // Scan line
            val y = top + r + (side - 2 * r) * sweep
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, primary, Color.Transparent), left, left + side),
                Offset(left + Spacing.md.toPx(), y),
                Offset(left + side - Spacing.md.toPx(), y),
                strokeWidth = 2.dp.toPx(),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalIconButton(
                onClick = onClose,
                modifier = Modifier.size(Sizes.iconButton),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                ),
            ) { AriaIcon(AriaIcons.Close, stringResource(R.string.ui_qr_close)) }
            val on = stringResource(R.string.ui_state_on)
            val off = stringResource(R.string.ui_state_off)
            FilledTonalIconToggleButton(
                checked = flashEnabled,
                onCheckedChange = { onToggleFlash() },
                modifier = Modifier
                    .size(Sizes.iconButton)
                    .semantics { stateDescription = if (flashEnabled) on else off },
                colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                AriaIcon(if (flashEnabled) AriaIcons.FlashOn else AriaIcons.FlashOff, stringResource(R.string.ui_qr_flashlight))
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen, vertical = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (error != null) {
                Surface(
                    shape = AriaShapes.contentRow,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    )
                }
            }
            Surface(shape = AriaShapes.pill, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
                Row(
                    Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AriaIcon(AriaIcons.QrScan, null, size = Sizes.iconSmall)
                    Text(stringResource(R.string.ui_qr_instruction), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
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
        icon = { AriaIcon(AriaIcons.QrScan, null) },
        title = { Text(stringResource(R.string.ui_qr_confirm_title)) },
        shape = AriaShapes.card,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.ui_qr_confirm_body), style = MaterialTheme.typography.bodyMedium)
                ConfirmRow(stringResource(R.string.ui_qr_api_host), creds.apiHost)
                ConfirmRow(stringResource(R.string.ui_qr_domain), creds.tenantDomain.ifEmpty { creds.server })
                ConfirmRow(stringResource(R.string.ui_qr_sip_server), "${creds.server}:${creds.port}")
                ConfirmRow(stringResource(R.string.ui_connection_transport), creds.transport.uppercase())
                ConfirmRow(stringResource(R.string.ui_qr_user), creds.username)
                if (creds.transport != "tls") {
                    Text(
                        stringResource(R.string.ui_qr_unencrypted, creds.transport.uppercase()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.ui_qr_sign_in)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f),
        )
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
