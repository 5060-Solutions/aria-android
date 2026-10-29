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
import uniffi.aria_mobile.SipCredentials
import uniffi.aria_mobile.AriaMobileEngine
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
    /** A blind transfer is waiting on the PBX (up to ~8 s). */
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
 * Owns the lifecycle of the (single) call and keeps three views of it in step:
 * the engine, the Telecom [Connection], and the Compose UI.
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

    /** The call on screen, or null when there is none. */
    val state: StateFlow<CallUiState?> = _state.asStateFlow()

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
        /** makeCall is still running; an Ended now means it is about to throw. */
        var dialInFlight = false
        var endedDuringDial = false
    }

    private var current: CallRecord? = null

    /**
     * An outgoing call hung up before its Ringing event arrived, so before its
     * id was known. Its Ringing still arrives, and is cancelled at the gateway
     * then. Main-thread only.
     */
    private var cancelOnRinging: CallRecord? = null
    private val ringTimeout = Runnable {
        val rec = current ?: return@Runnable
        if (rec.phase != CallPhase.INCOMING || rec.finished) return@Runnable
        finish(rec, EndReason.MISSED)
        // The gateway has given up by now; tell it anyway, so a call it still
        // holds is not left waiting on this device.
        rec.callToken?.takeIf { it.isNotEmpty() }?.let { rejectToken(it) }
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
                val rec = current ?: return@post
                if (rec.callToken == offer.callToken && rec.remoteName.isNullOrBlank() && !offer.callerName.isNullOrBlank()) {
                    rec.remoteName = offer.callerName
                    publish(rec)
                }
            }
        }

        override fun onCallStateChanged(info: CallInfo) {
            mainHandler.post { onEngineCallState(info) }
        }

        override fun onMediaStats(callId: String, stats: MediaStats) {}

        override fun onError(context: String, message: String) {
            Log.w(TAG, "Engine error in $context: $message")
        }

        override fun onAuthRequired() {
            // Fired from the core's background work (status polls, heartbeat)
            // when the gateway rejects the token. Renewal runs elsewhere.
            Log.i(TAG, "Gateway asked for a new token")
            EngineManager.onAuthRequired(appContext)
        }
    }

    private fun onEngineCallState(info: CallInfo) {
        cancelOnRinging?.let { pending ->
            if (info.direction == CallDirection.OUTBOUND && info.state != CallState.ENDED) {
                // The call the user already hung up has just told us its id:
                // cancel it at the gateway now.
                cancelOnRinging = null
                pending.callId = info.callId
                engineExecutor.execute { safeHangup(info.callId) }
                return
            }
        }
        val rec = current ?: return
        if (rec.finished) return
        if (rec.callId == null) {
            // make_call emits Ringing before it returns, which is the first
            // time an outgoing call's id is known.
            if (rec.direction != info.direction || info.state == CallState.ENDED) return
            rec.callId = info.callId
        } else if (rec.callId != info.callId) {
            return
        }

        info.remoteName?.takeIf { it.isNotBlank() }?.let { rec.remoteName = it }
        rec.muted = info.muted
        rec.onHold = info.onHold

        if (info.state == CallState.ENDED) {
            if (rec.dialInFlight && !rec.localHangupRequested) {
                // make_call emits Ended and then throws; wait for the throw so
                // the user sees why and Telecom gets ERROR, not REMOTE.
                rec.endedDuringDial = true
                return
            }
            if (rec.transferring) {
                // transferCall emits Ended before it returns, and its result
                // is posted after this event: let that result say whether
                // this was the transfer or a hangup that raced it.
                rec.endedDuringTransfer = true
                return
            }
            finish(
                rec,
                CallStateMapping.endReasonForEngineEnd(
                    localHangupRequested = rec.localHangupRequested,
                    wasAnswered = rec.connectedAtElapsed != null,
                    direction = rec.direction,
                ),
            )
            return
        }

        if (info.state == CallState.CONNECTED && rec.connectedAtElapsed == null) {
            onConnected(rec)
        }
        CallStateMapping.phaseFor(info.state, info.direction)?.let { rec.phase = it }
        applyTelecom(rec, CallStateMapping.telecomUpdateFor(info.state, info.direction))
        publish(rec)
    }

    private fun onConnected(rec: CallRecord) {
        // CallInfo.duration_secs is not maintained by the core, so the call is
        // timed from here.
        rec.connectedAtElapsed = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(ringTimeout)
        IncomingCallNotifier.cancel(appContext)
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
                    publish(rec)
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
        if (current != null) return "A call is already in progress."
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
        current = rec
        _error.value = null
        publish(rec)

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
        val rec = current
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
        val rec = current ?: return
        if (rec.key != recordKey || rec.finished) return
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
        engineExecutor.execute {
            try {
                // The dialled number is not logged.
                Log.d(TAG, "Placing call")
                val info = dial(rec, engine, uri, credentials)
                mainHandler.post {
                    rec.dialInFlight = false
                    if (cancelOnRinging === rec) cancelOnRinging = null
                    if (rec.finished) {
                        // Cancelled while the INVITE was in flight; the engine
                        // only knows the call now, so hang it up now.
                        engineExecutor.execute { hangupLate(info.callId) }
                    } else if (rec.endedDuringDial) {
                        // Answered and hung up before makeCall returned.
                        finish(rec, EndReason.REMOTE_HANGUP)
                    } else if (rec.callId == null) {
                        rec.callId = info.callId
                    }
                }
            } catch (e: Exception) {
                val reason = CallStateMapping.endReasonForDialFailure(e)
                if (reason == EndReason.FAILED) {
                    Log.e(TAG, "makeCall failed: ${e.message}", e)
                } else {
                    Log.i(TAG, "Outgoing call cancelled before it was answered")
                }
                mainHandler.post {
                    rec.dialInFlight = false
                    if (cancelOnRinging === rec) cancelOnRinging = null
                    if (!rec.finished) {
                        CallStateMapping.dialFailureMessage(e)?.let { _error.value = it }
                        finish(rec, reason)
                    }
                }
            }
        }
    }

    /**
     * `makeCall`, renewing the gateway token and dialling once more if the
     * gateway rejected it. Runs on [engineExecutor].
     */
    private fun dial(rec: CallRecord, engine: AriaMobileEngine, uri: String, credentials: SipCredentials): CallInfo {
        val renewal = EngineManager.tokenRenewal(appContext)
        val seen = renewal.generation
        return try {
            // An empty codec list defers to the engine's preferences.
            engine.makeCall(uri, credentials, emptyList())
        } catch (e: MobileException.AuthenticationException) {
            renewal.renewBlocking(seen) ?: throw e
            // The rejected attempt has reported Ringing and Ended under its own
            // id; the next attempt gets a new one.
            if (!onMain { prepareRedial(rec) }) throw MobileException.Cancelled("Hung up during token renewal")
            engine.makeCall(uri, credentials, emptyList())
        }
    }

    /** Forgets the rejected attempt's id. False when the user hung up meanwhile. */
    private fun prepareRedial(rec: CallRecord): Boolean {
        if (rec.finished) return false
        rec.callId = null
        rec.endedDuringDial = false
        rec.phase = CallPhase.CONNECTING
        publish(rec)
        return true
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
                IncomingCallNotifier.show(appContext, rec.key, rec.displayName(), rec.callToken ?: "")
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
        // Never decline the call already on screen.
        if (token.isNotEmpty() && token == current?.callToken) return
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
        if (token.isNotEmpty() && token == current?.callToken) {
            // The call already on screen (ringing, answered or just ended),
            // reported again: neither a second call nor a busy one.
            Log.i(TAG, "Ignoring a second report of the current call")
            return null
        }
        if (current?.finished == false) {
            // One call at a time: a second caller gets busy and a missed entry.
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
        current = rec
        mainHandler.removeCallbacks(ringTimeout)
        mainHandler.postDelayed(ringTimeout, RING_TIMEOUT_MS)
        publish(rec)
        return rec
    }

    /** Telecom asked us to show our ringing UI (self-managed calls draw their own). */
    fun onShowIncomingCallUi(connection: AriaConnection) {
        val rec = current ?: return
        if (rec.connection !== connection || rec.phase != CallPhase.INCOMING) return
        IncomingCallNotifier.show(appContext, rec.key, rec.displayName(), rec.callToken ?: "")
    }

    /** Answer the ringing call. [callToken], when given, must match it. */
    fun answer(callToken: String? = null) {
        val rec = current ?: return
        if (rec.phase != CallPhase.INCOMING || rec.finished) return
        if (callToken != null && callToken != rec.callToken) return
        val token = rec.callToken ?: return
        mainHandler.removeCallbacks(ringTimeout)
        IncomingCallNotifier.cancel(appContext)
        rec.phase = CallPhase.CONNECTING
        publish(rec)
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
                    if (!rec.finished) SipEngineHolder.audioBridge?.beginCall()
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
                    // Hung up while the accept was in flight: the engine only
                    // holds the call now, so end it now.
                    if (rec.finished) engineExecutor.execute { hangupLate(info.callId) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept call: ${e.message}", e)
                mainHandler.post {
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
        val rec = current ?: return
        if (rec.phase != CallPhase.INCOMING || rec.finished) return
        if (callToken != null && callToken != rec.callToken) return
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

    /** Hang up (or cancel, or decline) whatever call is on screen. */
    fun hangup() {
        val rec = current ?: return
        if (rec.finished) return
        if (rec.phase == CallPhase.INCOMING) {
            reject()
            return
        }
        rec.localHangupRequested = true
        val callId = rec.callId
        if (callId == null || rec.phase == CallPhase.CONNECTING && rec.direction == CallDirection.INBOUND) {
            // Nothing for the engine to hang up yet (the accept is in flight,
            // or makeCall's Ringing, which carries the id, has not arrived):
            // end it here. An outgoing call is cancelled at the gateway when
            // its Ringing arrives; an accepted one is hung up when the accept
            // returns.
            if (rec.direction == CallDirection.OUTBOUND && rec.dialInFlight) cancelOnRinging = rec
            finish(rec, EndReason.LOCAL_HANGUP)
            return
        }
        engineExecutor.execute {
            safeHangup(callId)
            // The engine emits Ended from hangup(); this covers it failing.
            mainHandler.post { finish(rec, EndReason.LOCAL_HANGUP) }
        }
    }

    fun setMuted(muted: Boolean) {
        val rec = current ?: return
        val callId = rec.callId ?: return
        rec.muted = muted
        publish(rec)
        engineExecutor.execute {
            try {
                SipEngineHolder.engine?.let { engine -> EngineManager.withAuthRetry(appContext) { engine.setMute(callId, muted) } }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set mute: $e")
            }
        }
    }

    fun setHold(onHold: Boolean) {
        val rec = current ?: return
        val callId = rec.callId ?: return
        if (rec.connectedAtElapsed == null) return
        engineExecutor.execute {
            try {
                // Emits OnHold / Connected, which updates Telecom and the UI.
                SipEngineHolder.engine?.let { engine -> EngineManager.withAuthRetry(appContext) { engine.setHold(callId, onHold) } }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set hold: $e")
            }
        }
    }

    fun sendDtmf(digit: String) {
        val callId = current?.callId ?: return
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
        val rec = current ?: return
        val callId = rec.callId ?: return
        if (rec.finished || rec.transferring || rec.connectedAtElapsed == null) return
        val dial = CallStateMapping.normalizeTransferTarget(target)
        if (dial.isEmpty()) {
            rec.transferError = "Enter a number to transfer to."
            publish(rec)
            return
        }
        rec.transferring = true
        rec.transferError = null
        publish(rec)

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

    private fun onTransferResult(rec: CallRecord, failure: Exception?) {
        rec.transferring = false
        if (rec.finished) return
        if (failure == null) {
            finish(rec, EndReason.TRANSFERRED)
            return
        }
        if (rec.endedDuringTransfer) {
            // The far end hung up while the transfer was being attempted.
            finish(rec, EndReason.REMOTE_HANGUP)
            return
        }
        rec.transferError = CallStateMapping.transferErrorMessage(failure)
        publish(rec)
    }

    fun clearTransferError() {
        val rec = current ?: return
        if (rec.transferError == null) return
        rec.transferError = null
        publish(rec)
    }

    // ── Audio routing ──────────────────────────────────────────────────

    /** Telecom changed the available or active audio route. */
    fun onAudioRoutesChanged(connection: AriaConnection, routes: AudioRouteState) {
        val rec = current ?: return
        if (rec.connection !== connection) return
        rec.audio = routes
        publish(rec)
    }

    /** Moves call audio to [option]. */
    fun selectAudioRoute(option: AudioRouteOption) {
        val rec = current ?: return
        val connection = rec.connection
        if (connection != null) {
            connection.requestRoute(option)
            return
        }
        // No Telecom connection (the account was refused): route directly.
        val speaker = option.type == AudioRouteType.SPEAKER
        SpeakerFallback.setSpeaker(appContext, speaker)
        rec.audio = SpeakerFallback.routes(speaker)
        publish(rec)
    }

    // ── Teardown ────────────────────────────────────────────────────────

    /**
     * Hangs up a call that was already finished here before the engine took it
     * on, and closes the audio its media may have opened in the meantime.
     */
    private fun hangupLate(callId: String) {
        safeHangup(callId)
        mainHandler.post {
            // Unless a new call has started since, which owns the audio now.
            if (current?.finished != false) SipEngineHolder.audioBridge?.stop()
        }
    }

    private fun safeHangup(callId: String) {
        try {
            SipEngineHolder.engine?.hangup(callId)
        } catch (e: MobileException.AuthenticationException) {
            // The core has already ended the call locally and forgotten it, so
            // retrying would reach nothing. Renew the token so its background
            // fallback hangup, and every later request, can get through.
            Log.w(TAG, "Hangup rejected by the gateway; renewing the token")
            EngineManager.onAuthRequired(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Hangup failed: ${e.message}")
        }
    }

    private fun finish(rec: CallRecord, reason: EndReason) {
        if (rec.finished) return
        rec.finished = true
        Log.i(TAG, "Call ${rec.key} ended: $reason")

        mainHandler.removeCallbacks(ringTimeout)
        IncomingCallNotifier.cancel(appContext)
        // Telecom resets its own routing; only the no-Telecom fallback needs undoing.
        if (rec.connection == null && rec.audio.isSpeaker) SpeakerFallback.setSpeaker(appContext, false)
        applyTelecom(rec, CallStateMapping.disconnectFor(reason))

        // Release the audio hardware so other apps can use it.
        SipEngineHolder.audioBridge?.stop()
        stopActiveCallService()

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
        publish(rec)
        mainHandler.postDelayed({
            if (current === rec) {
                current = null
                _state.value = null
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

    private fun publish(rec: CallRecord) {
        if (current === rec) _state.value = rec.toUiState()
    }
}
