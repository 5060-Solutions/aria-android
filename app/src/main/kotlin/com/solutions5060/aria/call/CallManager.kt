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
import com.solutions5060.aria.ai.CallTranscription
import com.solutions5060.aria.service.AriaConnection
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.IncomingCallService
import com.solutions5060.aria.service.SipEngineHolder
import com.solutions5060.aria.history.CallHistoryRecorder
import com.solutions5060.aria.history.CallHistoryStore
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
    /** Whether media is SRTP-encrypted as negotiated; null until known. */
    val encrypted: Boolean? = null,
    /** A blind transfer is waiting for its outcome (up to about 45 s). */
    val transferring: Boolean = false,
    /** Why the last transfer attempt failed; the call is still up. */
    val transferError: String? = null,
) {
    /** Name if the network sent one, else the user part of the URI. */
    val displayName: String
        get() = remoteName?.takeIf { it.isNotBlank() }
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
        var endedDuringAnswer = false
        /** The core's reason on an Ended held back for a method's result. */
        var deferredEndReason: EndReason? = null
        /** Ends the call as missed if it rings too long; null once it stops ringing. */
        var ringTimeout: Runnable? = null
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
    }

    fun clearError() {
        _error.value = null
    }

    // ── Engine events ───────────────────────────────────────────────────

    /** Installed on every engine by [EngineManager]. */
    val eventHandler: MobileEventHandler = object : MobileEventHandler {
        override fun onRegistrationChanged(deviceId: String, status: String, error: String?) {
            Log.i(TAG, "Registration $status${error?.let { " ($it)" } ?: ""}")
        }

        override fun onIncomingCall(offer: CallOffer) {
            // The push already reported this call to Telecom; the offer only
            // fills in a display name the push may not have carried.
            mainHandler.post {
                val rec = records.values.firstOrNull { it.callToken == offer.callToken } ?: return@post
                if (rec.remoteName.isNullOrBlank() && !offer.callerName.isNullOrBlank()) {
                    rec.remoteName = offer.callerName
                    publishAll()
                }
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
                        "The call ended on this phone, but the other party may still be connected.",
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

        if (info.state == CallState.ENDED) {
            info.endReason?.let { rec.deferredEndReason = CallStateMapping.endReasonFor(it) }
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
        // CallInfo.duration_secs is not maintained by the core, so the call is
        // timed from here.
        rec.connectedAtElapsed = SystemClock.elapsedRealtime()
        cancelRingTimeout(rec)
        syncIncomingNotifier()
        val callId = rec.callId ?: return
        // Ask the engine what SRTP negotiation produced; a padlock derived
        // from settings would lie whenever the peer declined the crypto line.
        engineExecutor.execute {
            val encrypted = try {
                SipEngineHolder.engine?.callIsEncrypted(callId)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read call encryption: ${e.message}")
                null
            }
            mainHandler.post {
                if (!rec.finished && encrypted != null) {
                    rec.encrypted = encrypted
                    publishAll()
                }
            }
        }
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
        // A second call is allowed next to an answered one; the core holds
        // the first when this one is dialled (see startOutgoing).
        CallWaiting.outgoingRefusal(uiStates())?.let { return it }
        if (SipEngineHolder.engine == null) return "Not connected. Go to Settings and connect first."
        val credentials = EngineManager.savedCredentials(context)
            ?: return "Missing SIP credentials. Scan a QR code in Settings."

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
        _error.value = "The system could not place the call."
        finish(rec, EndReason.FAILED)
    }

    private fun startOutgoing(rec: CallRecord) {
        val uri = rec.dialUri ?: return
        val credentials = EngineManager.savedCredentials(appContext)
        val engine = SipEngineHolder.engine
        if (credentials == null || engine == null) {
            _error.value = "Not connected. Go to Settings and connect first."
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
    fun reportIncomingCall(context: Context, callToken: String, callerUri: String, callerName: String?): Boolean {
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
        connection.setRinging()
        return true
    }

    /** Telecom refused the incoming call (another call is up somewhere). */
    fun onIncomingConnectionFailed(extras: Bundle?) {
        val token = extras?.getString(EXTRA_CALL_TOKEN) ?: return
        // Never decline a call already here.
        if (token.isNotEmpty() && records.values.any { it.callToken == token }) return
        Log.w(TAG, "Telecom refused incoming call; declining as busy")
        recordHistory(
            remoteUri = extras.getString(EXTRA_CALLER_URI) ?: "Unknown",
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
        val callerUri = extras.getString(EXTRA_CALLER_URI) ?: "Unknown"
        val callerName = extras.getString(EXTRA_CALLER_NAME)
        if (token.isNotEmpty() && records.values.any { it.callToken == token }) {
            // A call already here (ringing, answered or just ended), reported
            // again: neither a new call nor a busy one.
            Log.i(TAG, "Ignoring a second report of a call already here")
            return null
        }
        if (CallWaiting.shouldRejectIncomingAsBusy(uiStates())) {
            // Two calls already (the core's limit), or another call is still
            // ringing: this caller gets busy and a missed entry.
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
        // The engine names an offered call after its token, so a caller who
        // hangs up before we answer can be matched before accept.
        if (token.isNotEmpty()) rec.callId = "incoming-$token"
        records[rec.key] = rec
        armRingTimeout(rec)
        publishAll()
        return rec
    }

    /** Telecom asked us to show our ringing UI (self-managed calls draw their own). */
    fun onShowIncomingCallUi(connection: AriaConnection) {
        val rec = recordFor(connection) ?: return
        if (rec.phase != CallPhase.INCOMING) return
        showIncoming(rec)
    }

    private fun showIncoming(rec: CallRecord) {
        // Next to another call this is call waiting: a quieter alert, since
        // the user is already on the phone.
        val waiting = liveRecords().any { it !== rec }
        IncomingCallNotifier.show(appContext, rec.key, rec.displayName(), rec.callToken ?: "", waiting)
    }

    /**
     * Answer the ringing call. [callToken], when given, must match it. Any
     * call already up is put on hold by the core when this one connects.
     */
    fun answer(callToken: String? = null) {
        ringingRecord(callToken)?.let { answer(it) }
    }

    /** Telecom (a headset, a watch, the car) answered [connection]. */
    fun answer(connection: AriaConnection) {
        recordFor(connection)?.let { answer(it) }
    }

    /** Hang up every other call, then answer the ringing one. */
    fun endOthersAndAnswer() {
        val rec = ringingRecord() ?: return
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
                        _error.value = "Failed to answer: ${e.message}"
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
        if (callId == null || rec.direction == CallDirection.INBOUND && rec.connectedAtElapsed == null) {
            // An answer still in flight: answer() hangs the call up when the
            // accept returns.
            return
        }
        // Valid at any point for an outgoing call, even before makeCallWithId
        // runs: the core then never dials, and cancels a ringing call.
        engineExecutor.execute { safeHangup(callId) }
    }

    fun setMuted(muted: Boolean) {
        val rec = foregroundRecord() ?: return
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

    /** Telecom asked to hold or resume [connection] (Bluetooth, Android Auto, the system). */
    fun setHold(connection: AriaConnection, onHold: Boolean) {
        recordFor(connection)?.let { setHold(it, onHold) }
    }

    /** Bring the held call with record [key] back, holding whichever call is active. */
    fun swapTo(key: String) {
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

    /** Telecom asked [connection] to play a DTMF tone. */
    fun sendDtmf(connection: AriaConnection, digit: String) {
        recordFor(connection)?.let { sendDtmf(it, digit) }
    }

    private fun sendDtmf(rec: CallRecord, digit: String) {
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
        val rec = foregroundRecord() ?: return
        val callId = rec.callId ?: return
        if (rec.finished || rec.transferring || rec.connectedAtElapsed == null) return
        val dial = CallStateMapping.normalizeTransferTarget(target)
        if (dial.isEmpty()) {
            rec.transferError = "Enter a number to transfer to."
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
     * Consult first: places a call to [target] from the answered incoming
     * call on screen, which the core holds. Once it is answered, "Complete
     * transfer" ([transferAttended]) joins the two.
     */
    fun consult(target: String) {
        val rec = foregroundRecord() ?: return
        if (rec.finished || rec.transferring) return
        val states = uiStates()
        val ui = states.firstOrNull { it.key == rec.key } ?: return
        if (!CallWaiting.canConsult(ui, states)) return
        val dial = CallStateMapping.normalizeTransferTarget(target)
        rec.transferError = if (dial.isEmpty()) "Enter a number to call." else placeCall(appContext, dial)
        publishAll()
    }

    /**
     * Joins the answered incoming call with the consultation call this phone
     * placed. On success the core ends both (Transferred) and they are logged
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
                both.forEach { onTransferResult(it, failure, CallWaiting::attendedTransferErrorMessage) }
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

    // ── Audio routing ──────────────────────────────────────────────────

    /** Telecom changed the available or active audio route. */
    fun onAudioRoutesChanged(connection: AriaConnection, routes: AudioRouteState) {
        val rec = recordFor(connection) ?: return
        rec.audio = routes
        publishAll()
    }

    /** Moves call audio to [option]. */
    fun selectAudioRoute(option: AudioRouteOption) {
        val rec = foregroundRecord() ?: return
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
        Log.i(TAG, "Call ${rec.key} ended: $reason")

        cancelRingTimeout(rec)
        syncIncomingNotifier()
        val lastCall = liveRecords().isEmpty()
        // Telecom resets its own routing; only the no-Telecom fallback needs undoing.
        if (lastCall && rec.connection == null && rec.audio.isSpeaker) SpeakerFallback.setSpeaker(appContext, false)
        applyTelecom(rec, CallStateMapping.disconnectFor(reason))

        if (lastCall) {
            // Release the audio hardware so other apps can use it. A held
            // call left behind keeps the audio session and the service: it
            // stays held until the user resumes it.
            SipEngineHolder.audioBridge?.stop()
            stopActiveCallService()
        }

        val connectedAt = rec.connectedAtElapsed
        val durationSeconds = connectedAt?.let { ((SystemClock.elapsedRealtime() - it) / 1000).toInt() } ?: 0
        recordHistory(rec.remoteUri, rec.remoteName, rec.direction, rec.startedAtMillis, durationSeconds, reason)

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
    )

    private fun publishAll() {
        val states = uiStates()
        _calls.value = states
        _state.value = CallWaiting.foreground(states)
    }
}
