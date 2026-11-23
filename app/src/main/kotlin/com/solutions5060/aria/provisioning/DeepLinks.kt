package com.solutions5060.aria.provisioning

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder

/** Something another app (or a web page, or a QR code) asked Aria to do. */
sealed interface DeepLink {
    /** An `aria://provision` link: v=1 setup link or legacy QR payload. Always confirmed first. */
    data class Provision(val link: String) : DeepLink

    /** A `tel:` or `sip:` target to dial. Prefilled and confirmed, never called directly. */
    data class Dial(val target: String) : DeepLink

    /** Open a tab (from one of the app's own notifications). Only the known tabs. */
    data class OpenTab(val route: String) : DeepLink {
        companion object {
            val ROUTES = setOf("dialer", "contacts", "history", "voicemail", "settings")
        }
    }
}

/**
 * Turns incoming intents into [DeepLink]s and holds the one waiting for the
 * UI. Parsing is plain JVM (no android.net.Uri) so it is unit-tested.
 *
 * Nothing here acts on a link: a setup link shows a confirm screen with the
 * tenant, extension and setup server, and a dial link prefills the dialer
 * and asks before calling, so a web page cannot reconfigure the app or start
 * a call by itself.
 */
object DeepLinks {

    private const val MAX_DIAL_LENGTH = 64
    private val TEL_ALLOWED = Regex("^\\+?[0-9*#]{1,$MAX_DIAL_LENGTH}$")
    private val SIP_USER = Regex("^[A-Za-z0-9._~!$&'()*+,=;?/%-]{1,64}$")
    private val SIP_HOST = Regex("^[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?$")

    private val _pending = MutableStateFlow<DeepLink?>(null)

    /** The link waiting for the UI, if any. */
    val pending: StateFlow<DeepLink?> = _pending.asStateFlow()

    /** Queues [link] for the UI, replacing one not yet handled. */
    fun offer(link: DeepLink?) {
        if (link != null) _pending.value = link
    }

    /** The UI took [link]; drop it unless a newer one arrived meanwhile. */
    fun consume(link: DeepLink) {
        _pending.compareAndSet(link, null)
    }

    /**
     * The link an intent with [action] and [data] (`Intent.getDataString()`)
     * carries, or null when it carries none Aria acts on.
     */
    fun parse(action: String?, data: String?): DeepLink? {
        if (data.isNullOrBlank()) return null
        val scheme = data.substringBefore(':', "").lowercase()
        return when {
            scheme == "aria" && isProvisionLink(data) && (action == null || action == ACTION_VIEW) ->
                DeepLink.Provision(data.trim())
            (scheme == "tel" || scheme == "sip" || scheme == "sips") &&
                (action == null || action == ACTION_VIEW || action == ACTION_DIAL) ->
                dialTarget(data)?.let { DeepLink.Dial(it) }
            else -> null
        }
    }

    /** `aria://provision…` (any query). */
    fun isProvisionLink(link: String): Boolean {
        val t = link.trim()
        if (!t.startsWith("aria://", ignoreCase = true)) return false
        val host = t.substring("aria://".length).substringBefore('?').substringBefore('#').trimEnd('/')
        return host.equals("provision", ignoreCase = true)
    }

    /** A provision link with `v=1`: redeemed through the core, not parsed here. */
    fun isV1ProvisionLink(link: String): Boolean {
        if (!isProvisionLink(link)) return false
        val query = link.trim().substringAfter('?', "").substringBefore('#')
        return query.split('&').any { it == "v=1" }
    }

    /**
     * What to dial for a `tel:` or `sip:` URI: digits (with a leading `+`,
     * `*` and `#`) for tel, with visual separators and parameters removed;
     * `sip:user@host` for sip, without parameters or headers. Null when it is
     * not something Aria can dial.
     */
    fun dialTarget(uri: String): String? {
        val trimmed = uri.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        val rest = trimmed.substringAfter(':', "").removePrefix("//")
        val decoded = try {
            URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            return null
        }
        return when (scheme) {
            "tel" -> {
                val number = decoded.substringBefore(';').filterNot { it in " -.()\t" }
                number.takeIf { TEL_ALLOWED.matches(it) }
            }
            "sip", "sips" -> {
                val bare = decoded.substringBefore(';').substringBefore('?')
                val user = bare.substringBefore('@', "")
                val host = bare.substringAfter('@', "")
                when {
                    user.isEmpty() && SIP_HOST.matches(bare) -> null
                    !SIP_USER.matches(user) || !SIP_HOST.matches(host) -> null
                    else -> "sip:$user@${host.lowercase()}"
                }
            }
            else -> null
        }
    }

    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_DIAL = "android.intent.action.DIAL"
}
