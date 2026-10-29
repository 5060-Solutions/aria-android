package com.solutions5060.aria.call

import android.telecom.DisconnectCause
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.CallState

/** Where a call is, from the user's point of view. Drives the call screen. */
enum class CallPhase {
    /** Ringing on this device, waiting for the user to answer or decline. */
    INCOMING,

    /** Outgoing, before the far end has started ringing. */
    CONNECTING,

    /** Outgoing, the far end is ringing. */
    RINGING,
    ACTIVE,
    HELD,
    ENDED,
}

/** Why a call ended. Decides the Telecom disconnect cause and the history entry. */
enum class EndReason {
    /** This user hung up (or cancelled an outgoing call). */
    LOCAL_HANGUP,

    /** The other party hung up. */
    REMOTE_HANGUP,

    /** This user declined an incoming call. */
    REJECTED,

    /** An incoming call stopped ringing without being answered. */
    MISSED,

    /** The call could not be set up or dropped on an error. */
    FAILED,
}

/** What the Telecom [android.telecom.Connection] should be told after an engine update. */
sealed interface TelecomUpdate {
    /** Nothing to report. */
    data object None : TelecomUpdate

    /** Outgoing and not yet answered: `setDialing()`. */
    data object Dialing : TelecomUpdate

    /** `setRinging()`. */
    data object Ringing : TelecomUpdate

    /** `setActive()`. */
    data object Active : TelecomUpdate

    /** `setOnHold()`. */
    data object Hold : TelecomUpdate

    /** `setDisconnected(DisconnectCause(cause))` followed by `destroy()`. */
    data class Disconnect(val cause: Int) : TelecomUpdate
}

/**
 * Pure translation between the engine's call model and the two things that
 * mirror it — the Telecom connection and the Compose call screen.
 *
 * Kept free of Android state so the mapping is unit-testable: getting it
 * wrong is how a remote hangup leaves a call stuck "active" in the system UI.
 */
object CallStateMapping {

    /** The call-screen phase for an engine state, or null when the state changes nothing. */
    fun phaseFor(state: CallState, direction: CallDirection): CallPhase? = when (state) {
        CallState.INCOMING -> CallPhase.INCOMING
        CallState.CONNECTING -> if (direction == CallDirection.INBOUND) null else CallPhase.CONNECTING
        CallState.RINGING -> if (direction == CallDirection.INBOUND) CallPhase.INCOMING else CallPhase.RINGING
        CallState.CONNECTED -> CallPhase.ACTIVE
        CallState.ON_HOLD -> CallPhase.HELD
        CallState.ENDED -> CallPhase.ENDED
        CallState.IDLE -> null
    }

    /**
     * The Telecom update for an engine state. [endReason] is only consulted for
     * [CallState.ENDED]: the engine reports an ended call the same way whoever
     * hung up, so the caller has to say which side it was.
     */
    fun telecomUpdateFor(
        state: CallState,
        direction: CallDirection,
        endReason: EndReason = EndReason.REMOTE_HANGUP,
    ): TelecomUpdate = when (state) {
        CallState.CONNECTING, CallState.RINGING ->
            if (direction == CallDirection.OUTBOUND) TelecomUpdate.Dialing else TelecomUpdate.Ringing
        CallState.INCOMING -> TelecomUpdate.Ringing
        CallState.CONNECTED -> TelecomUpdate.Active
        CallState.ON_HOLD -> TelecomUpdate.Hold
        CallState.ENDED -> TelecomUpdate.Disconnect(disconnectCauseFor(endReason))
        CallState.IDLE -> TelecomUpdate.None
    }

    /** The [DisconnectCause] code Telecom expects for each way a call can end. */
    fun disconnectCauseFor(reason: EndReason): Int = when (reason) {
        EndReason.LOCAL_HANGUP -> DisconnectCause.LOCAL
        EndReason.REMOTE_HANGUP -> DisconnectCause.REMOTE
        EndReason.REJECTED -> DisconnectCause.REJECTED
        EndReason.MISSED -> DisconnectCause.MISSED
        EndReason.FAILED -> DisconnectCause.ERROR
    }

    /** Why an engine-reported end happened, given whether this side asked to hang up. */
    fun endReasonForEngineEnd(localHangupRequested: Boolean, wasAnswered: Boolean, direction: CallDirection): EndReason =
        when {
            localHangupRequested -> EndReason.LOCAL_HANGUP
            !wasAnswered && direction == CallDirection.INBOUND -> EndReason.MISSED
            else -> EndReason.REMOTE_HANGUP
        }

    /** The status line under the caller's name. */
    fun statusText(phase: CallPhase, elapsedSeconds: Long): String = when (phase) {
        CallPhase.INCOMING -> "Incoming Call"
        CallPhase.CONNECTING -> "Calling..."
        CallPhase.RINGING -> "Ringing..."
        CallPhase.ACTIVE -> formatElapsed(elapsedSeconds)
        CallPhase.HELD -> "On Hold"
        CallPhase.ENDED -> "Call Ended"
    }

    fun formatElapsed(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(java.util.Locale.ROOT, safe / 60, safe % 60)
    }
}
