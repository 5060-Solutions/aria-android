package com.solutions5060.aria.ui.connection

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solutions5060.aria.R
import com.solutions5060.aria.service.ConnectionState
import com.solutions5060.aria.service.ConnectionStatus
import com.solutions5060.aria.service.NetTransport
import com.solutions5060.aria.service.NetworkSnapshot
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.RegistrationStatus
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.components.RowShape
import com.solutions5060.aria.ui.components.SectionCard
import com.solutions5060.aria.ui.components.SectionHeader
import com.solutions5060.aria.ui.components.StatusDot
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.LocalAriaColors
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import kotlin.math.roundToInt

/** The chip's status for a gateway [ConnectionStatus]. */
fun ConnectionStatus.toRegistrationStatus(): RegistrationStatus = when (this) {
    ConnectionStatus.REGISTERED -> RegistrationStatus.Registered
    ConnectionStatus.CONNECTING -> RegistrationStatus.Connecting
    ConnectionStatus.ERROR -> RegistrationStatus.Error
    ConnectionStatus.OFFLINE -> RegistrationStatus.Offline
    ConnectionStatus.SIGNED_OUT -> RegistrationStatus.SignedOut
}

/** As above, with a recovering CONNECTING shown as "Reconnecting…". */
fun ConnectionState.toRegistrationStatus(): RegistrationStatus =
    if (status == ConnectionStatus.CONNECTING && recovering) RegistrationStatus.Reconnecting else status.toRegistrationStatus()

/** The host of a gateway node reported as "host:port" (IPv6 in brackets kept whole). */
fun nodeHost(node: String?): String? {
    val n = node?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (n.startsWith("[")) return n.substringBefore("]") + "]"
    return if (n.count { it == ':' } == 1) n.substringBefore(':') else n
}

/** The host part of a gateway URL ("push.ariaroute.com"), or the value as given. */
fun gatewayHost(url: String?): String? =
    url?.substringAfter("://")?.substringBefore('/')?.substringBefore('?')?.takeIf { it.isNotBlank() }

/**
 * DESIGN.md §5 connection sheet, opened from the status chip, laid out like
 * iOS: a status card, a "Connection" group of icon rows (account, gateway
 * node, transport, and for a live call codec, encryption and media), a way
 * into Diagnostics, and a Reconnect button pinned below the scrolling content.
 * Live while open ([ConnectionDetailsViewModel] polls only while collected).
 */
@Composable
fun ConnectionSheet(
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
    vm: ConnectionDetailsViewModel = viewModel(),
) {
    val details by vm.details.collectAsState()
    ConnectionSheetContent(details, onReconnect = vm::reconnect, onOpenDiagnostics = onOpenDiagnostics, onDismiss = onDismiss)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheetContent(
    details: ConnectionDetails,
    onReconnect: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = details.connection
    val status = state.toRegistrationStatus()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AriaShapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.md),
        ) {
            Text(
                stringResource(R.string.ui_connection_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = Spacing.screen)
                    .padding(bottom = Spacing.md)
                    .semantics { heading() },
            )
            // Scrolls; the Reconnect button below stays put and never covers it.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                StatusCard(status, state)
                SectionHeader(stringResource(R.string.ui_connection_title))
                InfoGroup {
                    InfoRow(
                        AriaIcons.Person,
                        stringResource(R.string.ui_connection_account),
                        if (details.extension.isBlank()) "—" else "${details.extension}@${details.domain}",
                    )
                    InfoRow(
                        AriaIcons.Server,
                        stringResource(R.string.ui_connection_node),
                        nodeHost(details.gatewayNode) ?: gatewayHost(state.gatewayUrl) ?: stringResource(R.string.ui_connection_unknown),
                    )
                    InfoRow(
                        AriaIcons.Secure,
                        stringResource(R.string.ui_connection_transport),
                        details.transport.uppercase().ifBlank { "—" },
                    )
                    details.call?.let { call ->
                        InfoRow(AriaIcons.Codecs, stringResource(R.string.ui_connection_codec), call.codecLabel ?: "—")
                        InfoRow(
                            AriaIcons.Secure,
                            stringResource(R.string.ui_connection_encryption),
                            when (call.encrypted) {
                                true -> "SRTP"
                                false -> stringResource(R.string.ui_connection_encryption_none)
                                null -> "—"
                            },
                        )
                        call.stats?.let { st ->
                            InfoRow(
                                AriaIcons.Signal,
                                stringResource(R.string.ui_connection_media),
                                stringResource(
                                    R.string.ui_connection_media_value,
                                    st.packetLoss.toLong(),
                                    st.jitterMs.roundToInt(),
                                    st.rttMs.roundToInt(),
                                ),
                            )
                        }
                    }
                    InfoRow(AriaIcons.Wifi, stringResource(R.string.ui_connection_network), networkLabel(details.network), last = true)
                }
                AriaListItem(
                    headline = stringResource(R.string.ui_settings_diagnostics),
                    leading = { RowIcon(AriaIcons.Diagnostics) },
                    trailing = { AriaIcon(AriaIcons.ChevronRight, null) },
                    onClick = onOpenDiagnostics,
                    shape = RowShape.Pill,
                )
                Spacer(Modifier.height(Spacing.xs))
            }
            AriaButton(
                text = stringResource(R.string.ui_connection_reconnect),
                onClick = onReconnect,
                icon = AriaIcons.Sync,
                loading = state.status == ConnectionStatus.CONNECTING || state.recovering,
                enabled = state.status != ConnectionStatus.SIGNED_OUT,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen)
                    .padding(top = Spacing.sm),
            )
        }
    }
}

@Composable
private fun networkLabel(network: NetworkSnapshot): String = when {
    !network.online -> stringResource(R.string.ui_connection_offline)
    NetTransport.WIFI in network.transports -> stringResource(R.string.ui_connection_wifi)
    NetTransport.CELLULAR in network.transports -> stringResource(R.string.ui_connection_cellular)
    NetTransport.ETHERNET in network.transports -> stringResource(R.string.ui_connection_ethernet)
    else -> stringResource(R.string.ui_connection_online)
}

/** The large status card at the top: icon, status, one line on what it means. */
@Composable
private fun StatusCard(status: RegistrationStatus, state: ConnectionState) {
    val aria = LocalAriaColors.current
    val color = status.color(aria)
    val ok = status == RegistrationStatus.Registered
    val line = when {
        !state.online -> stringResource(R.string.ui_connection_no_network_body)
        ok -> stringResource(R.string.ui_connection_ok_body)
        status == RegistrationStatus.Connecting || status == RegistrationStatus.Reconnecting ->
            stringResource(R.string.ui_connection_connecting_body)
        else -> state.error ?: stringResource(R.string.ui_connection_down_body)
    }
    SectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Box(
                Modifier
                    .size(Sizes.avatar)
                    .background(color.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (ok) AriaIcon(AriaIcons.CheckCircle, null, tint = color) else StatusDot(status)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(status.label), style = MaterialTheme.typography.titleLarge)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** An inset group of label/value rows with hairlines between (iOS-style grouped list). */
@Composable
private fun InfoGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = AriaShapes.card, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = Spacing.md), content = content)
    }
}

@Composable
private fun InfoRow(@DrawableRes icon: Int, label: String, value: String, last: Boolean = false) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.listRow)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AriaIcon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                // Domains and hosts keep both ends readable.
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = Sizes.icon + Spacing.md))
    }
}
