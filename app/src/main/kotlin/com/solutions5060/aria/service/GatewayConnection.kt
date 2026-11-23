package com.solutions5060.aria.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.solutions5060.aria.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** What the status chip shows. Always shown with a text label, never color alone. */
enum class ConnectionStatus { SIGNED_OUT, OFFLINE, CONNECTING, REGISTERED, ERROR }

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.CONNECTING,
    /** Why the last attempt failed, for the connection sheet. */
    val error: String? = null,
    /** Whether Android reports a default network. */
    val online: Boolean = true,
    val gatewayUrl: String? = null,
    val deviceId: String? = null,
    /** [SystemClock.elapsedRealtime] of the last successful registration. */
    val registeredAtElapsed: Long? = null,
)

/** Everything that moves [ConnectionState]. */
sealed interface ConnectionEvent {
    data object Connecting : ConnectionEvent
    data class Registered(val gatewayUrl: String?, val deviceId: String?, val atElapsed: Long) : ConnectionEvent
    data class Failed(val message: String) : ConnectionEvent
    data object NotProvisioned : ConnectionEvent
    data class Online(val online: Boolean) : ConnectionEvent
    /** The core's `on_registration_changed`. */
    data class CoreRegistration(val status: String, val error: String?) : ConnectionEvent
    data object SignedOut : ConnectionEvent
}

/** Pure state transitions, unit-tested. */
object ConnectionReducer {
    fun reduce(s: ConnectionState, e: ConnectionEvent): ConnectionState = when (e) {
        ConnectionEvent.Connecting ->
            if (s.online) s.copy(status = ConnectionStatus.CONNECTING, error = null) else s
        is ConnectionEvent.Registered -> s.copy(
            status = ConnectionStatus.REGISTERED, error = null,
            gatewayUrl = e.gatewayUrl ?: s.gatewayUrl, deviceId = e.deviceId ?: s.deviceId,
            registeredAtElapsed = e.atElapsed,
        )
        is ConnectionEvent.Failed ->
            // Offline explains the failure better than its exception does.
            if (!s.online) s.copy(status = ConnectionStatus.OFFLINE, error = e.message)
            else s.copy(status = ConnectionStatus.ERROR, error = e.message)
        ConnectionEvent.NotProvisioned -> ConnectionState(status = ConnectionStatus.SIGNED_OUT, online = s.online)
        is ConnectionEvent.Online -> when {
            s.status == ConnectionStatus.SIGNED_OUT -> s.copy(online = e.online)
            !e.online -> s.copy(online = false, status = ConnectionStatus.OFFLINE)
            // Back online: a reconnect follows and reports the outcome.
            s.status == ConnectionStatus.OFFLINE -> s.copy(online = true, status = ConnectionStatus.CONNECTING)
            else -> s.copy(online = true)
        }
        is ConnectionEvent.CoreRegistration -> when (e.status) {
            "registered" -> s.copy(status = ConnectionStatus.REGISTERED, error = null)
            "unregistered" -> s.copy(status = ConnectionStatus.OFFLINE, error = e.error)
            else -> if (e.error != null) s.copy(status = ConnectionStatus.ERROR, error = e.error) else s
        }
        ConnectionEvent.SignedOut -> ConnectionState(status = ConnectionStatus.SIGNED_OUT, online = s.online)
    }
}

/**
 * The app's connection to the push gateway, for the status chip and the
 * connection sheet, and the one place that (re)builds the engine:
 *
 * - at launch and whenever the app returns to the foreground ([onForeground]),
 * - when the network comes back or changes ([onNetworkChanged]: the core is
 *   told, and an engine that could not be built offline is built now),
 * - when the user taps Reconnect ([reconnect]).
 *
 * Incoming calls do not depend on any of this: the gateway sends them as
 * high-priority FCM data messages, which AriaFirebaseMessagingService turns
 * into a Telecom call whatever this state says, building the engine itself
 * if needed. The app holds no long-lived socket to the gateway.
 */
object GatewayConnection {

    private const val TAG = "GatewayConnection"

    /** A foreground return re-checks registration at most this often. */
    private const val FOREGROUND_RECHECK_MS = 60_000L

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val inFlight = AtomicBoolean(false)

    fun dispatch(event: ConnectionEvent) = _state.update { ConnectionReducer.reduce(it, event) }

    /**
     * Builds the engine from the saved account if there is none, or (with
     * [verify]) re-registers the running one, reporting the outcome. One
     * attempt at a time; a request while one runs is dropped.
     */
    fun connect(context: Context, verify: Boolean = false, fresh: Boolean = false) {
        if (!inFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        dispatch(ConnectionEvent.Connecting)
        AppScope.scope.launch {
            try {
                if (EngineManager.savedCredentials(appContext) == null) {
                    dispatch(ConnectionEvent.NotProvisioned)
                    return@launch
                }
                val engine = when {
                    fresh -> EngineManager.login(appContext)
                    SipEngineHolder.engine != null && verify -> EngineManager.refreshRegistration(appContext)
                    else -> EngineManager.ensureEngine(appContext)
                }
                if (engine == null) {
                    dispatch(ConnectionEvent.Failed("No gateway credentials"))
                } else {
                    dispatch(
                        ConnectionEvent.Registered(
                            gatewayUrl = EngineManager.gatewayUrl(appContext),
                            deviceId = EngineManager.deviceId(appContext),
                            atElapsed = SystemClock.elapsedRealtime(),
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connect failed: ${e.javaClass.simpleName}")
                dispatch(ConnectionEvent.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** The user asked to reconnect: log in afresh and replace the engine. */
    fun reconnect(context: Context) = connect(context, fresh = true)

    /** The app came to the foreground. */
    fun onForeground(context: Context) {
        val s = _state.value
        val last = s.registeredAtElapsed
        val stale = last == null || SystemClock.elapsedRealtime() - last > FOREGROUND_RECHECK_MS
        when {
            SipEngineHolder.engine == null -> connect(context)
            s.status != ConnectionStatus.REGISTERED || stale -> connect(context, verify = true)
        }
    }

    /** The default network changed (debounced by [NetworkChangeDetector]). */
    fun onNetworkChanged(context: Context) {
        val engine = SipEngineHolder.engine
        if (engine == null) {
            // Started offline, or the last attempt failed: try now.
            connect(context)
            return
        }
        try {
            // Returns at once; the core coalesces the heartbeats it sends.
            engine.notifyNetworkChange()
        } catch (e: Exception) {
            Log.w(TAG, "notifyNetworkChange failed: ${e.javaClass.simpleName}")
        }
        // The heartbeat's outcome is not reported by the core, so confirm the
        // registration explicitly after a change.
        connect(context, verify = true)
    }

    fun onConnectivity(context: Context, online: Boolean) {
        dispatch(ConnectionEvent.Online(online))
        if (online && SipEngineHolder.engine == null) connect(context)
    }

    fun onCoreRegistration(status: String, error: String?) =
        dispatch(ConnectionEvent.CoreRegistration(status, error))

    fun onSignedOut() = dispatch(ConnectionEvent.SignedOut)
}
