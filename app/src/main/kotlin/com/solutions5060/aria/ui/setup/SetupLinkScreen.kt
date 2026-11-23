package com.solutions5060.aria.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.solutions5060.aria.R
import com.solutions5060.aria.provisioning.PreparedSetupLink
import com.solutions5060.aria.provisioning.ProvisioningCredentials
import com.solutions5060.aria.provisioning.SetupLinkError
import com.solutions5060.aria.provisioning.SetupLinkException
import com.solutions5060.aria.provisioning.SetupLinkFlow
import com.solutions5060.aria.ui.components.AriaButton
import com.solutions5060.aria.ui.components.AriaButtonStyle
import com.solutions5060.aria.ui.components.SectionCard
import com.solutions5060.aria.ui.theme.Sizes
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.launch

object SetupLinkTags {
    const val CONFIRM = "setup_link_confirm"
    const val SERVER_HOST = "setup_link_server_host"
    const val ERROR = "setup_link_error"
}

@StringRes
private fun SetupLinkError.message(): Int = when (this) {
    SetupLinkError.INVALID -> R.string.setup_link_error_invalid
    SetupLinkError.EXPIRED -> R.string.setup_link_error_expired
    SetupLinkError.NETWORK -> R.string.setup_link_error_network
    SetupLinkError.MISMATCH -> R.string.setup_link_error_mismatch
    SetupLinkError.SERVER -> R.string.setup_link_error_server
    SetupLinkError.INSECURE -> R.string.setup_link_error_insecure
}

/**
 * Confirms an `aria://provision` link before it is used: previews it (v=1
 * links do not use up the token on preview), shows the organisation,
 * extension and setup server, and only redeems it when the user confirms.
 * [replacingAccount] adds a warning that the current account is signed out.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SetupLinkScreen(
    link: String,
    replacingAccount: Boolean,
    onAccount: (ProvisioningCredentials) -> Unit,
    onDismiss: () -> Unit,
    flow: SetupLinkFlow = remember { SetupLinkFlow() },
) {
    var prepared by remember { mutableStateOf<PreparedSetupLink?>(null) }
    var error by remember { mutableStateOf<SetupLinkError?>(null) }
    var redeeming by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(link, attempt) {
        error = null
        prepared = null
        try {
            prepared = flow.prepare(link)
        } catch (e: SetupLinkException) {
            error = e.error
        } catch (_: Exception) {
            error = SetupLinkError.NETWORK
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen, vertical = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.setup_link_title), style = MaterialTheme.typography.headlineMedium)
            val current = prepared
            val failed = error
            when {
                failed != null -> {
                    Text(
                        stringResource(failed.message()),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(SetupLinkTags.ERROR),
                    )
                    val retryable = failed == SetupLinkError.NETWORK || failed == SetupLinkError.SERVER
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        AriaButton(stringResource(R.string.action_cancel), onClick = onDismiss, style = AriaButtonStyle.Plain)
                        if (retryable && current == null) {
                            AriaButton(stringResource(R.string.action_retry), onClick = { attempt++ }, style = AriaButtonStyle.Standard)
                        }
                    }
                }
                current == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(modifier = Modifier.size(Sizes.icon))
                    Spacer(Modifier.padding(start = Spacing.sm))
                    Text(stringResource(R.string.setup_link_loading))
                }
            }
            if (current != null) {
                Text(stringResource(R.string.setup_link_body), style = MaterialTheme.typography.bodyMedium)
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        val s = current.summary
                        SummaryRow(R.string.setup_link_tenant, s.tenantName)
                        SummaryRow(R.string.setup_link_extension, s.extension)
                        if (s.displayName.isNotBlank() && s.displayName != s.extension) {
                            SummaryRow(R.string.setup_link_name, s.displayName)
                        }
                        SummaryRow(R.string.setup_link_domain, s.domain)
                        SummaryRow(R.string.setup_link_server, s.serverHost, Modifier.testTag(SetupLinkTags.SERVER_HOST))
                    }
                }
                if (replacingAccount) {
                    Text(
                        stringResource(R.string.setup_link_replace_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                AriaButton(
                    text = stringResource(if (redeeming) R.string.setup_link_redeeming else R.string.setup_link_confirm),
                    onClick = {
                        redeeming = true
                        error = null
                        scope.launch {
                            try {
                                onAccount(flow.redeem(current))
                            } catch (e: SetupLinkException) {
                                error = e.error
                            } catch (_: Exception) {
                                error = SetupLinkError.NETWORK
                            }
                            redeeming = false
                        }
                    },
                    loading = redeeming,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SetupLinkTags.CONFIRM),
                )
                AriaButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    style = AriaButtonStyle.Plain,
                    enabled = !redeeming,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(@StringRes label: Int, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
