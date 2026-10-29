package com.solutions5060.aria.call

import android.telecom.DisconnectCause
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.CallState
import uniffi.aria_mobile.MobileException

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

    /** This user blind-transferred the call to someone else. */
    TRANSFERRED,
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

    /** `setDisconnected(DisconnectCause(cause, ..., reason))` followed by `destroy()`. */
    data class Disconnect(val cause: Int, val reason: String? = null) : TelecomUpdate
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
        CallState.ENDED -> disconnectFor(endReason)
        CallState.IDLE -> TelecomUpdate.None
    }

    /** The full Telecom disconnect for [reason], including the reason string where one applies. */
    fun disconnectFor(reason: EndReason): TelecomUpdate.Disconnect =
        TelecomUpdate.Disconnect(disconnectCauseFor(reason), disconnectReasonFor(reason))

    /**
     * Telecom's free-form reason. Only a transfer needs one: OTHER alone
     * would read as an unexplained failure.
     */
    fun disconnectReasonFor(reason: EndReason): String? =
        if (reason == EndReason.TRANSFERRED) "transferred" else null

    /** The [DisconnectCause] code Telecom expects for each way a call can end. */
    fun disconnectCauseFor(reason: EndReason): Int = when (reason) {
        EndReason.LOCAL_HANGUP -> DisconnectCause.LOCAL
        EndReason.REMOTE_HANGUP -> DisconnectCause.REMOTE
        EndReason.REJECTED -> DisconnectCause.REJECTED
        EndReason.MISSED -> DisconnectCause.MISSED
        EndReason.FAILED -> DisconnectCause.ERROR
        EndReason.TRANSFERRED -> DisconnectCause.OTHER
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

    /**
     * What to tell the user when transferCall throws. Every one of these
     * leaves the call up, which the message says where it is not obvious.
     */
    fun transferErrorMessage(error: Throwable): String = when (error) {
        is MobileException.InvalidTarget -> "That number can't be used as a transfer target."
        is MobileException.InvalidState -> "This call can't be transferred right now."
        is MobileException.CallFailed -> "The phone system refused the transfer. You're still on the call."
        is MobileException.Timeout -> "The phone system didn't respond in time. You're still on the call."
        is MobileException.GatewayException -> "The transfer failed at the gateway. You're still on the call."
        is MobileException.NetworkException -> "Network error. You're still on the call."
        else -> "The transfer failed. You're still on the call."
    }

    /**
     * Cleans a typed or picked number into a dial string: a SIP URI is kept
     * as-is, otherwise only dialable characters survive ("(555) 123-4567"
     * becomes "5551234567"). Empty means there is nothing to transfer to.
     */
    fun normalizeTransferTarget(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("sip:", ignoreCase = true)) return trimmed
        return trimmed.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
    }

    fun formatElapsed(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return "%02d:%02d".format(java.util.Locale.ROOT, safe / 60, safe % 60)
    }
}
