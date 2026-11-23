package com.solutions5060.aria.call

/**
 * Pure rules for the local three-way conference the core mixes
 * (`conference_merge` / `conference_split`; see "Conference" in the core
 * README).
 *
 * ## Why app-level handling and not a Telecom [android.telecom.Conference]
 *
 * Aria's PhoneAccount is self-managed, so the system draws no in-call UI and
 * gains nothing from a Telecom `Conference`: it would only add a third
 * object whose state has to be kept in step with the two connections. The
 * mixing happens in the core, not in Telecom or the modem, and the core
 * already reports the result as plain per-call events (both calls
 * `Connected` with `in_conference` true). So each call keeps its own
 * [android.telecom.Connection], both are set active while conferenced, and
 * a Bluetooth/car hold or hangup on either connection goes to the core,
 * which applies it to the conference as a whole (hold) or ends that party
 * and re-emits the survivor (hangup). The call screen reads
 * [CallUiState.inConference].
 */
object Conference {

    /** The two calls "Merge" joins, oldest first. */
    data class MergePair(val first: CallUiState, val second: CallUiState)

    /**
     * The pair "Merge" acts on, or null when there is none: exactly two
     * connected calls (held or not), both with engine ids, neither already
     * conferenced. The core refuses anything else with InvalidState.
     */
    fun mergePair(calls: List<CallUiState>): MergePair? {
        val connected = calls.filter(CallWaiting::isConnected)
        if (connected.size != 2) return null
        if (connected.any { it.callId == null || it.inConference }) return null
        return MergePair(connected[0], connected[1])
    }

    /** Whether "Merge" is offered. */
    fun canMerge(calls: List<CallUiState>): Boolean = mergePair(calls) != null

    /** Whether "Split" is offered: a conference is up. */
    fun canSplit(calls: List<CallUiState>): Boolean =
        calls.any { CallWaiting.isLive(it) && it.inConference }

    /**
     * Swap is not allowed while conferenced (the core throws InvalidState);
     * Split first.
     */
    fun canSwap(calls: List<CallUiState>): Boolean = !canSplit(calls)
}
