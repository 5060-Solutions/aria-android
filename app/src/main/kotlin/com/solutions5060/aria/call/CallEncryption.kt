package com.solutions5060.aria.call

import uniffi.aria_mobile.CallState

/**
 * Whether a call's media is encrypted, as the call screen, its one-time
 * notice and the connection sheet show it.
 *
 * Aria calls are secure by default. A call whose media is not encrypted is
 * acceptable only when the user is told, so [UNENCRYPTED] always shows: an
 * amber open lock on the call screen for the whole call, a notice once per
 * call, and "None (unencrypted)" in the connection sheet.
 */
enum class CallEncryption {
    /** Not connected yet, or the core has not said: nothing is shown. */
    UNKNOWN,
    ENCRYPTED,
    UNENCRYPTED,
    ;

    companion object {
        /**
         * [encrypted] is the core's answer for the call ([CallUiState.encrypted]),
         * null when it has none. Before a call connects nothing has been
         * negotiated, so it is unknown whatever the core says.
         */
        fun of(phase: CallPhase, encrypted: Boolean?): CallEncryption = when {
            phase != CallPhase.ACTIVE && phase != CallPhase.HELD -> UNKNOWN
            encrypted == null -> UNKNOWN
            encrypted -> ENCRYPTED
            else -> UNENCRYPTED
        }

        fun of(call: CallUiState): CallEncryption = of(call.phase, call.encrypted)

        /**
         * What a call-state event says about encryption: `CallInfo.encrypted`
         * once the call is connected. The core sets it on Connected, carries
         * it on every later event, and re-sends the call with its state
         * unchanged when it changes (a media re-offer after a network change
         * that drops the crypto). Null for other states: before Connected the
         * core says false because nothing has been negotiated, which is not
         * "unencrypted".
         */
        fun reported(state: CallState, encrypted: Boolean): Boolean? = when (state) {
            CallState.CONNECTED, CallState.ON_HOLD -> encrypted
            else -> null
        }
    }
}

/**
 * Which calls have had the "not encrypted" notice, so each call gets it once:
 * when it connects unencrypted, or the first time it drops to unencrypted
 * later. Kept outside the call screen so leaving and reopening it does not
 * repeat it. Main thread only.
 */
class UnencryptedCallNotices {
    private val notified = mutableSetOf<String>()

    /** Whether to show the notice for the call [key] now that it is [encryption]. True at most once per call. */
    fun shouldNotify(key: String, encryption: CallEncryption): Boolean =
        encryption == CallEncryption.UNENCRYPTED && notified.add(key)

    /** Forget calls that have ended. */
    fun retainOnly(live: Set<String>) {
        notified.retainAll(live)
    }

    companion object {
        val shared = UnencryptedCallNotices()
    }
}
