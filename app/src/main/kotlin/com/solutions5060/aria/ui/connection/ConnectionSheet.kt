package com.solutions5060.aria.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.solutions5060.aria.R
import com.solutions5060.aria.service.ConnectionState
import com.solutions5060.aria.service.ConnectionStatus
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.RegistrationStatus
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.components.StatusDot
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.AriaShapes
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing

/** The chip's status for a gateway [ConnectionStatus]. */
fun ConnectionStatus.toRegistrationStatus(): RegistrationStatus = when (this) {
    ConnectionStatus.REGISTERED -> RegistrationStatus.Registered
    ConnectionStatus.CONNECTING -> RegistrationStatus.Connecting
    ConnectionStatus.ERROR -> RegistrationStatus.Error
    ConnectionStatus.OFFLINE -> RegistrationStatus.Offline
    ConnectionStatus.SIGNED_OUT -> RegistrationStatus.SignedOut
}

/** The host part of a gateway URL ("push.ariaroute.com"), or the value as given. */
fun gatewayHost(url: String?): String? =
    url?.substringAfter("://")?.substringBefore('/')?.substringBefore('?')?.takeIf { it.isNotBlank() }

/**
 * DESIGN.md §5 Connection sheet, opened from the status chip: registration,
 * gateway node, transport and last error, with "Reconnect".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSheet(
    state: ConnectionState,
    account: String,
    transport: String,
    onReconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val status = state.status.toRegistrationStatus()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AriaShapes.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .navigationBarsPadding()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
        ) {
            Text(
                stringResource(R.string.ui_connection_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(bottom = Spacing.md)
                    .semantics { heading() },
            )
            AriaListItem(
                headline = stringResource(status.label),
                supporting = account.ifBlank { null },
                leading = {
                    Box(Modifier.size(Sizes.avatar), contentAlignment = Alignment.Center) { StatusDot(status) }
                },
            )
            AriaListItem(
                headline = stringResource(R.string.ui_connection_gateway),
                supporting = gatewayHost(state.gatewayUrl) ?: stringResource(R.string.ui_connection_unknown),
                leading = { RowIcon(AriaIcons.Server) },
            )
            AriaListItem(
                headline = stringResource(R.string.ui_connection_transport),
                supporting = transport.uppercase(),
                leading = { RowIcon(AriaIcons.Secure) },
            )
            if (!state.online) {
                AriaListItem(
                    headline = stringResource(R.string.ui_connection_no_network),
                    supporting = stringResource(R.string.ui_connection_no_network_body),
                    leading = { RowIcon(AriaIcons.Offline) },
                )
            }
            state.error?.let { error ->
                AriaListItem(
                    headline = stringResource(R.string.ui_connection_last_error),
                    supporting = error,
                    leading = {
                        RowIcon(
                            AriaIcons.Error,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            container = MaterialTheme.colorScheme.errorContainer,
                        )
                    },
                )
            }
            AriaButton(
                text = stringResource(R.string.ui_connection_reconnect),
                onClick = onReconnect,
                icon = AriaIcons.Sync,
                loading = state.status == ConnectionStatus.CONNECTING,
                enabled = state.status != ConnectionStatus.SIGNED_OUT,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md),
            )
        }
    }
}
