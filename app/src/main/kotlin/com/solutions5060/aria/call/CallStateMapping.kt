package com.solutions5060.aria.call

import android.telecom.DisconnectCause
import com.solutions5060.aria.R
import com.solutions5060.aria.Txt
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.CallState
import uniffi.aria_mobile.MobileException
import uniffi.aria_mobile.EndReason as CoreEndReason

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

    /** An incoming call was answered on another of this user's devices. */
    ANSWERED_ELSEWHERE,
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
        EndReason.ANSWERED_ELSEWHERE -> DisconnectCause.ANSWERED_ELSEWHERE
    }

    /**
     * The app's end reason for the core's `end_reason` on an Ended event.
     * A local decline (`Rejected`) and a cancelled dial (`Cancelled`) are
     * normally finished by the app before the event arrives; the mapping
     * covers them for the rare case the event wins.
     */
    fun endReasonFor(reason: CoreEndReason): EndReason = when (reason) {
        CoreEndReason.LOCAL_HANGUP, CoreEndReason.CANCELLED -> EndReason.LOCAL_HANGUP
        CoreEndReason.REMOTE_HANGUP -> EndReason.REMOTE_HANGUP
        CoreEndReason.CALLER_CANCELLED -> EndReason.MISSED
        CoreEndReason.ANSWERED_ELSEWHERE -> EndReason.ANSWERED_ELSEWHERE
        CoreEndReason.REJECTED -> EndReason.REJECTED
        CoreEndReason.TRANSFERRED -> EndReason.TRANSFERRED
        CoreEndReason.FAILED -> EndReason.FAILED
    }

    /**
     * A connected call the core ended as `Failed` without this side hanging
     * up: the gateway instance carrying it died and the call could not be
     * moved. Reported once by the core; shown as "Call dropped".
     */
    fun isConnectionLoss(coreReason: CoreEndReason?, wasAnswered: Boolean, localHangupRequested: Boolean): Boolean =
        coreReason == CoreEndReason.FAILED && wasAnswered && !localHangupRequested

    /**
     * Why an engine-reported end happened: the core's [coreReason] when it
     * gave one, else inferred from whether this side asked to hang up.
     */
    fun endReasonForEngineEnd(
        localHangupRequested: Boolean,
        wasAnswered: Boolean,
        direction: CallDirection,
        coreReason: CoreEndReason? = null,
    ): EndReason =
        when {
            localHangupRequested -> EndReason.LOCAL_HANGUP
            coreReason != null -> endReasonFor(coreReason)
            !wasAnswered && direction == CallDirection.INBOUND -> EndReason.MISSED
            else -> EndReason.REMOTE_HANGUP
        }

    /**
     * Why a failed `makeCallWithId` ended the call. `Cancelled` means this user hung
     * up while it rang, which is a hangup, not a failure.
     */
    fun endReasonForDialFailure(error: Throwable): EndReason =
        if (error is MobileException.Cancelled) EndReason.LOCAL_HANGUP else EndReason.FAILED

    /** What the dialer shows when `makeCallWithId` throws, or null when nothing went wrong. */
    fun dialFailureMessage(error: Throwable): String? = when (error) {
        is MobileException.Cancelled -> null
        // The token is renewed in the background; the call is not redialled
        // on the user's behalf (the callee could ring twice).
        is MobileException.AuthenticationException ->
            Txt.get(R.string.call_dial_auth_expired, "The call couldn't be placed because the connection to the call service had expired. It is being renewed; please call again.")
        else -> Txt.get(R.string.call_dial_failed, "Call failed: %1\$s", error.message ?: error.javaClass.simpleName)
    }

    /** Longest call token accepted from an intent. */
    private const val MAX_CALL_TOKEN_LEN = 8192

    /**
     * A call token is a compact, opaque, gateway-issued secret. Only non-blank
     * printable ASCII of a sane length is accepted, so a malformed extra cannot
     * crash or drive the answer flow.
     */
    fun isWellFormedCallToken(token: String): Boolean =
        token.isNotBlank() && token.length <= MAX_CALL_TOKEN_LEN && token.all { it.code in 0x21..0x7e }

    /** The status line under the caller's name. */
    fun statusText(phase: CallPhase, elapsedSeconds: Long): String = when (phase) {
        CallPhase.INCOMING -> Txt.get(R.string.call_phase_incoming, "Incoming Call")
        CallPhase.CONNECTING -> Txt.get(R.string.call_phase_calling, "Calling...")
        CallPhase.RINGING -> Txt.get(R.string.call_phase_ringing, "Ringing...")
        CallPhase.ACTIVE -> formatElapsed(elapsedSeconds)
        CallPhase.HELD -> Txt.get(R.string.call_phase_on_hold, "On Hold")
        CallPhase.ENDED -> Txt.get(R.string.call_phase_ended, "Call Ended")
    }

    /**
     * What to tell the user when transferCall throws. Every one of these
     * leaves the call up, which the message says where it is not obvious.
     */
    fun transferErrorMessage(error: Throwable): String = when (error) {
        is MobileException.InvalidTarget -> Txt.get(R.string.transfer_err_invalid_target, "That number can't be used as a transfer target.")
        is MobileException.InvalidState -> Txt.get(R.string.transfer_err_invalid_state, "This call can't be transferred right now.")
        is MobileException.TransferUnsupported ->
            Txt.get(R.string.transfer_err_unsupported, "The phone system can't transfer this call. You're still on the call.")
        is MobileException.CallFailed -> sipStatusIn(error.message)?.let { code ->
            Txt.get(R.string.transfer_err_sip, "The transfer failed: %1\$s (SIP %2\$d). You're still on the call.", sipStatusText(code), code)
        } ?: Txt.get(R.string.transfer_err_refused, "The phone system refused the transfer. You're still on the call.")
        // Covers a 408 too: no one answered, or no outcome was reported.
        is MobileException.Timeout ->
            Txt.get(R.string.transfer_err_timeout, "The transfer didn't go through: no one answered, or the phone system didn't say whether it worked. You're still on the call.")
        is MobileException.GatewayException -> Txt.get(R.string.transfer_err_gateway, "The transfer failed at the gateway. You're still on the call.")
        is MobileException.NetworkException -> Txt.get(R.string.transfer_err_network, "Network error. You're still on the call.")
        else -> Txt.get(R.string.transfer_err_generic, "The transfer failed. You're still on the call.")
    }

    /** The SIP status a core error message carries ("... (SIP 486) ..."), if any. */
    fun sipStatusIn(message: String?): Int? =
        message?.let { Regex("""\bSIP (\d{3})\b""").find(it) }?.groupValues?.get(1)?.toIntOrNull()

    /** Plain words for the SIP statuses a failed transfer usually reports. */
    fun sipStatusText(code: Int): String = when (code) {
        486, 600 -> "the line is busy"
        480, 408 -> "no one answered"
        404, 484, 604 -> "that number wasn't found"
        403, 603 -> "the call was declined"
        487 -> "the transfer was cancelled"
        else -> "the phone system reported an error"
    }

    /**
     * After a failed answer, whether this app must still decline the call at
     * the gateway. On `Timeout` or `NetworkError` the gateway may have taken
     * the answer, so the core resolves it (hangs up or rejects) and reports
     * `Ended`. Any other failure was a refusal that left the call offered,
     * with the caller still hearing it ring.
     */
    fun acceptFailureNeedsReject(error: Throwable): Boolean =
        error !is MobileException.Timeout && error !is MobileException.NetworkException

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
