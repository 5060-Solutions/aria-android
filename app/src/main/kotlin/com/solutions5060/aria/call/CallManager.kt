package com.solutions5060.aria.call

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import com.solutions5060.aria.R
import com.solutions5060.aria.Txt
import com.solutions5060.aria.ai.CallTranscription
import com.solutions5060.aria.service.AriaConnection
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.IncomingCallService
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.history.CallHistoryRecorder
import com.solutions5060.aria.history.CallHistoryStore
import com.solutions5060.aria.recording.RecordingConsent
import com.solutions5060.aria.recording.RecordingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.CallInfo
import uniffi.aria_mobile.CallOffer
import uniffi.aria_mobile.CallState
import uniffi.aria_mobile.MediaStats
import uniffi.aria_mobile.MobileEventHandler
import uniffi.aria_mobile.MobileException
import uniffi.aria_mobile.newOutgoingCallId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/** A one-off message about a call, shown as a snackbar. */
enum class CallNotice {
    /** The connection to the gateway was lost and the call could not be kept. */
    DROPPED,
}

/** What the call screen renders. Immutable; a new value is published on every change. */
data class CallUiState(
    val key: String,
    val callId: String?,
    val remoteUri: String,
    val remoteName: String?,
    val direction: CallDirection,
    val phase: CallPhase,
    val muted: Boolean = false,
    val onHold: Boolean = false,
    /** [SystemClock.elapsedRealtime] when the call connected; null until then. */
    val connectedAtElapsed: Long? = null,
    /** Audio outputs Telecom offers, and the one in use. */
    val audio: AudioRouteState = AudioRouteState(),
    /**
     * Whether media is SRTP-encrypted as negotiated (`CallInfo.encrypted`),
     * null until the core has reported it on Connected. See [CallEncryption].
     */
    val encrypted: Boolean? = null,
    /** A blind transfer is waiting for its outcome (up to about 45 s). */
    val transferring: Boolean = false,
    /** Why the last transfer attempt failed; the call is still up. */
    val transferError: String? = null,
    /** Mixed into a local three-way conference with the other call ([CallManager.mergeCalls]). */
    val inConference: Boolean = false,
    /** Being recorded to a WAV file ([CallManager.startRecording]). */
    val recording: Boolean = false,
    /** The name of the matching phone contact, if contacts access was granted. */
    val contactName: String? = null,
    /** The negotiated codec by its SDP name ("PCMU", "PCMA", "opus", "G729"); null before media. */
    val codec: String? = null,
    /** The device a call that is being moved here comes from, when it rings for the user to decide. */
    val movingFrom: String? = null,
    /** The device this call was moved to; the call ended here because of it. */
    val movedTo: String? = null,
    /** When the call began on the device it moved from, in epoch milliseconds. */
    val originalStartMillis: Long? = null,
    /** The PBX ended this call into a Hold for me wait: show "Holding for you", not a normal end. */
    val heldForYou: Boolean = false,
    /** Hold for me: the wait this ring is for (the far party is back), else null. */
    val holdReturnId: String? = null,
    /** Hold for me: who is back, as the PBX named them. */
    val holdReturnName: String? = null,
    /** When this device joined a hold-return call, in epoch milliseconds; null until then. */
    val joinedAtMillis: Long? = null,
) {
    /** The contact's name, else the name the network sent, else the user part of the URI. */
    val displayName: String
        get() = contactName?.takeIf { it.isNotBlank() }
            ?: remoteName?.takeIf { it.isNotBlank() }
            ?: remoteUri.removePrefix("sip:").substringBefore("@")
}

/**
 * Owns the lifecycle of every call (at most [CallWaiting.MAX_CALLS], the
 * core's limit) and keeps three views of each in step: the engine, its
 * Telecom [Connection], and the Compose UI.
 *
 * Calls are keyed by a record key chosen here. Engine events carry the
 * engine's call id and are routed to the record with that id; Telecom
 * callbacks carry the connection and are routed to the record that owns it.
 * The core keeps at most one connected call off hold and reports each hold
 * change as an event, which is mirrored onto that call's connection.
 *
 * Telecom is the only path in and out. Outgoing calls go through
 * [TelecomManager.placeCall], so `onCreateOutgoingConnection` runs and the
 * connection starts the SIP call; incoming pushes go through
 * `addNewIncomingCall`, and the connection's `onShowIncomingCallUi` posts the
 * one incoming-call notification. Engine callbacks
 * ([MobileEventHandler.onCallStateChanged]) are the only source of call state
 * after that — nothing polls.
 *
 * All state lives on the main thread. Engine callbacks arrive on arbitrary
 * threads and are posted there; blocking engine calls run on [engineExecutor].
 */
object CallManager {

    private const val TAG = "CallManager"

    /** Extra carrying our record key through Telecom's outgoing request. */
    const val EXTRA_RECORD_KEY = "com.solutions5060.aria.RECORD_KEY"
    const val EXTRA_CALL_TOKEN = "call_token"
    const val EXTRA_CALLER_URI = "caller_uri"
    const val EXTRA_CALLER_NAME = "caller_name"
    const val EXTRA_HOLD_RETURN_ID = "hold_return_id"
    const val EXTRA_HOLD_RETURN_NAME = "hold_return_name"

    /** How long "Call Ended" stays on screen before the call screen closes. */
    private const val ENDED_LINGER_MS = 1_000L

    /**
     * Ring for at most this long. The engine reports a caller who gives up,
     * so this is only a backstop for a push whose call the gateway lost. The
     * gateway answers an unanswered push with 480 after 30 s, so ringing much
     * past that only rings for a call that no longer exists.
     */
    private const val RING_TIMEOUT_MS = 35_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineExecutor = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var appContext: Context

    private val _state = MutableStateFlow<CallUiState?>(null)

    /** The call on screen (see [CallWaiting.foreground]), or null when there is none. */
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

    private val _calls = MutableStateFlow<List<CallUiState>>(emptyList())

    /** Every call, oldest first, including one that just ended and is still on screen. */
    val calls: StateFlow<List<CallUiState>> = _calls.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** The last call-setup failure, for the dialer to show. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<CallNotice?>(null)

    /** Something to tell the user after a call, as a snackbar; cleared with [clearNotice]. */
    val notice: StateFlow<CallNotice?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    private val _actionError = MutableStateFlow<String?>(null)

    /**
     * Why the last in-call action (merge, split, start or stop recording)
     * failed, for the call screen to show; the calls are unchanged. Cleared
     * with [clearActionError].
     */
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /** Mutable bookkeeping for one call. Main-thread only. */
    private class CallRecord(
        val key: String,
        val direction: CallDirection,
        var remoteUri: String,
        var remoteName: String?,
        /** The SIP URI dialled (outgoing only). */
        val dialUri: String? = null,
        /** The gateway call token (incoming only). */
        val callToken: String? = null,
    ) {
        var callId: String? = null
        var connection: AriaConnection? = null
        var phase: CallPhase = if (direction == CallDirection.INBOUND) CallPhase.INCOMING else CallPhase.CONNECTING
        var muted = false
        var onHold = false
        val startedAtMillis: Long = System.currentTimeMillis()
        var connectedAtElapsed: Long? = null
        var localHangupRequested = false
        var finished = false
        var audio = AudioRouteState()
        var encrypted: Boolean? = null
        var transferring = false
        var transferError: String? = null
        /** Ended arrived while a transfer was in flight; its result decides why. */
        var endedDuringTransfer = false
        /** makeCallWithId is still running; an Ended now may mean it is about to throw. */
        var dialInFlight = false
        var endedDuringDial = false
        /** acceptIncomingCall is still running; an Ended now may mean it is about to throw. */
        var answerInFlight = false
        /** acceptIncomingCall returned: the engine holds this call, so a hangup must reach it. */
        var accepted = false
        var endedDuringAnswer = false
        /** The core's reason on an Ended held back for a method's result. */
        var deferredEndReason: EndReason? = null
        /** Ends the call as missed if it rings too long; null once it stops ringing. */
        var ringTimeout: Runnable? = null
        var inConference = false
        var recording = false
        var contactName: String? = null
        var codec: String? = null
        var movingFrom: String? = null
        var movedTo: String? = null
        var originalStartMillis: Long? = null
        var holdReturnId: String? = null
        var holdReturnName: String? = null
        var joinedAtMillis: Long? = null
        /** The incoming-call notification has been posted for this record. */
        var incomingShown = false
        var heldForYou = false
        /** A move was asked for here: stay silent until the offer says whether this INVITE is it. */
        var awaitingMoveOffer = false
        var moveAutoAnswer = false
    }

    /** Every call by record key, oldest first. Main-thread only. */
    private val records = LinkedHashMap<String, CallRecord>()

    private fun liveRecords(): List<CallRecord> = records.values.filter { !it.finished }

    private fun uiStates(): List<CallUiState> = records.values.map { it.toUiState() }

    /** The record behind the call on screen. */
    private fun foregroundRecord(): CallRecord? = CallWaiting.foreground(uiStates())?.let { records[it.key] }

    /** The call ringing here, if any; [callToken], when given, must match it. */
    private fun ringingRecord(callToken: String? = null): CallRecord? =
        liveRecords().firstOrNull {
            it.phase == CallPhase.INCOMING && (callToken == null || callToken == it.callToken)
        }

    private fun recordFor(connection: AriaConnection): CallRecord? =
        records.values.firstOrNull { it.connection === connection && !it.finished }

    private fun armRingTimeout(rec: CallRecord) {
        cancelRingTimeout(rec)
        val timeout = Runnable {
            if (rec.phase != CallPhase.INCOMING || rec.finished) return@Runnable
            finish(rec, EndReason.MISSED)
            // The gateway has given up by now; tell it anyway, so a call it
            // still holds is not left waiting on this device.
            rec.callToken?.takeIf { it.isNotEmpty() }?.let { rejectToken(it) }
        }
        rec.ringTimeout = timeout
        mainHandler.postDelayed(timeout, RING_TIMEOUT_MS)
    }

    private fun cancelRingTimeout(rec: CallRecord) {
        rec.ringTimeout?.let { mainHandler.removeCallbacks(it) }
        rec.ringTimeout = null
    }

    /** Takes down the incoming-call notification once no call is ringing here. */
    private fun syncIncomingNotifier() {
        if (ringingRecord() == null) IncomingCallNotifier.cancel(appContext)
    }

    /** Pushes already handled; a repeat for the same call must not ring, reject or log it again. */
    private val handledCallTokens = RecentCallTokens()

    fun init(context: Context) {
        appContext = context.applicationContext
        com.solutions5060.aria.move.CallMoveCoordinator.init(appContext)
        com.solutions5060.aria.hold.HoldForMeCoordinator.init(appContext)
    }

    /** `X-Aria-Move` per offered call token (null: the offer carried none). */
    private val offerMoveIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** `X-Aria-Hold-Return` and its name per offered call token (absent: the offer carried none). */
    private val offerHoldReturns = java.util.concurrent.ConcurrentHashMap<String, com.solutions5060.aria.hold.HoldReturnPush>()

    /** How long an INVITE waits, silent, for its offer when a move is pending. */
    private const val MOVE_OFFER_WAIT_MS = 4_000L

    /** Records that the call [callId] (the PBX's id) was moved to [label], for "Moved to …". */
    fun markMoved(callId: String, label: String) {
        // The PBX's call id is not the engine's; the leg that is up here is the one.
        mainHandler.post { liveRecords().firstOrNull { it.phase == CallPhase.ACTIVE || it.phase == CallPhase.HELD }?.let { it.movedTo = label; publishAll() } }
    }

    /** The user asked to move the call with [remoteNumber] to [label]; its end here will say so. */
    fun markMovedForNumber(remoteNumber: String?, label: String) {
        mainHandler.post {
            val rec = liveRecords().firstOrNull {
                remoteNumber == null || com.solutions5060.aria.move.CallMovePolicy.sameNumber(it.remoteUri, remoteNumber)
            } ?: return@post
            rec.movedTo = label
            publishAll()
        }
    }

    /**
     * Decide an incoming call once its offer is known: ring, ring labelled
     * "Moving call from …", or (for the move this device asked for) answer
     * with no ring, with Telecom told the call is active straight away.
     */
    private fun applyMoveDecision(rec: CallRecord) {
        if (rec.finished || rec.phase != CallPhase.INCOMING) return
        val token = rec.callToken.orEmpty()
        val moveId = offerMoveIds[token]
        val pending = com.solutions5060.aria.move.CallMoveCoordinator.pendingMove()
        when (val d = com.solutions5060.aria.move.CallMovePolicy.decide(moveId, pending, System.currentTimeMillis())) {
            is com.solutions5060.aria.move.MoveDecision.AutoAnswer -> {
                rec.awaitingMoveOffer = false
                rec.moveAutoAnswer = true
                rec.originalStartMillis = d.callStartedAtMillis
                com.solutions5060.aria.move.CallMoveCoordinator.claim(moveId.orEmpty())
                cancelRingTimeout(rec)
                rec.connection?.let { if (it.state != Connection.STATE_ACTIVE) it.setActive() }
                answer(rec)
            }
            com.solutions5060.aria.move.MoveDecision.RingMoving -> {
                rec.movingFrom = com.solutions5060.aria.move.CallMoveCoordinator.movingFromLabel(rec.remoteUri)
                    ?: Txt.get(R.string.move_unknown_device, "another device")
                ringAfterMoveCheck(rec)
            }
            com.solutions5060.aria.move.MoveDecision.Ring -> ringAfterMoveCheck(rec)
        }
    }

    private fun ringAfterMoveCheck(rec: CallRecord) {
        if (!rec.awaitingMoveOffer) {
            publishAll()
            return
        }
        rec.awaitingMoveOffer = false
        rec.connection?.setRinging()
        publishAll()
        showIncoming(rec)
    }

    fun clearError() {
        _error.value = null
    }

    fun clearActionError() {
        _actionError.value = null
    }

    // ── Engine events ───────────────────────────────────────────────────

    /** Installed on every engine by [EngineManager]. */
    val eventHandler: MobileEventHandler = object : MobileEventHandler {
        override fun onRegistrationChanged(deviceId: String, status: String, error: String?) {
            // A new registration may be a new PBX registration id.
            com.solutions5060.aria.move.CallMoveCoordinator.forgetOwnRegistration()
            Log.i(TAG, "Registration $status${error?.let { " ($it)" } ?: ""}")
            com.solutions5060.aria.service.GatewayConnection.onCoreRegistration(appContext, deviceId, status, error)
        }

        override fun onIncomingCall(offer: CallOffer) {
            // The push already reported this call to Telecom; the offer only
            // fills in a display name the push may not have carried.
            offer.moveId?.takeIf { it.isNotEmpty() }?.let { offerMoveIds[offer.callToken] = it }
            val holdId = offer.holdReturnId?.takeIf { it.isNotEmpty() }
            val holdName = offer.holdReturnName?.takeIf { it.isNotBlank() }
            if (holdId != null || holdName != null) {
                offerHoldReturns[offer.callToken] = com.solutions5060.aria.hold.HoldReturnPush(holdId, holdName)
            }
            mainHandler.post {
                val rec = records.values.firstOrNull { it.callToken == offer.callToken } ?: return@post
                if (rec.remoteName.isNullOrBlank() && !offer.callerName.isNullOrBlank()) {
                    rec.remoteName = offer.callerName
                    publishAll()
                }
                offerHoldReturns[offer.callToken]?.let { hr ->
                    // The offer is the gateway's word; the push may have been first.
                    val wasHoldReturn = rec.holdReturnId != null
                    hr.id?.let { rec.holdReturnId = it }
                    hr.name?.let { rec.holdReturnName = it }
                    publishAll()
                    // Only the offer knew: the heads-up already up must now read "They're back".
                    if (!wasHoldReturn && rec.holdReturnId != null && rec.incomingShown &&
                        rec.phase == CallPhase.INCOMING && !rec.finished
                    ) {
                        showIncoming(rec)
                    }
                }
                applyMoveDecision(rec)
            }
        }

        override fun onCallStateChanged(info: CallInfo) {
            mainHandler.post { onEngineCallState(info) }
        }

        override fun onMediaStats(callId: String, stats: MediaStats) {}

        override fun onError(context: String, message: String) {
            // The message names the call_id, which embeds an incoming call's
            // token, so it is not logged.
            Log.w(TAG, "Engine error in $context")
            if (context == "hangup") {
                // The call ended here, but the gateway did not confirm hanging
                // it up. Not a registration problem; just let the user know.
                mainHandler.post {
                    Toast.makeText(
                        appContext,
                        Txt.get(R.string.call_err_hangup_unconfirmed, "The call ended on this phone, but the other party may still be connected."),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        override fun onAuthRequired() {
            // Fired from the core's background work (status polls, heartbeat)
            // when the gateway rejects the token. Renewal runs elsewhere.
            Log.i(TAG, "Gateway asked for a new token")
            EngineManager.onAuthRequired(appContext)
        }
    }

    private fun onEngineCallState(info: CallInfo) {
        // Outgoing ids are chosen before dialling and incoming ids come from
        // the push, so every event can be matched; one without a match is
        // for some other call (or one already finished here).
        val rec = CallWaiting.recordFor(info.callId, records.values, { it.callId }, { it.finished }) ?: return

        info.remoteName?.takeIf { it.isNotBlank() }?.let { rec.remoteName = it }
        rec.muted = info.muted
        rec.onHold = info.onHold
        rec.inConference = info.inConference
        rec.recording = info.recording
        info.codec?.let { rec.codec = it }
        // Every event once connected carries the negotiated encryption, and a
        // change mid-call is re-sent with the state unchanged; the call screen
        // re-evaluates its callout on each published state.
        CallEncryption.reported(info.state, info.encrypted)?.let { rec.encrypted = it }

        if (info.state == CallState.ENDED) {
            info.endReason?.let { rec.deferredEndReason = CallStateMapping.endReasonFor(it) }
            if (CallStateMapping.isConnectionLoss(info.endReason, rec.connectedAtElapsed != null, rec.localHangupRequested)) {
                // A gateway instance died and no other took the call over
                // (the core waits 15 s, or 60 s with no instance answering).
                _notice.value = CallNotice.DROPPED
            }
            // Events arrive asynchronously, before or after the engine method
            // that caused them returns. While one is running its result says
            // why the call ended, so an Ended now waits for that result.
            if (rec.dialInFlight && !rec.localHangupRequested) {
                // makeCallWithId reports Ended and throws, so the user sees
                // why and Telecom gets ERROR, not REMOTE.
                rec.endedDuringDial = true
                return
            }
            if (rec.answerInFlight && !rec.localHangupRequested) {
                // An accept with an unknown outcome reports Ended and throws.
                rec.endedDuringAnswer = true
                return
            }
            if (rec.transferring) {
                // A successful transfer reports Ended; the result says whether
                // this was the transfer or a hangup that raced it.
                rec.endedDuringTransfer = true
                return
            }
            // A local hangup or decline was finished before this arrived and
            // is ignored above, so nothing is recorded twice.
            finish(
                rec,
                CallStateMapping.endReasonForEngineEnd(
                    localHangupRequested = rec.localHangupRequested,
                    wasAnswered = rec.connectedAtElapsed != null,
                    direction = rec.direction,
                    coreReason = info.endReason,
                ),
            )
            return
        }

        if (info.state == CallState.CONNECTED && rec.connectedAtElapsed == null) {
            onConnected(rec)
        }
        CallStateMapping.phaseFor(info.state, info.direction)?.let { rec.phase = it }
        applyTelecom(rec, CallStateMapping.telecomUpdateFor(info.state, info.direction))
        publishAll()
    }

    private fun onConnected(rec: CallRecord) {
        com.solutions5060.aria.service.DiagLog.log("call", "${rec.direction.name.lowercase()} call connected")
        // CallInfo.duration_secs is not maintained by the core, so the call is
        // timed from here.
        rec.connectedAtElapsed = SystemClock.elapsedRealtime()
        if (rec.holdReturnId != null) rec.joinedAtMillis = System.currentTimeMillis()
        // A moved call keeps counting from when it began on the other device.
        rec.originalStartMillis?.let { start ->
            val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(0)
            rec.connectedAtElapsed = SystemClock.elapsedRealtime() - elapsed
        }
        cancelRingTimeout(rec)
        syncIncomingNotifier()
        val callId = rec.callId ?: return
        // Encryption comes with the call's events (CallInfo.encrypted), as
        // negotiated; see onEngineCallState.
        // Opt-in and a no-op unless the user turned transcription on and a
        // speech model is installed.
        scope.launch { CallTranscription.onCallStarted(appContext, callId) }
    }

    private fun applyTelecom(rec: CallRecord, update: TelecomUpdate) {
        val connection = rec.connection ?: return
        when (update) {
            TelecomUpdate.None -> {}
            TelecomUpdate.Dialing -> if (connection.state != Connection.STATE_DIALING) connection.setDialing()
            TelecomUpdate.Ringing -> if (connection.state != Connection.STATE_RINGING) connection.setRinging()
            TelecomUpdate.Active -> if (connection.state != Connection.STATE_ACTIVE) connection.setActive()
            TelecomUpdate.Hold -> if (connection.state != Connection.STATE_HOLDING) connection.setOnHold()
            is TelecomUpdate.Disconnect -> {
                connection.setDisconnected(DisconnectCause(update.cause, null, null, update.reason))
                connection.destroy()
                rec.connection = null
            }
        }
    }

    // ── Outgoing ───────────────────────────────────────────────────────

    /**
     * Starts an outgoing call through Telecom. Returns a user-facing error, or
     * null when the call is under way.
     */
    fun placeCall(context: Context, number: String): String? {
        // Before anything else, including the two-call limit: an emergency
        // number always reaches the native dialer, never the gateway.
        if (EmergencyRouting.isEmergency(number)) {
            Log.i(TAG, "Emergency number: handing off to the native dialer")
            return EmergencyRouting.handOff(context, number)
        }
        // A second call is allowed next to an answered one; the core holds
        // the first when this one is dialled (see startOutgoing).
        CallWaiting.outgoingRefusal(uiStates())?.let { return it }
        if (SipEngineHolder.engine == null) return Txt.get(R.string.call_err_not_connected, "Not connected. Go to Settings and connect first.")
        val credentials = EngineManager.savedCredentials(context)
            ?: return Txt.get(R.string.call_err_no_credentials, "Missing SIP credentials. Scan a QR code in Settings.")

        // Normalize: if already a SIP URI, use as-is; otherwise construct one.
        val sipUri = if (number.startsWith("sip:")) number else "sip:$number@${credentials.domain}"
        val rec = CallRecord(
            key = UUID.randomUUID().toString(),
            direction = CallDirection.OUTBOUND,
            remoteUri = sipUri,
            remoteName = null,
            dialUri = sipUri,
        )
        // Known before dialling, so hangup(id) works at any point, even before
        // makeCallWithId runs (it then never dials).
        rec.callId = newOutgoingCallId()
        rec.contactName = ContactNames.nameFor(appContext, sipUri)
        records[rec.key] = rec
        _error.value = null
        publishAll()

        val telecom = context.getSystemService(TelecomManager::class.java)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, TelecomAccount.handle(context))
            putBundle(
                TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                Bundle().apply { putString(EXTRA_RECORD_KEY, rec.key) },
            )
        }
        try {
            telecom.placeCall(Uri.fromParts("sip", sipUri.removePrefix("sip:"), null), extras)
        } catch (e: SecurityException) {
            // Telecom refused the account (disabled or not registered). Still
            // place the call rather than fail it; it just has no system
            // integration.
            Log.w(TAG, "Telecom placeCall refused, calling without it: ${e.message}")
            rec.audio = SpeakerFallback.routes(speaker = false)
            startOutgoing(rec)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Telecom placeCall rejected, calling without it: ${e.message}")
            rec.audio = SpeakerFallback.routes(speaker = false)
            startOutgoing(rec)
        }
        return null
    }

    /** Telecom accepted the outgoing call: bind its connection and dial. */
    fun onOutgoingConnectionCreated(connection: AriaConnection, recordKey: String?) {
        val rec = recordKey?.let { records[it] }
        if (rec == null || rec.finished || rec.direction != CallDirection.OUTBOUND || rec.key != recordKey) {
            // Not ours (a stale request); refuse it rather than leave it hanging.
            connection.setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
            connection.destroy()
            return
        }
        rec.connection = connection
        connection.setDialing()
        startOutgoing(rec)
    }

    /** Telecom refused the outgoing call (e.g. an emergency call is in progress). */
    fun onOutgoingConnectionFailed(recordKey: String?) {
        val rec = recordKey?.let { records[it] } ?: return
        if (rec.finished) return
        _error.value = Txt.get(R.string.call_err_system_refused, "The system could not place the call.")
        finish(rec, EndReason.FAILED)
    }

    private fun startOutgoing(rec: CallRecord) {
        val uri = rec.dialUri ?: return
        val credentials = EngineManager.savedCredentials(appContext)
        val engine = SipEngineHolder.engine
        if (credentials == null || engine == null) {
            _error.value = Txt.get(R.string.call_err_not_connected, "Not connected. Go to Settings and connect first.")
            finish(rec, EndReason.FAILED)
            return
        }
        rec.dialInFlight = true
        // Main thread, call not finished: audio may start for this call.
        SipEngineHolder.audioBridge?.beginCall()
        startActiveCallService(rec)
        val callId = rec.callId ?: return
        // Hold the call already up while this one rings, as a phone does; the
        // core would only hold it once this call is answered.
        val toHold = liveRecords()
            .filter { it !== rec && it.connectedAtElapsed != null && !it.onHold }
            .mapNotNull { it.callId }
        engineExecutor.execute {
            for (heldId in toHold) {
                try {
                    // Local only (no request to the gateway); emits OnHold.
                    engine.setHold(heldId, true)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not hold the other call: ${e.javaClass.simpleName}")
                }
            }
            try {
                // The dialled number is not logged.
                Log.d(TAG, "Placing call")
                // An empty codec list defers to the engine's preferences.
                engine.makeCallWithId(callId, uri, credentials, emptyList())
                mainHandler.post {
                    rec.dialInFlight = false
                    // Connected arrives as an event, before or after this.
                    if (!rec.finished && rec.endedDuringDial) {
                        // Answered and hung up before makeCallWithId returned.
                        finish(rec, rec.deferredEndReason ?: EndReason.REMOTE_HANGUP)
                    }
                }
            } catch (e: Exception) {
                val reason = CallStateMapping.endReasonForDialFailure(e)
                if (reason == EndReason.FAILED) {
                    Log.e(TAG, "makeCallWithId failed: ${e.javaClass.simpleName}")
                } else {
                    Log.i(TAG, "Outgoing call cancelled before it was answered")
                }
                if (e is MobileException.AuthenticationException) {
                    // Renew in the background and let the user redial: retrying
                    // here could ring the callee twice.
                    EngineManager.onAuthRequired(appContext)
                }
                mainHandler.post {
                    rec.dialInFlight = false
                    if (!rec.finished) {
                        CallStateMapping.dialFailureMessage(e)?.let { _error.value = it }
                        finish(rec, reason)
                    }
                }
            }
        }
    }

    /** Runs [block] on the main thread and waits for it. Never call from the main thread. */
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        mainHandler.post(task)
        return task.get()
    }

    // ── Incoming ───────────────────────────────────────────────────────

    /**
     * Reports a pushed call to Telecom, which calls back into
     * [onIncomingConnectionCreated]. Falls back to ringing without Telecom only
     * when Telecom refuses the account, so there is always exactly one
     * incoming-call UI.
     */
    fun reportIncomingCall(
        context: Context,
        callToken: String,
        callerUri: String,
        callerName: String?,
        holdReturnId: String? = null,
        holdReturnName: String? = null,
    ): Boolean {
        // FCM can deliver the same push twice. A repeat for the ringing (or
        // answered, or just-ended) call would otherwise reach Telecom as a
        // second call and be rejected as busy, taking the real call with it.
        if (!handledCallTokens.claim(callToken)) {
            Log.i(TAG, "Ignoring a repeated push for a call already handled")
            return false
        }
        val extras = Bundle().apply {
            putString(EXTRA_CALL_TOKEN, callToken)
            putString(EXTRA_CALLER_URI, callerUri)
            putString(EXTRA_CALLER_NAME, callerName)
            putString(EXTRA_HOLD_RETURN_ID, holdReturnId)
            putString(EXTRA_HOLD_RETURN_NAME, holdReturnName)
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts("sip", callerUri.removePrefix("sip:"), null),
            )
        }
        try {
            context.getSystemService(TelecomManager::class.java)
                .addNewIncomingCall(TelecomAccount.handle(context), extras)
        } catch (e: Exception) {
            Log.e(TAG, "Telecom addNewIncomingCall failed, ringing without it: $e")
            mainHandler.post {
                val rec = createIncomingRecord(extras) ?: return@post
                rec.audio = SpeakerFallback.routes(speaker = false)
                showIncoming(rec)
            }
        }
        return true
    }

    /** Telecom created the incoming connection. Returns false when we are busy. */
    fun onIncomingConnectionCreated(connection: AriaConnection, extras: Bundle?): Boolean {
        val rec = createIncomingRecord(extras ?: Bundle()) ?: return false
        rec.connection = connection
        val token = rec.callToken.orEmpty()
        if (com.solutions5060.aria.move.CallMoveCoordinator.mayWaitForMove(rec.remoteUri)) {
            // A move to this device is on its way and this call is from the
            // far party being moved: do not ring until the offer says whether
            // it is the move. If the offer is slow, ring. Any other call rings now.
            rec.awaitingMoveOffer = true
            if (offerMoveIds.containsKey(token)) {
                applyMoveDecision(rec)
            } else {
                mainHandler.postDelayed({
                    if (rec.awaitingMoveOffer && !rec.finished && rec.phase == CallPhase.INCOMING) ringAfterMoveCheck(rec)
                }, MOVE_OFFER_WAIT_MS)
            }
            return true
        }
        connection.setRinging()
        if (offerMoveIds.containsKey(token)) applyMoveDecision(rec)
        return true
    }

    /** Telecom refused the incoming call (another call is up somewhere). */
    fun onIncomingConnectionFailed(extras: Bundle?) {
        val token = extras?.getString(EXTRA_CALL_TOKEN) ?: return
        // Never decline a call already here.
        if (token.isNotEmpty() && records.values.any { it.callToken == token }) return
        Log.w(TAG, "Telecom refused incoming call; declining as busy")
        recordHistory(
            remoteUri = extras.getString(EXTRA_CALLER_URI) ?: Txt.get(R.string.caller_unknown, "Unknown"),
            remoteName = extras.getString(EXTRA_CALLER_NAME),
            direction = CallDirection.INBOUND,
            startedAtMillis = System.currentTimeMillis(),
            durationSeconds = 0,
            reason = EndReason.MISSED,
        )
        rejectToken(token)
    }

    private fun createIncomingRecord(extras: Bundle): CallRecord? {
        val token = extras.getString(EXTRA_CALL_TOKEN).orEmpty()
        val callerUri = extras.getString(EXTRA_CALLER_URI) ?: Txt.get(R.string.caller_unknown, "Unknown")
        val callerName = extras.getString(EXTRA_CALLER_NAME)
        if (token.isNotEmpty() && records.values.any { it.callToken == token }) {
            // A call already here (ringing, answered or just ended), reported
            // again: neither a new call nor a busy one.
            Log.i(TAG, "Ignoring a second report of a call already here")
            return null
        }
        if (CallWaiting.shouldRejectIncomingAsBusy(uiStates())) {
            // Two calls already (the core's limit), or another call is still
            // ringing, dialling or being answered: this caller gets busy and
            // a missed entry, and Telecom a failed connection (BUSY).
            recordHistory(callerUri, callerName, CallDirection.INBOUND, System.currentTimeMillis(), 0, EndReason.MISSED)
            if (token.isNotEmpty()) rejectToken(token)
            return null
        }
        val rec = CallRecord(
            key = UUID.randomUUID().toString(),
            direction = CallDirection.INBOUND,
            remoteUri = callerUri,
            remoteName = callerName,
            callToken = token,
        )
        rec.holdReturnId = extras.getString(EXTRA_HOLD_RETURN_ID)?.takeIf { it.isNotEmpty() }
        rec.holdReturnName = extras.getString(EXTRA_HOLD_RETURN_NAME)?.takeIf { it.isNotBlank() }
        // The engine names an offered call after its token, so a caller who
        // hangs up before we answer can be matched before accept.
        if (token.isNotEmpty()) rec.callId = "incoming-$token"
        // Before the incoming-call notification is posted, so it shows the name.
        rec.contactName = ContactNames.nameFor(appContext, callerUri)
        records[rec.key] = rec
        armRingTimeout(rec)
        publishAll()
        return rec
    }

    /** Telecom asked us to show our ringing UI (self-managed calls draw their own). */
    fun onShowIncomingCallUi(connection: AriaConnection) {
        val rec = recordFor(connection) ?: return
        if (rec.phase != CallPhase.INCOMING || rec.awaitingMoveOffer || rec.moveAutoAnswer) return
        showIncoming(rec)
    }

    private fun showIncoming(rec: CallRecord) {
        rec.incomingShown = true
        // Next to another call this is call waiting: a quieter alert, since
        // the user is already on the phone.
        val waiting = liveRecords().any { it !== rec }
        val label = rec.movingFrom?.let { Txt.get(R.string.move_incoming_from, "Moving call from %1\$s", it) }
            // The far party of a Hold for me wait is back; the push alone says so. Once the wait is
            // known, a Rejoin now or final ring is labelled with the plain name: nobody is back.
            ?: rec.holdReturnId?.let { holdId ->
                com.solutions5060.aria.hold.HoldReturn.label(
                    Txt.get(R.string.ui_hold_theyre_back, "They're back — %1\$s"),
                    rec.toUiState(),
                    com.solutions5060.aria.hold.HoldForMeCoordinator.waitForHoldReturn(holdId)?.ringReason,
                )
            }
        IncomingCallNotifier.show(appContext, rec.key, label ?: rec.displayName(), rec.callToken ?: "", waiting)
    }

    /**
     * Answer the ringing call. [callToken], when given, must match it. Any
     * call already up is put on hold by the core when this one connects.
     */
    fun answer(callToken: String? = null) {
        ringingRecord(callToken)?.let { answer(it) }
    }

    /** Answer the ringing call with record [key] (the one the screen showed). */
    fun answerByKey(key: String) {
        records[key]?.let { answer(it) }
    }

    /** Telecom (a headset, a watch, the car) answered [connection]. */
    fun answer(connection: AriaConnection) {
        recordFor(connection)?.let { answer(it) }
    }

    /** Hang up every other call, then answer the ringing call with record [key]. */
    fun endOthersAndAnswer(key: String) {
        val rec = records[key]?.takeIf { it.phase == CallPhase.INCOMING && !it.finished } ?: return
        liveRecords().filter { it !== rec }.forEach { hangup(it) }
        answer(rec)
    }

    private fun answer(rec: CallRecord) {
        if (rec.phase != CallPhase.INCOMING || rec.finished) return
        val token = rec.callToken ?: return
        cancelRingTimeout(rec)
        rec.phase = CallPhase.CONNECTING
        syncIncomingNotifier()
        publishAll()
        // Now, while the user's answer still counts as foreground use; see
        // IncomingCallService for why the microphone type depends on it.
        startActiveCallService(rec)

        engineExecutor.execute {
            try {
                // A push can start this process with no engine; build it from
                // the saved account before accepting.
                val engine = SipEngineHolder.engine ?: EngineManager.ensureEngine(appContext)
                    ?: throw IllegalStateException("Not connected")
                // Let audio start for this call, unless it was hung up while
                // the engine was being built. Checked on the main thread, where
                // finish() runs, so the two cannot interleave.
                val stillWanted = onMain {
                    if (!rec.finished) {
                        SipEngineHolder.audioBridge?.beginCall()
                        rec.answerInFlight = true
                    }
                    !rec.finished
                }
                if (!stillWanted) {
                    // Nothing was accepted yet: decline rather than answer and hang up.
                    EngineManager.withAuthRetry(appContext) { engine.rejectIncomingCall(token) }
                    return@execute
                }
                // An empty codec list defers to the engine's preferences.
                val info = EngineManager.withAuthRetry(appContext) { engine.acceptIncomingCall(token, emptyList()) }
                mainHandler.post {
                    rec.answerInFlight = false
                    rec.accepted = true
                    if (rec.finished) {
                        // Hung up while the accept was in flight: the engine
                        // only holds the call now, so end it now.
                        engineExecutor.execute { hangupLate(info.callId) }
                    } else if (rec.endedDuringAnswer) {
                        // Connected and hung up before the accept returned.
                        finish(rec, rec.deferredEndReason ?: EndReason.REMOTE_HANGUP)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept call: ${e.javaClass.simpleName}")
                if (CallStateMapping.acceptFailureNeedsReject(e)) {
                    // Refused: the call is still offered and the caller still
                    // hears it ring. Timeout/NetworkError are resolved by the core.
                    try {
                        SipEngineHolder.engine?.rejectIncomingCall(token)
                    } catch (r: Exception) {
                        Log.w(TAG, "Could not decline after a failed answer: ${r.javaClass.simpleName}")
                    }
                }
                mainHandler.post {
                    rec.answerInFlight = false
                    if (!rec.finished) {
                        _error.value = Txt.get(R.string.call_err_answer_failed, "Failed to answer: %1\$s", e.message ?: e.javaClass.simpleName)
                        finish(rec, EndReason.FAILED)
                    }
                }
            }
        }
    }

    /** Decline the ringing call. [callToken], when given, must match it. */
    fun reject(callToken: String? = null) {
        ringingRecord(callToken)?.let { reject(it) }
    }

    /** Decline the ringing call with record [key]. */
    fun rejectByKey(key: String) {
        records[key]?.let { reject(it) }
    }

    /** Telecom declined [connection]. */
    fun reject(connection: AriaConnection) {
        recordFor(connection)?.let { reject(it) }
    }

    private fun reject(rec: CallRecord) {
        if (rec.phase != CallPhase.INCOMING || rec.finished) return
        finish(rec, EndReason.REJECTED)
        rec.callToken?.let { rejectToken(it) }
    }

    private fun rejectToken(token: String) {
        engineExecutor.execute {
            try {
                val engine = SipEngineHolder.engine ?: EngineManager.ensureEngine(appContext)
                if (engine != null) EngineManager.withAuthRetry(appContext) { engine.rejectIncomingCall(token) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject call: ${e.message}")
            }
        }
    }

    // ── In-call controls ─────────────────────────────────────────────────

    /** Hang up (or cancel, or decline) the call on screen. */
    fun hangup() {
        foregroundRecord()?.let { hangup(it) }
    }

    /** Hang up the call with record [key]. */
    fun hangup(key: String) {
        records[key]?.let { hangup(it) }
    }

    /** Telecom ended [connection] (a headset button, the system). */
    fun hangup(connection: AriaConnection) {
        recordFor(connection)?.let { hangup(it) }
    }

    private fun hangup(rec: CallRecord) {
        if (rec.finished) return
        if (rec.phase == CallPhase.INCOMING) {
            reject(rec)
            return
        }
        rec.localHangupRequested = true
        val callId = rec.callId
        // hangup never waits on the gateway, so the call ends here at once;
        // the Ended it reports is then ignored.
        finish(rec, EndReason.LOCAL_HANGUP)
        val accepted = rec.accepted || rec.connectedAtElapsed != null
        if (callId == null || !CallWaiting.hangupReachesEngine(rec.direction, true, accepted)) {
            // An answer still in flight: answer() hangs the call up when the
            // accept returns (or declines it if the accept has not started).
            return
        }
        // Valid at any point for an outgoing call, even before makeCallWithId
        // runs: the core then never dials, and cancels a ringing call.
        engineExecutor.execute { safeHangup(callId) }
    }

    fun setMuted(muted: Boolean) {
        foregroundRecord()?.let { setMuted(it, muted) }
    }

    /** Mute the call with record [key]: the screen names the call it showed when tapped. */
    fun setMuted(key: String, muted: Boolean) {
        records[key]?.let { setMuted(it, muted) }
    }

    private fun setMuted(rec: CallRecord, muted: Boolean) {
        if (rec.finished) return
        val callId = rec.callId ?: return
        rec.muted = muted
        publishAll()
        engineExecutor.execute {
            try {
                SipEngineHolder.engine?.let { engine -> EngineManager.withAuthRetry(appContext) { engine.setMute(callId, muted) } }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set mute: $e")
            }
        }
    }

    /** Hold or resume the call on screen. */
    fun setHold(onHold: Boolean) {
        foregroundRecord()?.let { setHold(it, onHold) }
    }

    /** Hold or resume the call with record [key]. */
    fun setHold(key: String, onHold: Boolean) {
        records[key]?.let { setHold(it, onHold) }
    }

    /** Telecom asked to hold or resume [connection] (Bluetooth, Android Auto, the system). */
    fun setHold(connection: AriaConnection, onHold: Boolean) {
        recordFor(connection)?.let { setHold(it, onHold) }
    }

    /** Bring the held call with record [key] back, holding whichever call is active. */
    fun swapTo(key: String) {
        // The core refuses a swap while conferenced; Split first.
        if (!Conference.canSwap(uiStates())) return
        records[key]?.let { setHold(it, false) }
    }

    private fun setHold(rec: CallRecord, onHold: Boolean) {
        val callId = rec.callId ?: return
        if (rec.finished || rec.connectedAtElapsed == null) return
        val action: (engine: uniffi.aria_mobile.AriaMobileEngine) -> Unit = if (onHold) {
            { engine -> engine.setHold(callId, true) }
        } else {
            val states = uiStates()
            val target = states.firstOrNull { it.key == rec.key } ?: return
            val active = states.firstOrNull { it.key != rec.key && it.phase == CallPhase.ACTIVE }
            when (val resume = CallWaiting.resumeAction(target, active) ?: return) {
                is CallWaiting.ResumeAction.Swap -> { engine -> engine.swapCalls(resume.activeCallId, resume.heldCallId) }
                is CallWaiting.ResumeAction.Resume -> { engine -> engine.setHold(resume.callId, false) }
            }
        }
        engineExecutor.execute {
            try {
                // Emits OnHold / Connected for each call whose hold changed,
                // which updates Telecom and the UI.
                SipEngineHolder.engine?.let { engine -> EngineManager.withAuthRetry(appContext) { action(engine) } }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set hold: ${e.javaClass.simpleName}")
            }
        }
    }

    fun sendDtmf(digit: String) {
        foregroundRecord()?.let { sendDtmf(it, digit) }
    }

    /** Send a DTMF digit on the call with record [key]. */
    fun sendDtmf(key: String, digit: String) {
        records[key]?.let { sendDtmf(it, digit) }
    }

    /** Telecom asked [connection] to play a DTMF tone. */
    fun sendDtmf(connection: AriaConnection, digit: String) {
        recordFor(connection)?.let { sendDtmf(it, digit) }
    }

    private fun sendDtmf(rec: CallRecord, digit: String) {
        if (rec.finished) return
        val callId = rec.callId ?: return
        engineExecutor.execute {
            try {
                SipEngineHolder.engine?.let { engine -> EngineManager.withAuthRetry(appContext) { engine.sendDtmf(callId, digit) } }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send DTMF: $e")
            }
        }
    }

    // ── Transfer ──────────────────────────────────────────────────────

    /**
     * Blind-transfers the connected call to [target]. On success the engine
     * ends the call and the screen closes; on failure the call stays up and
     * [CallUiState.transferError] says why.
     */
    fun transfer(target: String) {
        foregroundRecord()?.let { transfer(it, target) }
    }

    /** Blind-transfers the call with record [key]. */
    fun transfer(key: String, target: String) {
        records[key]?.let { transfer(it, target) }
    }

    private fun transfer(rec: CallRecord, target: String) {
        val callId = rec.callId ?: return
        if (rec.finished || rec.transferring || rec.connectedAtElapsed == null) return
        val dial = CallStateMapping.normalizeTransferTarget(target)
        if (dial.isEmpty()) {
            rec.transferError = Txt.get(R.string.call_err_enter_transfer_target, "Enter a number to transfer to.")
            publishAll()
            return
        }
        rec.transferring = true
        rec.endedDuringTransfer = false
        rec.transferError = null
        publishAll()

        engineExecutor.execute {
            val failure = try {
                val engine = SipEngineHolder.engine ?: throw IllegalStateException("Not connected")
                EngineManager.withAuthRetry(appContext) { engine.transferCall(callId, dial) }
                null
            } catch (e: Exception) {
                Log.w(TAG, "Transfer failed: ${e.javaClass.simpleName}: ${e.message}")
                e
            }
            mainHandler.post { onTransferResult(rec, failure) }
        }
    }

    private fun onTransferResult(
        rec: CallRecord,
        failure: Exception?,
        message: (Throwable) -> String = CallStateMapping::transferErrorMessage,
    ) {
        rec.transferring = false
        if (rec.finished) return
        if (failure == null) {
            finish(rec, EndReason.TRANSFERRED)
            return
        }
        if (rec.endedDuringTransfer) {
            // The call ended another way while the transfer was attempted.
            finish(rec, rec.deferredEndReason ?: EndReason.REMOTE_HANGUP)
            return
        }
        rec.transferError = message(failure)
        publishAll()
    }

    /**
     * Consult first: places a call to [target] from the answered call on
     * screen (incoming or outgoing), which the core holds. Once it is answered, "Complete
     * transfer" ([transferAttended]) joins the two.
     */
    fun consult(target: String) {
        foregroundRecord()?.let { consult(it, target) }
    }

    /** Consult first from the call with record [key]. */
    fun consult(key: String, target: String) {
        records[key]?.let { consult(it, target) }
    }

    private fun consult(rec: CallRecord, target: String) {
        if (rec.finished || rec.transferring) return
        val states = uiStates()
        val ui = states.firstOrNull { it.key == rec.key } ?: return
        if (!CallWaiting.canConsult(ui, states)) return
        val dial = CallStateMapping.normalizeTransferTarget(target)
        rec.transferError = if (dial.isEmpty()) Txt.get(R.string.call_err_enter_number, "Enter a number to call.") else placeCall(appContext, dial)
        publishAll()
    }

    /**
     * Joins the two connected calls ([CallWaiting.attendedTransferPair]),
     * whichever way each went. On success the core ends both (Transferred) and they are logged
     * as transferred; on failure both stay up and each says why.
     */
    fun transferAttended() {
        val pair = CallWaiting.attendedTransferPair(uiStates()) ?: return
        val transferred = records[pair.transferred.key] ?: return
        val consult = records[pair.consult.key] ?: return
        val both = listOf(transferred, consult)
        if (both.any { it.finished || it.transferring }) return
        val callId = transferred.callId ?: return
        val consultId = consult.callId ?: return
        both.forEach {
            it.transferring = true
            it.endedDuringTransfer = false
            it.transferError = null
        }
        publishAll()

        engineExecutor.execute {
            val failure = try {
                val engine = SipEngineHolder.engine ?: throw IllegalStateException("Not connected")
                EngineManager.withAuthRetry(appContext) { engine.transferAttended(callId, consultId) }
                null
            } catch (e: Exception) {
                Log.w(TAG, "Attended transfer failed: ${e.javaClass.simpleName}")
                e
            }
            mainHandler.post {
                both.forEach { rec ->
                    val partner = if (rec === transferred) consult else transferred
                    onTransferResult(rec, failure) { e ->
                        CallWaiting.attendedTransferErrorMessage(e, partner.finished || partner.endedDuringTransfer)
                    }
                }
            }
        }
    }

    fun clearTransferError() {
        var changed = false
        records.values.forEach {
            if (it.transferError != null) {
                it.transferError = null
                changed = true
            }
        }
        if (changed) publishAll()
    }

    // ── Conference ──────────────────────────────────────────────────────

    /**
     * Merges the two connected calls into a local three-way conference
     * (`conference_merge`). Both come off hold and report
     * [CallUiState.inConference]. Returns why not when there is no pair to
     * merge; an engine failure is reported through [actionError].
     */
    fun mergeCalls(): String? {
        if (com.solutions5060.aria.fixtures.Fixtures.active) return fixtureMerge()
        val pair = Conference.mergePair(uiStates()) ?: return Txt.get(R.string.call_err_merge_needs_two, "Merge needs two connected calls.")
        val a = pair.first.callId ?: return null
        val b = pair.second.callId ?: return null
        _actionError.value = null
        engineExecutor.execute {
            try {
                val engine = SipEngineHolder.engine ?: throw IllegalStateException("Not connected")
                // Local mixing only (no gateway request); events update both calls.
                engine.conferenceMerge(a, b)
            } catch (e: Exception) {
                Log.w(TAG, "Merge failed: ${e.javaClass.simpleName}")
                mainHandler.post { _actionError.value = Txt.get(R.string.call_err_merge_failed, "The calls could not be merged.") }
            }
        }
        return null
    }

    /**
     * Ends the conference, keeping both calls (`conference_split`): the newer
     * one stays connected, the older goes on hold. Returns why not when there
     * is no conference.
     */
    fun splitConference(): String? {
        if (com.solutions5060.aria.fixtures.Fixtures.active) return fixtureSplit()
        if (!Conference.canSplit(uiStates())) return Txt.get(R.string.call_err_no_conference, "There is no conference to split.")
        _actionError.value = null
        engineExecutor.execute {
            try {
                val engine = SipEngineHolder.engine ?: throw IllegalStateException("Not connected")
                engine.conferenceSplit()
            } catch (e: Exception) {
                Log.w(TAG, "Split failed: ${e.javaClass.simpleName}")
                mainHandler.post { _actionError.value = Txt.get(R.string.call_err_split_failed, "The conference could not be split.") }
            }
        }
        return null
    }

    // ── Recording ───────────────────────────────────────────────────────

    /** Whether the user still has to confirm the recording notice ([RecordingConsent]). */
    fun recordingConsentNeeded(): Boolean = !RecordingConsent.isAcknowledged(appContext)

    /**
     * Starts recording the connected call with record [key] into
     * `filesDir/recordings` (`start_recording`). [consentGiven] is true when
     * the user has just confirmed the recording notice; it is remembered.
     * Without it, and without an earlier confirmation, nothing is recorded.
     * Returns why not; an engine failure is reported through [actionError].
     */
    fun startRecording(key: String, consentGiven: Boolean = false): String? {
        if (consentGiven) RecordingConsent.acknowledge(appContext)
        if (recordingConsentNeeded()) return Txt.get(R.string.call_err_record_consent, "Confirm the recording notice first.")
        val rec = records[key] ?: return null
        if (rec.finished || rec.connectedAtElapsed == null) return Txt.get(R.string.call_err_record_not_connected, "Only a connected call can be recorded.")
        if (rec.recording) return null
        val callId = rec.callId ?: return null
        val file = RecordingsRepository(appContext).newRecordingFile(rec.remoteUri)
        _actionError.value = null
        engineExecutor.execute {
            try {
                val engine = SipEngineHolder.engine ?: throw IllegalStateException("Not connected")
                // Reports the call with recording = true.
                engine.startRecording(callId, file.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "Start recording failed: ${e.javaClass.simpleName}")
                if (file.length() == 0L) file.delete()
                mainHandler.post { _actionError.value = Txt.get(R.string.call_err_record_start, "Recording could not start.") }
            }
        }
        return null
    }

    /**
     * Stops recording the call with record [key] (`stop_recording`). The file
     * stays in the recordings list. Recording also stops by itself when the
     * call ends.
     */
    fun stopRecording(key: String) {
        val rec = records[key] ?: return
        if (rec.finished || !rec.recording) return
        val callId = rec.callId ?: return
        engineExecutor.execute {
            try {
                SipEngineHolder.engine?.stopRecording(callId)
            } catch (e: Exception) {
                Log.w(TAG, "Stop recording failed: ${e.javaClass.simpleName}")
                mainHandler.post { _actionError.value = Txt.get(R.string.call_err_record_incomplete, "The recording may be incomplete.") }
            }
        }
    }

    // ── Audio routing ──────────────────────────────────────────────────

    /** Telecom changed the available or active audio route. */
    fun onAudioRoutesChanged(connection: AriaConnection, routes: AudioRouteState) {
        val rec = recordFor(connection) ?: return
        rec.audio = routes
        publishAll()
    }

    /** Moves call audio to [option]. */
    fun selectAudioRoute(option: AudioRouteOption) {
        foregroundRecord()?.let { selectAudioRoute(it, option) }
    }

    /** Moves call audio to [option], through the connection of the call with record [key]. */
    fun selectAudioRoute(key: String, option: AudioRouteOption) {
        (records[key] ?: foregroundRecord())?.let { selectAudioRoute(it, option) }
    }

    private fun selectAudioRoute(rec: CallRecord, option: AudioRouteOption) {
        val connection = rec.connection
        if (connection != null) {
            connection.requestRoute(option)
            return
        }
        // No Telecom connection (the account was refused): route directly.
        val speaker = option.type == AudioRouteType.SPEAKER
        SpeakerFallback.setSpeaker(appContext, speaker)
        rec.audio = SpeakerFallback.routes(speaker)
        publishAll()
    }

    // ── Teardown ────────────────────────────────────────────────────────

    /**
     * Hangs up a call that was already finished here before the engine took it
     * on, and closes the audio its media may have opened in the meantime.
     */
    private fun hangupLate(callId: String) {
        safeHangup(callId)
        mainHandler.post {
            // Unless another call is up, which still needs the audio.
            if (liveRecords().isEmpty()) SipEngineHolder.audioBridge?.stop()
        }
    }

    private fun safeHangup(callId: String) {
        try {
            SipEngineHolder.engine?.hangup(callId)
        } catch (e: MobileException.AuthenticationException) {
            // Only when there is no gateway token at all: the call has ended
            // here and nothing was sent. A rejected token is reported through
            // on_auth_required instead.
            Log.w(TAG, "Hangup without a gateway token; renewing")
            EngineManager.onAuthRequired(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Hangup failed: ${e.message}")
        }
    }

    private fun finish(rec: CallRecord, reason: EndReason) {
        if (rec.finished) return
        rec.finished = true
        rec.heldForYou = reason == EndReason.HELD_FOR_YOU
        Log.i(TAG, "Call ${rec.key} ended: $reason")

        cancelRingTimeout(rec)
        syncIncomingNotifier()
        if (rec.holdReturnId != null) com.solutions5060.aria.hold.HoldForMeCoordinator.releasePushListener(appContext)
        val remaining = liveRecords()
        // Only a call still ringing here is left: nothing needs the audio.
        val onlyRinging = CallWaiting.onlyRingingLeft(remaining.map { it.toUiState() })
        val releaseAudio = remaining.isEmpty() || onlyRinging
        // Telecom resets its own routing; only the no-Telecom fallback needs undoing.
        if (releaseAudio && rec.connection == null && rec.audio.isSpeaker) SpeakerFallback.setSpeaker(appContext, false)
        applyTelecom(rec, CallStateMapping.disconnectFor(reason))

        if (releaseAudio) {
            // Release the audio hardware (and the microphone service) so
            // other apps can use it. A held call left behind keeps both: it
            // stays held until the user resumes it. Answering a call left
            // ringing begins audio and starts the service again, in that
            // order, as for any incoming call.
            SipEngineHolder.audioBridge?.stop()
            stopActiveCallService()
        }
        if (onlyRinging) {
            // It was announced as call waiting (a single quiet alert); with
            // no call left it must ring like any incoming call.
            remaining.filter { it.phase == CallPhase.INCOMING }.forEach { showIncoming(it) }
        }

        val connectedAt = rec.connectedAtElapsed
        val durationSeconds = connectedAt?.let { ((SystemClock.elapsedRealtime() - it) / 1000).toInt() } ?: 0
        recordHistory(rec.remoteUri, rec.contactName ?: rec.remoteName, rec.direction, rec.startedAtMillis, durationSeconds, reason)
        // A caller sent to voicemail leaves the message after this.
        com.solutions5060.aria.voicemail.VoicemailMonitor.afterCall(appContext)

        val callId = rec.callId
        if (callId != null && connectedAt != null) {
            // Transcribing takes longer than the teardown around it.
            scope.launch {
                val insight = CallTranscription.onCallEnded(appContext, callId)
                if (insight != null) {
                    Log.i(TAG, "Transcript ready: ${insight.segments.size} segments (${insight.status})")
                }
            }
        }

        rec.phase = CallPhase.ENDED
        // No numbers or names in the diagnostics log.
        com.solutions5060.aria.service.DiagLog.log(
            "call",
            "${rec.direction.name.lowercase()} call ended: ${reason.name.lowercase()}" +
                (connectedAt?.let { ", ${durationSeconds}s" } ?: "") + (rec.codec?.let { ", $it" } ?: ""),
        )
        publishAll()
        mainHandler.postDelayed({
            if (records[rec.key] === rec) {
                records.remove(rec.key)
                publishAll()
            }
        }, ENDED_LINGER_MS)
    }

    private fun recordHistory(
        remoteUri: String,
        remoteName: String?,
        direction: CallDirection,
        startedAtMillis: Long,
        durationSeconds: Int,
        reason: EndReason,
    ) {
        val entry = CallHistoryRecorder.entryFor(
            remoteUri, remoteName, direction, startedAtMillis, durationSeconds, reason,
        ) ?: return
        try {
            CallHistoryStore.add(appContext, entry)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save call history: ${e.message}")
        }
    }

    private fun startActiveCallService(rec: CallRecord) {
        // Keeps the process (and microphone access) alive while the call is up.
        IncomingCallService.start(appContext, rec.displayName())
    }

    private fun stopActiveCallService() {
        IncomingCallService.stop(appContext)
    }

    private fun CallRecord.displayName(): String = toUiState().displayName

    private fun CallRecord.toUiState() = CallUiState(
        key = key,
        callId = callId,
        remoteUri = remoteUri,
        remoteName = remoteName,
        direction = direction,
        phase = phase,
        muted = muted,
        onHold = onHold,
        connectedAtElapsed = connectedAtElapsed,
        audio = audio,
        encrypted = encrypted,
        transferring = transferring,
        transferError = transferError,
        inConference = inConference,
        recording = recording,
        contactName = contactName,
        codec = codec,
        movingFrom = movingFrom,
        movedTo = movedTo,
        originalStartMillis = originalStartMillis,
        heldForYou = heldForYou,
        holdReturnId = holdReturnId,
        holdReturnName = holdReturnName,
        joinedAtMillis = joinedAtMillis,
    )

    /**
     * Debug fixture mode only ([com.solutions5060.aria.fixtures.Fixtures]):
     * shows [calls] on screen without any engine or Telecom call behind them.
     * Actions on them find no record and do nothing.
     */
    fun showFixtureCalls(calls: List<CallUiState>) {
        if (!com.solutions5060.aria.BuildConfig.DEBUG) return
        _calls.value = calls
        _state.value = CallWaiting.foreground(calls)
    }

    /** Fixture mode: what the core reports after `conference_merge` (both connected, conferenced). */
    private fun fixtureMerge(): String? {
        val calls = _calls.value
        val pair = Conference.mergePair(calls) ?: return Txt.get(R.string.call_err_merge_needs_two, "Merge needs two connected calls.")
        val keys = setOf(pair.first.key, pair.second.key)
        showFixtureCalls(
            calls.map {
                if (it.key in keys) it.copy(inConference = true, phase = CallPhase.ACTIVE, onHold = false) else it
            },
        )
        return null
    }

    /** Fixture mode: what the core reports after `conference_split` (older held, newer connected). */
    private fun fixtureSplit(): String? {
        val calls = _calls.value
        if (!Conference.canSplit(calls)) return Txt.get(R.string.call_err_no_conference, "There is no conference to split.")
        val newest = calls.lastOrNull { it.inConference }?.key
        showFixtureCalls(
            calls.map {
                when {
                    !it.inConference -> it
                    it.key == newest -> it.copy(inConference = false, phase = CallPhase.ACTIVE, onHold = false)
                    else -> it.copy(inConference = false, phase = CallPhase.HELD, onHold = true)
                }
            },
        )
        return null
    }

    private fun publishAll() {
        val states = uiStates()
        _calls.value = states
        _state.value = CallWaiting.foreground(states)
    }
}
