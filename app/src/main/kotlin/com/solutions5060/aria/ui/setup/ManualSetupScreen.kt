package com.solutions5060.aria.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.solutions5060.aria.R
import com.solutions5060.aria.provisioning.DEFAULT_API_URL
import com.solutions5060.aria.provisioning.ManualSetupError
import com.solutions5060.aria.provisioning.ManualSetupField
import com.solutions5060.aria.provisioning.ManualSetupInput
import com.solutions5060.aria.provisioning.ManualSetupResult
import com.solutions5060.aria.provisioning.ProvisioningCredentials
import com.solutions5060.aria.provisioning.validateManualSetup
import com.solutions5060.aria.service.PbxApiClient
import com.solutions5060.aria.ui.SecureWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test tags, shared with the instrumented tests. */
object ManualSetupTags {
    const val SERVER = "manual_setup_server"
    const val EXTENSION = "manual_setup_extension"
    const val PASSWORD = "manual_setup_password"
    const val API_URL = "manual_setup_api_url"
    const val SIGN_IN = "manual_setup_sign_in"
    const val FORM_ERROR = "manual_setup_form_error"
}

@StringRes
private fun ManualSetupError.message(): Int = when (this) {
    ManualSetupError.SERVER_REQUIRED -> R.string.manual_setup_error_server_required
    ManualSetupError.SERVER_INVALID -> R.string.manual_setup_error_server_invalid
    ManualSetupError.EXTENSION_REQUIRED -> R.string.manual_setup_error_extension_required
    ManualSetupError.EXTENSION_INVALID -> R.string.manual_setup_error_extension_invalid
    ManualSetupError.PASSWORD_REQUIRED -> R.string.manual_setup_error_password_required
    ManualSetupError.API_URL_NOT_HTTPS -> R.string.manual_setup_error_api_not_https
    ManualSetupError.API_URL_INVALID -> R.string.manual_setup_error_api_invalid
}

/**
 * Signs in by hand: tenant domain or server, extension, password and an
 * optional API URL. The form is validated locally, then the credentials are
 * checked with the PBX's extension login before they are saved, so a typo
 * is reported here rather than as a failed connection later. On success
 * [onSignedIn] receives the same credentials a QR code would produce.
 *
 * [verify] is injectable for tests; by default it performs the real login.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualSetupScreen(
    onBack: () -> Unit,
    onSignedIn: (ProvisioningCredentials) -> Unit,
    verify: suspend (ProvisioningCredentials) -> Unit = ::verifyWithPbx,
) {
    // The password is on screen.
    SecureWindow()

    var server by remember { mutableStateOf("") }
    var extension by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiUrl by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<Map<ManualSetupField, ManualSetupError>>(emptyMap()) }
    @StringRes var formError by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        when (val result = validateManualSetup(ManualSetupInput(server, extension, password, apiUrl))) {
            is ManualSetupResult.Invalid -> {
                errors = result.errors
                formError = null
            }
            is ManualSetupResult.Valid -> {
                errors = emptyMap()
                formError = null
                busy = true
                scope.launch {
                    val failure = try {
                        verify(result.credentials)
                        null
                    } catch (e: PbxApiClient.HttpException) {
                        when (e.code) {
                            400, 401, 403 -> R.string.manual_setup_error_credentials
                            404 -> R.string.manual_setup_error_not_found
                            else -> R.string.manual_setup_error_network
                        }
                    } catch (_: Exception) {
                        R.string.manual_setup_error_network
                    }
                    busy = false
                    if (failure == null) onSignedIn(result.credentials) else formError = failure
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.manual_setup_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.manual_setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            SetupField(
                value = server,
                onValueChange = { server = it; errors = errors - ManualSetupField.SERVER },
                label = R.string.manual_setup_server,
                placeholder = stringResource(R.string.manual_setup_server_hint),
                error = errors[ManualSetupField.SERVER],
                keyboardType = KeyboardType.Uri,
                tag = ManualSetupTags.SERVER,
            )
            SetupField(
                value = extension,
                onValueChange = { extension = it; errors = errors - ManualSetupField.EXTENSION },
                label = R.string.manual_setup_extension,
                error = errors[ManualSetupField.EXTENSION],
                keyboardType = KeyboardType.Ascii,
                tag = ManualSetupTags.EXTENSION,
            )
            SetupField(
                value = password,
                onValueChange = { password = it; errors = errors - ManualSetupField.PASSWORD },
                label = R.string.manual_setup_password,
                error = errors[ManualSetupField.PASSWORD],
                keyboardType = KeyboardType.Password,
                tag = ManualSetupTags.PASSWORD,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (showPassword) R.string.manual_setup_hide_password else R.string.manual_setup_show_password,
                            ),
                        )
                    }
                },
            )
            SetupField(
                value = apiUrl,
                onValueChange = { apiUrl = it; errors = errors - ManualSetupField.API_URL },
                label = R.string.manual_setup_api_url,
                placeholder = stringResource(R.string.manual_setup_api_url_hint, DEFAULT_API_URL),
                error = errors[ManualSetupField.API_URL],
                keyboardType = KeyboardType.Uri,
                tag = ManualSetupTags.API_URL,
                imeAction = ImeAction.Done,
                onDone = ::submit,
            )

            formError?.let {
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(ManualSetupTags.FORM_ERROR),
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = ::submit,
                enabled = !busy,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(ManualSetupTags.SIGN_IN),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.manual_setup_signing_in))
                } else {
                    Text(stringResource(R.string.manual_setup_sign_in))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SetupField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    error: ManualSetupError?,
    keyboardType: KeyboardType,
    tag: String,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.message())) } },
        singleLine = true,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

/** Checks the credentials with `POST /api/v1/auth/extension-login`; throws on failure. */
suspend fun verifyWithPbx(creds: ProvisioningCredentials) {
    withContext(Dispatchers.IO) {
        PbxApiClient(creds.apiUrl).extensionLogin(creds.username, creds.password, creds.tenantDomain)
    }
}
