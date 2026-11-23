package com.solutions5060.aria.provisioning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.aria_mobile.ProvisionException
import uniffi.aria_mobile.ProvisionedAccount

/** What the confirm screen shows before a setup link is used. Nothing secret. */
data class SetupLinkSummary(
    val tenantName: String,
    val extension: String,
    val displayName: String,
    val domain: String,
    /** Who is setting the line up: the setup server (v=1) or the API host (legacy). */
    val serverHost: String,
    val expiresAt: String? = null,
)

/** Why a setup link could not be used; the screen maps each to a string resource. */
enum class SetupLinkError { INVALID, EXPIRED, NETWORK, MISMATCH, SERVER, INSECURE }

/** A link ready to confirm: a v=1 link to redeem, or a legacy payload already parsed. */
sealed interface PreparedSetupLink {
    val summary: SetupLinkSummary

    data class V1(val link: String, override val summary: SetupLinkSummary) : PreparedSetupLink
    data class Legacy(val credentials: ProvisioningCredentials, override val summary: SetupLinkSummary) : PreparedSetupLink
}

class SetupLinkException(val error: SetupLinkError) : Exception(error.name)

/**
 * The two-step setup link flow: [prepare] (preview, non-consuming) for the
 * confirm screen, then [redeem] after the user confirms. v=1 links go
 * through the core's `provision_preview` / `provision_redeem`, which enforce
 * the desktop rules (https only, fixed path prefix, no redirects, 10 s
 * timeout, 16 KB cap, redeemed account must match the preview). Legacy
 * `aria://provision?server&user&pass…` links keep using the app's parser.
 * Both block on the network, so they run on [Dispatchers.IO].
 */
class SetupLinkFlow(
    private val preview: (String) -> uniffi.aria_mobile.ProvisionPreview = { uniffi.aria_mobile.provisionPreview(it) },
    private val redeemLink: (String) -> ProvisionedAccount = { uniffi.aria_mobile.provisionRedeem(it) },
) {

    suspend fun prepare(link: String): PreparedSetupLink = withContext(Dispatchers.IO) {
        if (!DeepLinks.isV1ProvisionLink(link)) return@withContext prepareLegacy(link)
        val p = try {
            preview(link)
        } catch (e: ProvisionException) {
            // The core throws InvalidLink for a legacy link too; that was handled above.
            throw SetupLinkException(errorFor(e))
        }
        PreparedSetupLink.V1(
            link,
            SetupLinkSummary(
                tenantName = p.tenantName,
                extension = p.extension,
                displayName = p.displayName,
                domain = p.domain,
                serverHost = p.serverHost,
                expiresAt = p.expiresAt,
            ),
        )
    }

    /** Uses the link up and returns the account to sign in with. */
    suspend fun redeem(prepared: PreparedSetupLink): ProvisioningCredentials = withContext(Dispatchers.IO) {
        when (prepared) {
            is PreparedSetupLink.Legacy -> prepared.credentials
            is PreparedSetupLink.V1 -> {
                val account = try {
                    redeemLink(prepared.link)
                } catch (e: ProvisionException) {
                    throw SetupLinkException(errorFor(e))
                }
                val creds = credentialsFrom(account)
                if (validateProvisioning(creds) != null) throw SetupLinkException(SetupLinkError.INSECURE)
                creds
            }
        }
    }

    private fun prepareLegacy(link: String): PreparedSetupLink {
        val creds = parseProvisioningUri(link) ?: throw SetupLinkException(SetupLinkError.INVALID)
        if (validateProvisioning(creds) != null) throw SetupLinkException(SetupLinkError.INSECURE)
        return PreparedSetupLink.Legacy(
            creds,
            SetupLinkSummary(
                tenantName = creds.tenantDomain.ifEmpty { creds.server },
                extension = creds.username,
                displayName = creds.displayName,
                domain = creds.tenantDomain.ifEmpty { creds.server },
                serverHost = creds.apiHost,
            ),
        )
    }

    companion object {
        fun errorFor(e: ProvisionException): SetupLinkError = when (e) {
            is ProvisionException.InvalidLink -> SetupLinkError.INVALID
            is ProvisionException.Expired -> SetupLinkError.EXPIRED
            is ProvisionException.Network -> SetupLinkError.NETWORK
            is ProvisionException.Mismatch -> SetupLinkError.MISMATCH
            is ProvisionException.Server -> SetupLinkError.SERVER
        }

        /**
         * A redeemed account as the credentials a QR code gives, so sign-in
         * follows the same path: extension login against the setup server's
         * API (`https://<server_host>`), then device registration.
         */
        fun credentialsFrom(a: ProvisionedAccount): ProvisioningCredentials {
            val transport = a.transport.lowercase().takeIf { it in ALLOWED_SIP_TRANSPORTS } ?: DEFAULT_SIP_TRANSPORT
            val proxyHost = a.outboundProxy?.substringBefore(':')?.ifEmpty { null }
            val proxyPort = a.outboundProxy?.substringAfter(':', "")?.toIntOrNull()
            val port = a.port.toInt().takeIf { it > 0 } ?: proxyPort ?: defaultSipPort(transport)
            return ProvisioningCredentials(
                server = proxyHost ?: a.domain,
                port = port,
                username = a.username,
                password = a.password,
                displayName = a.displayName,
                transport = transport,
                voicemail = a.voicemail.orEmpty(),
                apiUrl = "https://${a.serverHost}",
                tenantDomain = a.domain,
            )
        }
    }
}
