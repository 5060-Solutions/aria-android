package com.solutions5060.aria.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.pbx.StoredPbxAuth
import com.solutions5060.aria.pbx.withAuth
import com.solutions5060.aria.proximity.BleProximity
import com.solutions5060.aria.proximity.HandoffPrefs
import com.solutions5060.aria.proximity.HandoffSettings
import com.solutions5060.aria.proximity.ProximityJson
import com.solutions5060.aria.service.PbxApiClient
import com.solutions5060.aria.ui.components.AriaIcon
import com.solutions5060.aria.ui.components.AriaListItem
import com.solutions5060.aria.ui.components.AriaScreen
import com.solutions5060.aria.ui.components.AriaSwitchRow
import com.solutions5060.aria.ui.components.LocalSnackbarHostState
import com.solutions5060.aria.ui.components.RowIcon
import com.solutions5060.aria.ui.components.RowShape
import com.solutions5060.aria.ui.theme.AriaIcons
import com.solutions5060.aria.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CallHandoffTags {
    const val ROW = "settings_call_handoff"
    const val ENABLED = "handoff_enabled"
    const val AUTO_MOVE_ON_LEAVE = "handoff_auto_move_on_leave"
    const val BLE = "handoff_ble"
}

/** The way into [CallHandoffScreen], shown in the Calling group. */
@Composable
fun CallHandoffRow(onOpen: () -> Unit) {
    AriaListItem(
        headline = stringResource(R.string.handoff_title),
        supporting = stringResource(R.string.handoff_summary),
        leading = { RowIcon(AriaIcons.Swap) },
        trailing = { AriaIcon(AriaIcons.ChevronRight, contentDescription = null) },
        onClick = onOpen,
        shape = RowShape.Pill,
        modifier = Modifier.testTag(CallHandoffTags.ROW),
    )
}

/**
 * `/me/call-handoff`: whether this phone is offered a call from another
 * device, whether the call follows it when it leaves its network, and Bluetooth proximity.
 * Bluetooth is off by default; its three permissions are asked for only when
 * the user turns it on.
 */
@Composable
fun CallHandoffScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    var settings by remember { mutableStateOf(HandoffPrefs.load(context)) }
    var loaded by remember { mutableStateOf(false) }
    var askBle by remember { mutableStateOf(false) }
    val loadFailed = stringResource(R.string.handoff_load_failed)
    val bleDenied = stringResource(R.string.handoff_ble_denied)
    val bleUnsupported = stringResource(R.string.handoff_ble_unsupported)

    fun save(next: HandoffSettings) {
        settings = next
        HandoffPrefs.save(context, next)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val auth = StoredPbxAuth(context)
                    auth.withAuth { PbxApiClient(auth.apiUrl()).setCallHandoff(it, ProximityJson.settingsBody(next)) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                snackbar.showSnackbar(loadFailed)
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val remote = withContext(Dispatchers.IO) {
                val auth = StoredPbxAuth(context)
                ProximityJson.settings(auth.withAuth { PbxApiClient(auth.apiUrl()).callHandoff(it) })
            }
            // Bluetooth also needs its permissions here; the PBX's flag alone cannot start it.
            val merged = remote.copy(ble = remote.ble && BleProximity.hasPermissions(context))
            settings = merged
            HandoffPrefs.save(context, merged)
            loaded = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            snackbar.showSnackbar(loadFailed)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.isNotEmpty() && result.values.all { it }) {
            save(settings.copy(ble = true))
            BleProximity.start(context)
        } else {
            scope.launch { snackbar.showSnackbar(bleDenied) }
        }
    }

    AriaScreen(title = stringResource(R.string.handoff_title), onBack = onBack) { padding: PaddingValues ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.rowGap),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                stringResource(R.string.handoff_desc),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
            AriaSwitchRow(
                headline = stringResource(R.string.handoff_enabled),
                checked = settings.enabled,
                onCheckedChange = { save(settings.copy(enabled = it)) },
                modifier = Modifier.testTag(CallHandoffTags.ENABLED),
            )
            AriaSwitchRow(
                headline = stringResource(R.string.handoff_auto_leave),
                supporting = stringResource(R.string.handoff_auto_leave_desc),
                checked = settings.autoMoveOnLeave,
                enabled = settings.enabled,
                onCheckedChange = { save(settings.copy(autoMoveOnLeave = it)) },
                leading = { RowIcon(AriaIcons.Wifi) },
                modifier = Modifier.testTag(CallHandoffTags.AUTO_MOVE_ON_LEAVE),
            )
            AriaSwitchRow(
                headline = stringResource(R.string.handoff_ble),
                supporting = stringResource(if (BleProximity.supported()) R.string.handoff_ble_desc else R.string.handoff_ble_unsupported),
                checked = settings.ble,
                enabled = settings.enabled && BleProximity.supported(),
                onCheckedChange = { on ->
                    when {
                        !on -> {
                            save(settings.copy(ble = false))
                            BleProximity.stop(context)
                        }
                        !BleProximity.supported() -> scope.launch { snackbar.showSnackbar(bleUnsupported) }
                        BleProximity.hasPermissions(context) -> {
                            save(settings.copy(ble = true))
                            BleProximity.start(context)
                        }
                        else -> askBle = true
                    }
                },
                leading = { RowIcon(AriaIcons.Bluetooth) },
                modifier = Modifier.testTag(CallHandoffTags.BLE),
            )
        }
    }

    if (askBle) {
        AlertDialog(
            onDismissRequest = { askBle = false },
            title = { Text(stringResource(R.string.handoff_ble_permission_title)) },
            text = { Text(stringResource(R.string.handoff_ble_permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askBle = false
                    permissionLauncher.launch(BleProximity.permissions)
                }) { Text(stringResource(R.string.perm_continue)) }
            },
            dismissButton = { TextButton(onClick = { askBle = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
