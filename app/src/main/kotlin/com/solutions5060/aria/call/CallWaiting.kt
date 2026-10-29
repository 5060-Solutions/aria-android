package com.solutions5060.aria.call

import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.MobileException

/**
 * Pure rules for having more than one call: which calls may start, which one
 * the screen shows, and which pair can be joined in an attended transfer.
 *
 * The core allows at most two live calls and keeps at most one of them off
 * hold; these rules keep the app inside those limits so a request the core
 * would refuse is never made. Free of Android state so it is unit-testable.
 */
object CallWaiting {

    /** The core's limit on live calls (connected, held, or being set up). */
    const val MAX_CALLS = 2

    /** Whether a call still counts: everything but one that has ended and is only lingering on screen. */
    fun isLive(call: CallUiState): Boolean = call.phase != CallPhase.ENDED

    /** Answered (connected at some point) and not ended: the calls hold, swap and transfer act on. */
    fun isConnected(call: CallUiState): Boolean =
        call.connectedAtElapsed != null && (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.HELD)

    /**
     * Whether a newly pushed call may ring alongside [calls], or must be
     * declined as busy. It rings when there is room for it under the core's
     * limit and every other call is answered. A call still ringing here
     * (one incoming call at a time keeps answering unambiguous), or one still
     * dialling, ringing out or being answered, makes it busy: answering next
     * to an unanswered call would let the core hold the call the user is
     * talking on the moment that other call connects.
     */
    fun shouldRejectIncomingAsBusy(calls: List<CallUiState>): Boolean {
        val live = calls.filter(::isLive)
        if (live.size >= MAX_CALLS) return true
        return live.any { !isConnected(it) }
    }

    /**
     * Why a new outgoing call cannot be placed alongside [calls], or null when
     * it can. A second call is placed only from an answered call, so the
     * first is never left half set up.
     */
    fun outgoingRefusal(calls: List<CallUiState>): String? {
        val live = calls.filter(::isLive)
        return when {
            live.isEmpty() -> null
            live.size >= MAX_CALLS -> "You're already on two calls. End one to place another."
            live.any { it.phase == CallPhase.INCOMING } -> "Answer or decline the incoming call first."
            live.any { !isConnected(it) } -> "Wait for the current call to connect first."
            else -> null
        }
    }

    /**
     * The call the screen shows: a ringing incoming call first, then one being
     * set up, then the call that is off hold, then a held one, and last a call
     * that just ended. Within each, the newest. [calls] is oldest first.
     */
    fun foreground(calls: List<CallUiState>): CallUiState? {
        val newestFirst = calls.asReversed()
        return newestFirst.firstOrNull { it.phase == CallPhase.INCOMING }
            ?: newestFirst.firstOrNull { it.phase == CallPhase.CONNECTING || it.phase == CallPhase.RINGING }
            ?: newestFirst.firstOrNull { it.phase == CallPhase.ACTIVE }
            ?: newestFirst.firstOrNull { it.phase == CallPhase.HELD }
            ?: newestFirst.firstOrNull()
    }

    /** The live calls other than [foreground], oldest first: the rows under the main call. */
    fun others(calls: List<CallUiState>, foreground: CallUiState?): List<CallUiState> =
        calls.filter { isLive(it) && it.key != foreground?.key }

    /** The record whose engine call id is [callId], skipping finished ones; null for some other call. */
    fun <T> recordFor(callId: String, records: Collection<T>, idOf: (T) -> String?, finished: (T) -> Boolean): T? =
        records.firstOrNull { !finished(it) && idOf(it) == callId }

    /** The two calls an attended transfer joins: the transferred call and the consultation call. */
    data class TransferPair(val transferred: CallUiState, val consult: CallUiState)

    /**
     * The pair "Complete transfer" acts on, or null when there is none. The
     * PBX only joins a call this phone answered (transferred) with one it
     * placed (consultation), so it needs exactly two connected calls, one
     * each way.
     */
    fun attendedTransferPair(calls: List<CallUiState>): TransferPair? {
        val connected = calls.filter(::isConnected)
        if (connected.size != 2) return null
        val inbound = connected.singleOrNull { it.direction == CallDirection.INBOUND } ?: return null
        val outbound = connected.singleOrNull { it.direction == CallDirection.OUTBOUND } ?: return null
        if (inbound.callId == null || outbound.callId == null) return null
        return TransferPair(transferred = inbound, consult = outbound)
    }

    /**
     * Whether [call] can start a consultation: an answered incoming call with
     * no other call yet, so the call placed next completes the pair.
     */
    fun canConsult(call: CallUiState, calls: List<CallUiState>): Boolean =
        call.direction == CallDirection.INBOUND && isConnected(call) && calls.count(::isLive) == 1

    /**
     * True when every call left is still ringing here: nothing is connected
     * or being dialled, so the call audio and the in-call service can be
     * released (answering starts them again), and the ringing call should
     * ring as a normal incoming call rather than as call waiting.
     */
    fun onlyRingingLeft(calls: List<CallUiState>): Boolean {
        val live = calls.filter(::isLive)
        return live.isNotEmpty() && live.all { it.phase == CallPhase.INCOMING }
    }

    /**
     * Whether a local hangup must also be sent to the engine. An outgoing
     * call always (the core cancels it at any point). An incoming call only
     * once the engine holds it, i.e. [accepted] (accept returned, or the call
     * connected); while the accept is still running, its result hangs the
     * call up, and before it starts nothing was accepted.
     */
    fun hangupReachesEngine(direction: CallDirection, hasCallId: Boolean, accepted: Boolean): Boolean =
        hasCallId && (direction == CallDirection.OUTBOUND || accepted)

    /** Whether "Add call" is offered: [call] is answered and there is room for another call. */
    fun canAddCall(call: CallUiState, calls: List<CallUiState>): Boolean =
        isConnected(call) && outgoingRefusal(calls) == null

    /**
     * What to do to bring [target] off hold while [active] (if any) is the
     * call off hold: a swap when both are connected, else a plain resume.
     */
    fun resumeAction(target: CallUiState, active: CallUiState?): ResumeAction? {
        val heldId = target.callId ?: return null
        if (!isConnected(target) || target.phase != CallPhase.HELD) return null
        val activeId = active?.callId
        return if (activeId != null && active.key != target.key && active.phase == CallPhase.ACTIVE) {
            ResumeAction.Swap(activeCallId = activeId, heldCallId = heldId)
        } else {
            ResumeAction.Resume(heldId)
        }
    }

    sealed interface ResumeAction {
        /** `swapCalls(activeCallId, heldCallId)`. */
        data class Swap(val activeCallId: String, val heldCallId: String) : ResumeAction

        /** `setHold(callId, false)`; the core holds any other connected call first. */
        data class Resume(val callId: String) : ResumeAction
    }

    /**
     * What to tell the user when `transferAttended` throws. Every failure
     * leaves both calls up.
     */
    fun attendedTransferErrorMessage(error: Throwable, partnerEnded: Boolean = false): String = when {
        // The core throws InvalidState when one leg ended mid-transfer; that
        // call is gone, so "both calls are still up" would be wrong.
        partnerEnded -> "The other call ended, so the transfer was not completed."
        else -> attendedTransferFailure(error)
    }

    private fun attendedTransferFailure(error: Throwable): String = when (error) {
        is MobileException.TransferUnsupported ->
            "These calls can't be joined: the phone system can only transfer a call you answered to a call you placed."
        is MobileException.InvalidState -> "The transfer can't be completed right now. Both calls are still up."
        is MobileException.GatewayException -> "A transfer is already in progress. Both calls are still up."
        is MobileException.CallFailed -> CallStateMapping.sipStatusIn(error.message)?.let { code ->
            "The transfer failed: ${CallStateMapping.sipStatusText(code)} (SIP $code). Both calls are still up."
        } ?: "The phone system refused the transfer. Both calls are still up."
        is MobileException.Timeout ->
            "The phone system didn't say whether the transfer worked. Both calls are still up."
        is MobileException.NetworkException -> "Network error. Both calls are still up."
        else -> "The transfer failed. Both calls are still up."
    }
}
