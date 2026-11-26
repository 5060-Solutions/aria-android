package com.solutions5060.aria.ui.connection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solutions5060.aria.R
import com.solutions5060.aria.call.CodecOrder
import com.solutions5060.aria.call.CodecPreferences
import com.solutions5060.aria.service.ConnectionStatus
import com.solutions5060.aria.service.DiagLog
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaScreen
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.RowGroup
import com.solutions5060.aria.ui.components.SectionCard
import androidx.compose.ui.graphics.Color
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.LocalAriaColors
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.util.Locale

object DiagnosticsTags {
    const val COPY = "diagnostics_copy"
    const val LOG = "diagnostics_log"
    const val GATEWAY_NODE = "diagnostics_gateway_node"
    const val CODEC = "diagnostics_codec"
    const val ENCRYPTION = "diagnostics_encryption"
    const val ROW = "settings_diagnostics"
}

@StringRes
private fun ConnectionStatus.label(recovering: Boolean): Int = when {
    recovering -> R.string.diag_status_recovering
    this == ConnectionStatus.REGISTERED -> R.string.diag_status_registered
    this == ConnectionStatus.CONNECTING -> R.string.diag_status_connecting
    this == ConnectionStatus.ERROR -> R.string.diag_status_error
    this == ConnectionStatus.OFFLINE -> R.string.diag_status_offline
    else -> R.string.diag_status_signed_out
}

/**
 * Settings > Diagnostics: the full [ConnectionDetails] (registration,
 * gateway and node, transport, network, the live call's codec, SRTP and
 * media stats, codec order) and the event log, with Copy for support.
 * The status chip's connection sheet shows the short form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, vm: ConnectionDetailsViewModel = viewModel()) {
    val details by vm.details.collectAsState()
    val log by DiagLog.lines.collectAsState()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.diag_copied)
    val dash = stringResource(R.string.diag_none)

    AriaScreen(
        title = stringResource(R.string.diag_title),
        onBack = onBack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
        ) {
            RowGroup(stringResource(R.string.diag_registration)) {
                DetailRow(R.string.diag_status, stringResource(details.status.label(details.connection.recovering)))
                details.error?.let { DetailRow(R.string.diag_last_error, it) }
                DetailRow(R.string.diag_account, "${details.extension} @ ${details.domain}".trim().ifBlank { dash })
                DetailRow(R.string.diag_transport, details.transport.uppercase().ifBlank { dash })
            }
            RowGroup(stringResource(R.string.diag_gateway)) {
                DetailRow(R.string.diag_gateway_url, details.connection.gatewayUrl ?: dash)
                DetailRow(R.string.diag_gateway_node, details.gatewayNode ?: dash, Modifier.testTag(DiagnosticsTags.GATEWAY_NODE))
                DetailRow(R.string.diag_device_id, details.connection.deviceId ?: dash)
            }
            RowGroup(stringResource(R.string.diag_network)) {
                val net = details.network
                DetailRow(
                    R.string.diag_network_path,
                    if (!net.online) {
                        stringResource(R.string.diag_offline)
                    } else {
                        net.transports.joinToString(" + ") { transportLabel(it) }
                            .ifEmpty { stringResource(R.string.diag_online) }
                    },
                )
            }
            RowGroup(stringResource(R.string.diag_call)) {
                val call = details.call
                if (call == null) {
                    DetailRow(R.string.diag_call_none, dash)
                } else {
                    DetailRow(R.string.diag_codec, call.codecLabel ?: dash, Modifier.testTag(DiagnosticsTags.CODEC))
                    // Never hidden: an unencrypted call says so, in amber.
                    DetailRow(
                        R.string.diag_srtp,
                        stringResource(
                            when (call.encrypted) {
                                true -> R.string.diag_srtp_on
                                false -> R.string.ui_connection_encryption_none
                                null -> R.string.diag_none
                            },
                        ),
                        Modifier.testTag(DiagnosticsTags.ENCRYPTION),
                        valueColor = if (call.encrypted == false) LocalAriaColors.current.warning else null,
                    )
                    call.stats?.let { s ->
                        DetailRow(R.string.diag_packets, stringResource(R.string.diag_packets_value, s.packetsSent.toLong(), s.packetsReceived.toLong(), s.packetLoss.toLong()))
                        DetailRow(
                            R.string.diag_jitter_rtt,
                            stringResource(R.string.diag_jitter_rtt_value, String.format(Locale.ROOT, "%.1f", s.jitterMs), String.format(Locale.ROOT, "%.0f", s.rttMs)),
                        )
                    }
                }
                DetailRow(R.string.diag_codec_order, CodecOrder.offered(details.codecOrder).joinToString(", ") { CodecPreferences.label(it) })
            }
            RowGroup(stringResource(R.string.diag_log)) {
                SectionCard {
                    SelectionContainer {
                        Text(
                            log.takeLast(200).joinToString("\n").ifEmpty { stringResource(R.string.diag_log_empty) },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .testTag(DiagnosticsTags.LOG),
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(bottom = Spacing.xl)) {
                AriaButton(
                    text = stringResource(R.string.diag_copy),
                    onClick = {
                        copy(context, details.report(DiagLog.text()))
                        scope.launch { snackbar.showSnackbar(copied) }
                    },
                    icon = AriaIcons.Copy,
                    style = AriaButtonStyle.Standard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(DiagnosticsTags.COPY),
                )
                AriaButton(
                    text = stringResource(R.string.diag_reconnect),
                    onClick = vm::reconnect,
                    icon = AriaIcons.Refresh,
                    style = AriaButtonStyle.Plain,
                    enabled = details.status != ConnectionStatus.SIGNED_OUT,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(@StringRes label: Int, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    AriaListItem(
        headline = stringResource(label),
        supporting = value,
        supportingColor = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Aria diagnostics", text))
}

/** Settings > Diagnostics (DESIGN.md §5 Settings 6): the way into [DiagnosticsScreen]. */
@Composable
fun DiagnosticsSection(onOpen: () -> Unit) {
    RowGroup(stringResource(R.string.diag_section)) {
        AriaListItem(
            headline = stringResource(R.string.diag_title),
            supporting = stringResource(R.string.diag_row_desc),
            leading = { com.solutions5060.aria.ui.components.RowIcon(AriaIcons.Diagnostics) },
            trailing = { AriaIcon(AriaIcons.ChevronRight, contentDescription = null) },
            onClick = onOpen,
            shape = com.solutions5060.aria.ui.components.RowShape.Pill,
            modifier = Modifier.testTag(DiagnosticsTags.ROW),
        )
    }
}

/** Network transport names as people know them. */
fun transportLabel(t: com.solutions5060.aria.service.NetTransport): String = when (t) {
    com.solutions5060.aria.service.NetTransport.WIFI -> "Wi-Fi"
    com.solutions5060.aria.service.NetTransport.CELLULAR -> "Cellular"
    com.solutions5060.aria.service.NetTransport.ETHERNET -> "Ethernet"
    com.solutions5060.aria.service.NetTransport.VPN -> "VPN"
    com.solutions5060.aria.service.NetTransport.OTHER -> "Other"
}
