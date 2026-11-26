package com.solutions5060.aria.hold

import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallUiState

/** The window after joining a hold-return call in which "Still on hold" is accepted (contract C2). */
const val STILL_ON_HOLD_WINDOW_MS = 10_000L

/** What a hold-return call (the far party is back) shows, from the push alone: no socket or REST needed. */
object HoldReturn {
    /** Who is back: the PBX's name for them, else the call's own display name. */
    private fun who(call: CallUiState) = call.holdReturnName?.takeIf { it.isNotBlank() } ?: call.displayName

    /**
     * Whether this ring is "They're back": a person answered (`human`), or the
     * wait is not known yet (the push came first) or has no ring reason yet.
     * The Rejoin now (`rejoin`) and final (`max_wait`) rings carry the same
     * header but nobody is back, so they are not.
     */
    fun isBack(ringReason: String?): Boolean = ringReason == null || ringReason == "human"

    /**
     * The headline of a hold-return ring: "They're back — <name>" ([template]
     * is `ui_hold_theyre_back`), or just the name for a `rejoin` or `max_wait`
     * ring ([ringReason], from the wait once known). Null for an ordinary call.
     */
    fun label(template: String, call: CallUiState, ringReason: String? = null): String? =
        call.holdReturnId?.let { if (isBack(ringReason)) String.format(java.util.Locale.ROOT, template, who(call)) else who(call) }

    /**
     * What is spoken once: "They're back, <name>" ([template] is
     * `ui_hold_announce_back`), or just the name for a `rejoin` or `max_wait`
     * ring. Null for an ordinary call.
     */
    fun announcement(template: String, call: CallUiState, ringReason: String? = null): String? =
        call.holdReturnId?.let { if (isBack(ringReason)) String.format(java.util.Locale.ROOT, template, who(call)) else who(call) }

    /** The status line under a ringing hold return. */
    sealed interface Status {
        /** "Tap to rejoin". */
        data object TapToRejoin : Status

        /** "Still waiting after N hours. Rejoin or hang up?" */
        data class StillWaiting(val hours: Int) : Status

        /** The ordinary incoming-call status. */
        data object Incoming : Status
    }

    /** The status for a hold return ringing for [ringReason]; [maxWaitSecs] is the wait's, when known. */
    fun status(ringReason: String?, maxWaitSecs: Int?): Status = when (ringReason) {
        "max_wait" -> Status.StillWaiting(hours(maxWaitSecs ?: 10_800))
        "rejoin" -> Status.Incoming
        else -> Status.TapToRejoin
    }

    /** Whole hours of the maximum wait, for "Still waiting after N hours". */
    fun hours(maxWaitSecs: Int): Int = (maxWaitSecs / 3600).coerceAtLeast(1)
}

/** When a hold-return push, not the foreground app, runs the socket driver, and when it lets go. */
object PushListener {
    /** How long a push-started driver may run if the call never reports its end. */
    const val MAX_MS = 5 * 60_000L

    /** How long after the hold-return call ends to read the open waits, so the PBX's state has settled. */
    const val SETTLE_MS = 5_000L

    /** Start it for the push only when nothing is running (not foreground, not an earlier push). */
    fun shouldStart(alreadyRunning: Boolean): Boolean = !alreadyRunning

    /** Stop it only if this push started it and the app has not come to the foreground (which owns it then). */
    fun shouldStop(startedByPush: Boolean, foreground: Boolean): Boolean = startedByPush && !foreground
}

/**
 * Whether "Still on hold" is offered: while a hold return rings, and for 10 s
 * after joining one. Never for the Rejoin now or final rings ([ringReason]
 * `rejoin` or `max_wait`): the user asked to come back, or the wait is over.
 */
fun stillOnHoldVisible(state: CallUiState, nowMillis: Long, ringReason: String? = null): Boolean {
    if (state.holdReturnId == null) return false
    if (ringReason == "rejoin" || ringReason == "max_wait") return false
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
