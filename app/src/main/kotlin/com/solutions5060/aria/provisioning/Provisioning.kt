package com.solutions5060.aria.provisioning

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

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
) {
    /** The API endpoint's host, for showing the user where they are about to sign in. */
    val apiHost: String
        get() = try {
            URI(apiUrl).host ?: apiUrl
        } catch (_: Exception) {
            apiUrl
        }
}

/**
 * Signalling is encrypted unless the provisioning code explicitly asks for
 * something else: a QR without a transport gets TLS, not cleartext UDP.
 */
const val DEFAULT_SIP_TRANSPORT = "tls"

/** The transports this client actually implements. Anything else is a provisioning error, not a reason to fall back. */
val ALLOWED_SIP_TRANSPORTS = setOf("udp", "tcp", "tls")

/** The conventional SIP port for [transport] (5061 for TLS, 5060 otherwise). */
fun defaultSipPort(transport: String): Int = if (transport.equals("tls", ignoreCase = true)) 5061 else 5060

/**
 * Parses an `aria://provision?...` QR payload. Returns null when it is not
 * one, or a required field is missing or malformed.
 *
 * Plain JVM code (no android.net.Uri) so the rules are unit-tested. Query
 * values are form-decoded, as Uri.getQueryParameter did, and the first value
 * of a repeated key wins.
 */
fun parseProvisioningUri(uriString: String): ProvisioningCredentials? {
    return try {
        val trimmed = uriString.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0 || !trimmed.substring(0, schemeEnd).equals("aria", ignoreCase = true)) return null
        val rest = trimmed.substring(schemeEnd + 3)
        val host = rest.substringBefore('?').substringBefore('#').trimEnd('/')
        if (!host.equals("provision", ignoreCase = true)) return null
        val params = parseQuery(rest.substringAfter('?', "").substringBefore('#'))

        val server = params["server"] ?: return null
        val user = params["user"] ?: return null
        val passEncoded = params["pass"] ?: return null
        // The MIME decoder skips characters outside the alphabet, as
        // android.util.Base64 did, so a stray newline does not reject the code.
        val password = String(Base64.getMimeDecoder().decode(passEncoded), Charsets.UTF_8)
        // Normalised here; an unrecognised value is rejected by
        // validateProvisioning rather than silently becoming udp.
        val transport = params["transport"]?.trim()?.lowercase()?.ifEmpty { null } ?: DEFAULT_SIP_TRANSPORT
        val port = params["port"]?.toIntOrNull() ?: defaultSipPort(transport)

        ProvisioningCredentials(
            server = server,
            port = port,
            username = user,
            password = password,
            displayName = params["name"] ?: "",
            transport = transport,
            voicemail = params["vm"] ?: "",
            apiUrl = params["api"] ?: "",
            tenantDomain = params["tenant"] ?: "",
        )
    } catch (_: Exception) {
        // Do not log the payload — it embeds the base64 SIP password.
        null
    }
}

private fun parseQuery(query: String): Map<String, String> {
    val params = linkedMapOf<String, String>()
    if (query.isEmpty()) return params
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
        val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        params.putIfAbsent(key, value)
    }
    return params
}

// Hostname (RFC-1123-ish) or bare IPv4. Rejects empty, whitespace, scheme/path
// artifacts, and anything that does not look like a real host.
private val HOSTNAME_REGEX = Regex(
    "^(([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)\\.)+[a-zA-Z]{2,}$"
)
private val IPV4_REGEX = Regex("^(\\d{1,3})(\\.\\d{1,3}){3}$")

private fun isValidHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    if (host.any { it.isWhitespace() }) return false
    return HOSTNAME_REGEX.matches(host) || IPV4_REGEX.matches(host)
}

/**
 * Validates a parsed provisioning payload before it is trusted for login/SIP
 * registration, so a hostile QR cannot silently point the client at an
 * attacker-controlled server or a plain-http API endpoint.
 *
 * Returns null when the payload is acceptable, or a user-facing error message
 * describing why it was rejected.
 */
fun validateProvisioning(creds: ProvisioningCredentials): String? {
    if (!isValidHost(creds.server)) {
        return "Rejected: the provisioning server host is missing or invalid."
    }
    if (creds.apiUrl.isBlank()) {
        return "Rejected: the provisioning code is missing a secure API endpoint."
    }
    val apiUri = try {
        URI(creds.apiUrl)
    } catch (_: Exception) {
        null
    }
    if (apiUri == null ||
        !apiUri.scheme.equals("https", ignoreCase = true) ||
        !isValidHost(apiUri.host)
    ) {
        return "Rejected: the API endpoint must use https with a valid hostname."
    }
    // The transport was the one provisioning field taken verbatim: anything
    // unrecognised fell through to the "udp" default, so a QR could quietly
    // downgrade signalling to cleartext. An unknown value is now refused
    // outright rather than silently becoming the weakest option.
    if (creds.transport !in ALLOWED_SIP_TRANSPORTS) {
        return "Rejected: unsupported SIP transport \"${creds.transport}\"."
    }
    if (creds.port !in 1..65535) {
        return "Rejected: the SIP port ${creds.port} is out of range."
    }
    return null
}
