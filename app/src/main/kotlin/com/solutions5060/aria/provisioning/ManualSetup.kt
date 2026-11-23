package com.solutions5060.aria.provisioning

import java.net.URI

/** What the user typed on the manual setup screen. */
data class ManualSetupInput(
    /** The tenant's SIP domain or server host, e.g. `acme.sip.ariaroute.com`. */
    val server: String,
    val extension: String,
    val password: String,
    /** Optional; [DEFAULT_API_URL] when blank. */
    val apiUrl: String = "",
)

/** A field the manual setup screen marks as wrong. */
enum class ManualSetupField { SERVER, EXTENSION, PASSWORD, API_URL }

/** Why a field was rejected; the screen maps each to a string resource. */
enum class ManualSetupError {
    SERVER_REQUIRED,
    SERVER_INVALID,
    EXTENSION_REQUIRED,
    EXTENSION_INVALID,
    PASSWORD_REQUIRED,
    API_URL_NOT_HTTPS,
    API_URL_INVALID,
}

sealed interface ManualSetupResult {
    data class Valid(val credentials: ProvisioningCredentials) : ManualSetupResult
    data class Invalid(val errors: Map<ManualSetupField, ManualSetupError>) : ManualSetupResult
}

/** The Aria cloud API, used when the manual setup form leaves the API URL blank. */
const val DEFAULT_API_URL = "https://api.ariaroute.com"

private val EXTENSION_REGEX = Regex("^[A-Za-z0-9._+-]{1,64}$")
private val HOST_REGEX = Regex("^(([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)\\.)+[a-zA-Z]{2,}$")
private val IPV4_REGEX = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")

private fun validHost(host: String?): Boolean =
    !host.isNullOrBlank() && (HOST_REGEX.matches(host) || IPV4_REGEX.matches(host))

/**
 * Validates the manual setup form and builds the same credentials a QR code
 * would, so sign-in then follows the existing path (extension login, device
 * registration, engine start). The API URL must be https; a bare host gets
 * https, and `http://` is refused rather than upgraded silently. Signalling
 * defaults to TLS, as for a QR without a transport.
 */
fun validateManualSetup(input: ManualSetupInput): ManualSetupResult {
    val errors = linkedMapOf<ManualSetupField, ManualSetupError>()

    // Accept what people paste: a URL or a sip: URI around the host.
    val server = input.server.trim()
        .removePrefix("https://").removePrefix("sip:").removePrefix("sips:")
        .trimEnd('/')
        .lowercase()
    when {
        server.isEmpty() -> errors[ManualSetupField.SERVER] = ManualSetupError.SERVER_REQUIRED
        !validHost(server) -> errors[ManualSetupField.SERVER] = ManualSetupError.SERVER_INVALID
    }

    val extension = input.extension.trim()
    when {
        extension.isEmpty() -> errors[ManualSetupField.EXTENSION] = ManualSetupError.EXTENSION_REQUIRED
        !EXTENSION_REGEX.matches(extension) -> errors[ManualSetupField.EXTENSION] = ManualSetupError.EXTENSION_INVALID
    }

    // Passwords are taken verbatim: spaces may be part of them.
    if (input.password.isEmpty()) errors[ManualSetupField.PASSWORD] = ManualSetupError.PASSWORD_REQUIRED

    val apiUrl = normaliseApiUrl(input.apiUrl)
    when (apiUrl) {
        is ApiUrl.NotHttps -> errors[ManualSetupField.API_URL] = ManualSetupError.API_URL_NOT_HTTPS
        is ApiUrl.Invalid -> errors[ManualSetupField.API_URL] = ManualSetupError.API_URL_INVALID
        is ApiUrl.Ok -> {}
    }

    if (errors.isNotEmpty() || apiUrl !is ApiUrl.Ok) return ManualSetupResult.Invalid(errors)

    val creds = ProvisioningCredentials(
        server = server,
        port = defaultSipPort(DEFAULT_SIP_TRANSPORT),
        username = extension,
        password = input.password,
        displayName = "",
        transport = DEFAULT_SIP_TRANSPORT,
        voicemail = "",
        apiUrl = apiUrl.url,
        tenantDomain = server,
    )
    // The same gate a QR code passes.
    return if (validateProvisioning(creds) == null) {
        ManualSetupResult.Valid(creds)
    } else {
        ManualSetupResult.Invalid(mapOf(ManualSetupField.SERVER to ManualSetupError.SERVER_INVALID))
    }
}

private sealed interface ApiUrl {
    data class Ok(val url: String) : ApiUrl
    data object NotHttps : ApiUrl
    data object Invalid : ApiUrl
}

private fun normaliseApiUrl(raw: String): ApiUrl {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ApiUrl.Ok(DEFAULT_API_URL)
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = try {
        URI(withScheme)
    } catch (_: Exception) {
        return ApiUrl.Invalid
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) return ApiUrl.NotHttps
    // No credentials in the URL, no query or fragment: just an origin and an optional base path.
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return ApiUrl.Invalid
    if (!validHost(uri.host)) return ApiUrl.Invalid
    val port = if (uri.port == -1) "" else ":${uri.port}"
    if (uri.port != -1 && uri.port !in 1..65535) return ApiUrl.Invalid
    val path = uri.rawPath.orEmpty().trimEnd('/')
    return ApiUrl.Ok("https://${uri.host.lowercase()}$port$path")
}
