package com.solutions5060.aria.hold

import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallUiState

/** The window after joining a hold-return call in which "Still on hold" is accepted (contract C2). */
const val STILL_ON_HOLD_WINDOW_MS = 10_000L

/** What a hold-return call (the far party is back) shows, from the push alone: no socket or REST needed. */
object HoldReturn {
    /** Who is back: the PBX's name for them, else the call's own display name. */
    private fun who(call: CallUiState) = call.holdReturnName?.takeIf { it.isNotBlank() } ?: call.displayName

    /** "They're back — <name>" ([template] is `ui_hold_theyre_back`), or null for an ordinary call. */
    fun label(template: String, call: CallUiState): String? =
        call.holdReturnId?.let { String.format(java.util.Locale.ROOT, template, who(call)) }

    /** "They're back, <name>" ([template] is `ui_hold_announce_back`), spoken once; null for an ordinary call. */
    fun announcement(template: String, call: CallUiState): String? =
        call.holdReturnId?.let { String.format(java.util.Locale.ROOT, template, who(call)) }

    /** Whole hours of the maximum wait, for "Still waiting after N hours". */
    fun hours(maxWaitSecs: Int): Int = (maxWaitSecs / 3600).coerceAtLeast(1)
}

/** Whether "Still on hold" is offered: while a hold return rings, and for 10 s after joining one. */
fun stillOnHoldVisible(state: CallUiState, nowMillis: Long): Boolean {
    if (state.holdReturnId == null) return false
    return when (state.phase) {
        CallPhase.INCOMING -> true
        CallPhase.ACTIVE, CallPhase.HELD -> state.joinedAtMillis?.let { nowMillis - it < STILL_ON_HOLD_WINDOW_MS } ?: false
        else -> false
    }
}

/** When the button must disappear (epoch ms), for a connected hold return; else null. */
fun stillOnHoldDeadline(state: CallUiState): Long? {
    if (state.holdReturnId == null || state.phase == CallPhase.INCOMING) return null
    return state.joinedAtMillis?.plus(STILL_ON_HOLD_WINDOW_MS)
}
