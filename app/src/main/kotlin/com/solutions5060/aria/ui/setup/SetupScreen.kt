package com.solutions5060.aria.ui.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.provisioning.ProvisioningCredentials
import com.solutions5060.aria.ui.call.CallSheet
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.permissions.Permissions
import com.solutions5060.aria.ui.settings.QRScannerScreen
import com.solutions5060.aria.ui.theme.AriaBrandGradient
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.launch

/** How the user chose to set up; the primer explains what that method needs first. */
enum class SetupMethod { Qr, Link, Manual }

/**
 * DESIGN.md §5 onboarding, three panes: the brand hero; the setup method
 * (Scan QR prominent, Open setup link and Enter manually standard); and a
 * permission primer that explains each permission before Android asks.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SetupScreen(
    onCredentialsScanned: (ProvisioningCredentials) -> Unit,
    onManualSetup: () -> Unit,
    /** A scanned or pasted `aria://provision?v=1` setup link, confirmed and redeemed elsewhere. */
    onSetupLinkScanned: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = Fixtures.current?.sheet?.toIntOrNull() ?: 0) { 3 }
    var method by rememberSaveable { mutableStateOf<SetupMethod?>(null) }
    var showQRScanner by remember { mutableStateOf(false) }
    var showLinkEntry by remember { mutableStateOf(false) }

    fun proceed() {
        when (method) {
            SetupMethod.Qr -> showQRScanner = true
            SetupMethod.Link -> showLinkEntry = true
            SetupMethod.Manual -> onManualSetup()
            null -> scope.launch { pager.animateScrollToPage(1) }
        }
    }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (method == SetupMethod.Qr) add(Manifest.permission.CAMERA)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Permissions.markNotificationPromptShown(context)
        proceed()
    }

    if (showQRScanner) {
        QRScannerScreen(
            onCredentialsScanned = onCredentialsScanned,
            onDismiss = { showQRScanner = false },
            onSetupLinkScanned = { link ->
                showQRScanner = false
                onSetupLinkScanned(link)
            },
        )
        return
    }
    if (showLinkEntry) {
        SetupLinkEntrySheet(
            onOpen = {
                showLinkEntry = false
                onSetupLinkScanned(it)
            },
            onDismiss = { showLinkEntry = false },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            userScrollEnabled = true,
        ) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    0 -> HeroPane()
                    1 -> MethodPane(
                        onChoose = { chosen ->
                            method = chosen
                            scope.launch { pager.animateScrollToPage(2) }
                        },
                    )
                    else -> PrimerPane(method)
                }
            }
        }
        PagerDots(count = 3, current = pager.currentPage)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        ) {
            when (pager.currentPage) {
                0 -> AriaButton(
                    stringResource(R.string.ui_onboarding_get_started),
                    onClick = { scope.launch { pager.animateScrollToPage(1) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                1 -> Spacer(Modifier.height(Sizes.primaryButton))
                else -> AriaButton(
                    stringResource(if (method == null) R.string.ui_onboarding_choose_method else R.string.ui_onboarding_continue),
                    onClick = {
                        if (method == null) {
                            proceed()
                        } else if (Fixtures.active) {
                            proceed()
                        } else {
                            launcher.launch(permissions.toTypedArray())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroPane() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            // The brand mark on an Expressive "sunny" shape in the brand gradient.
            Box(
                Modifier
                    .size(200.dp)
                    .clip(MaterialShapes.SoftBurst.toShape())
                    .background(Brush.linearGradient(AriaBrandGradient.map { it.copy(alpha = 0.18f) })),
            )
            Image(
                painterResource(R.drawable.ic_aria_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(Sizes.avatarLarge + Spacing.xl)
                    .clip(AriaShapes.card),
            )
        }
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            stringResource(R.string.ui_onboarding_welcome),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            stringResource(R.string.ui_onboarding_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

@Composable
private fun MethodPane(onChoose: (SetupMethod) -> Unit) {
    Column(
        Modifier.widthIn(max = Sizes.maxContentWidth),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            stringResource(R.string.ui_onboarding_method_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.ui_onboarding_method_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        AriaButton(
            stringResource(R.string.ui_settings_scan_qr),
            onClick = { onChoose(SetupMethod.Qr) },
            icon = AriaIcons.QrScan,
            modifier = Modifier.fillMaxWidth(),
        )
        AriaButton(
            stringResource(R.string.ui_onboarding_open_link),
            onClick = { onChoose(SetupMethod.Link) },
            style = AriaButtonStyle.Standard,
            size = com.solutions5060.aria.ui.components.AriaButtonSize.Primary,
            icon = AriaIcons.Link,
            modifier = Modifier.fillMaxWidth(),
        )
        AriaButton(
            stringResource(R.string.ui_onboarding_manual),
            onClick = { onChoose(SetupMethod.Manual) },
            style = AriaButtonStyle.Standard,
            size = com.solutions5060.aria.ui.components.AriaButtonSize.Primary,
            icon = AriaIcons.Edit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class Primer(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val body: Int)

@Composable
private fun PrimerPane(method: SetupMethod?) {
    val items = buildList {
        add(Primer(AriaIcons.Mic, R.string.ui_primer_mic, R.string.ui_primer_mic_body))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Primer(AriaIcons.Notifications, R.string.ui_primer_notifications, R.string.ui_primer_notifications_body))
        }
        if (method == SetupMethod.Qr) add(Primer(AriaIcons.Camera, R.string.ui_primer_camera, R.string.ui_primer_camera_body))
        add(Primer(AriaIcons.ContactCard, R.string.ui_primer_contacts, R.string.ui_primer_contacts_body))
    }
    Column(
        Modifier.widthIn(max = Sizes.maxContentWidth),
        verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
    ) {
        Text(
            stringResource(R.string.ui_primer_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.ui_primer_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Spacing.md),
        )
        items.forEach { item ->
            AriaListItem(
                headline = stringResource(item.title),
                supporting = stringResource(item.body),
                leading = {
                    RowIcon(
                        item.icon,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        container = MaterialTheme.colorScheme.primaryContainer,
                    )
                },
                tallWithoutLeading = true,
            )
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int) {
    val description = stringResource(R.string.ui_onboarding_page, current + 1, count)
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) Spacing.xl else Spacing.xs, label = "dot")
            Box(
                Modifier
                    .padding(horizontal = Spacing.xxs)
                    .size(width = width, height = Spacing.xs)
                    .background(
                        if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}

/** Paste an `aria://provision` link from the admin's email or chat. */
@Composable
private fun SetupLinkEntrySheet(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    CallSheet(title = stringResource(R.string.ui_onboarding_open_link), onDismiss = onDismiss) {
        Text(
            stringResource(R.string.ui_onboarding_link_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link,
            onValueChange = { link = it.trim() },
            label = { Text(stringResource(R.string.ui_onboarding_link_field)) },
            singleLine = true,
            shape = AriaShapes.textField,
            modifier = Modifier.fillMaxWidth(),
        )
        AriaButton(
            stringResource(R.string.ui_onboarding_continue),
            onClick = { onOpen(link) },
            enabled = link.startsWith("aria://", ignoreCase = true),
            icon = AriaIcons.Link,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
