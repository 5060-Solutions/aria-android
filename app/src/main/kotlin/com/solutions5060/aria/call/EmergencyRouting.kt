package com.solutions5060.aria.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.solutions5060.aria.R
import com.solutions5060.aria.Txt

/**
 * Emergency numbers never go over VoIP. The gateway cannot give a
 * dispatcher this phone's location or a callback path, so every place a
 * call can start (dialer, history, contacts, voicemail call-back, tel:/sip:
 * intents, consult calls) goes through [CallManager.placeCall], which asks
 * [isEmergency] first and hands a match to the native dialer with
 * `ACTION_DIAL`. The user then presses call in the phone app, which uses
 * the cellular network and the device's emergency-call handling.
 */
object EmergencyRouting {

    private const val TAG = "EmergencyRouting"

    /**
     * The desktop list, used only if the core cannot answer (its native
     * library failed to load). Failing open here would route 911 over VoIP.
     */
    private val FALLBACK_NUMBERS = setOf("911", "112", "999", "000")

    /** Overridable in tests, where the native core is not loaded. */
    @Volatile
    internal var coreCheck: (String) -> Boolean = { uniffi.aria_mobile.isEmergencyNumber(it) }

    /** Whether [dial] is an emergency number (911, 112, 999, 000 after normalising). */
    fun isEmergency(dial: String): Boolean = try {
        coreCheck(dial)
    } catch (e: Throwable) {
        // UnsatisfiedLinkError and friends: never let a broken core make an
        // emergency number look ordinary. Logging must not be what fails.
        runCatching { Log.w(TAG, "Core emergency check unavailable: ${e.javaClass.simpleName}") }
        fallbackIsEmergency(dial)
    }

    /** The same normalisation as the core, for when the core is unavailable. */
    internal fun fallbackIsEmergency(dial: String): Boolean = FALLBACK_NUMBERS.contains(normalise(dial))

    /** The digits the native dialer is given: a `tel:` number without scheme, host or params. */
    fun normalise(dial: String): String {
        var s = dial.trim()
        for (prefix in listOf("sips:", "sip:", "tel:")) {
            if (s.startsWith(prefix, ignoreCase = true)) {
                s = s.substring(prefix.length)
                break
            }
        }
        s = s.substringBefore('@').substringBefore(';')
        return s.filterNot { it == ' ' || it == '-' }
    }

    /** `ACTION_DIAL tel:<number>`: opens the phone app with the number filled in, never calls by itself. */
    fun dialIntent(dial: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", normalise(dial), null))

    /**
     * Hands [dial] to the native dialer. Returns a user-facing error when
     * there is no dialer (a tablet without telephony), else null.
     */
    fun handOff(context: Context, dial: String): String? {
        val intent = dialIntent(dial)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            Log.w(TAG, "No dialer for an emergency call: ${e.javaClass.simpleName}")
            Txt.get(R.string.call_err_emergency_no_dialer, "Aria can't place emergency calls. Use your phone's dialer to call %1\$s.", normalise(dial))
        }
    }
}
