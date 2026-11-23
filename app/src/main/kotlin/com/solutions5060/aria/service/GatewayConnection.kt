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
    /** The core is recovering from a network change or a refused token; calls continue. */
    val recovering: Boolean = false,
    /** The gateway instance holding the registration (`current_gateway()`, "host:port"). */
    val gatewayNode: String? = null,
)

/** Everything that moves [ConnectionState]. */
sealed interface ConnectionEvent {
    data object Connecting : ConnectionEvent
    data class Registered(
        val gatewayUrl: String?,
        val deviceId: String?,
        val atElapsed: Long,
        val gatewayNode: String? = null,
    ) : ConnectionEvent
    data class Failed(val message: String) : ConnectionEvent
    data object NotProvisioned : ConnectionEvent
    data class Online(val online: Boolean) : ConnectionEvent
    /** The core's `on_registration_changed`; [gatewayNode] is `current_gateway()` when it fired. */
    data class CoreRegistration(
        val status: String,
        val error: String?,
        val deviceId: String? = null,
        val gatewayNode: String? = null,
    ) : ConnectionEvent
    data object SignedOut : ConnectionEvent
}

/** Pure state transitions, unit-tested. */
object ConnectionReducer {
    fun reduce(s: ConnectionState, e: ConnectionEvent): ConnectionState = when (e) {
        ConnectionEvent.Connecting ->
            if (s.online) s.copy(status = ConnectionStatus.CONNECTING, error = null, recovering = false) else s
        is ConnectionEvent.Registered -> s.copy(
            status = ConnectionStatus.REGISTERED, error = null, recovering = false,
            gatewayUrl = e.gatewayUrl ?: s.gatewayUrl, deviceId = e.deviceId ?: s.deviceId,
            registeredAtElapsed = e.atElapsed, gatewayNode = e.gatewayNode ?: s.gatewayNode,
        )
        is ConnectionEvent.Failed ->
            // Offline explains the failure better than its exception does.
            if (!s.online) s.copy(status = ConnectionStatus.OFFLINE, error = e.message, recovering = false)
            else s.copy(status = ConnectionStatus.ERROR, error = e.message, recovering = false)
        ConnectionEvent.NotProvisioned -> ConnectionState(status = ConnectionStatus.SIGNED_OUT, online = s.online)
        is ConnectionEvent.Online -> when {
            s.status == ConnectionStatus.SIGNED_OUT -> s.copy(online = e.online)
            !e.online -> s.copy(online = false, status = ConnectionStatus.OFFLINE)
            // Back online: a reconnect follows and reports the outcome.
            s.status == ConnectionStatus.OFFLINE -> s.copy(online = true, status = ConnectionStatus.CONNECTING)
            else -> s.copy(online = true)
        }
        is ConnectionEvent.CoreRegistration -> when (e.status) {
            // After register_device, a recovery, or a move to another gateway
            // instance: the device id may be new.
            "registered" -> s.copy(
                status = ConnectionStatus.REGISTERED, error = null, recovering = false,
                deviceId = e.deviceId?.ifBlank { null } ?: s.deviceId,
                gatewayNode = e.gatewayNode ?: s.gatewayNode,
            )
            // A network change is being recovered from; live calls continue.
            "recovering" -> s.copy(status = ConnectionStatus.CONNECTING, error = null, recovering = true)
            // on_auth_required has fired and a new token is on its way; the
            // core resumes by itself after set_gateway_token.
            "auth_required" -> s.copy(status = ConnectionStatus.CONNECTING, error = null, recovering = true)
            // Incoming calls may not arrive until the next network change.
            "failed" -> s.copy(
                status = if (s.online) ConnectionStatus.ERROR else ConnectionStatus.OFFLINE,
                error = e.error ?: "Registration failed",
                recovering = false,
            )
            "unregistered" -> s.copy(status = ConnectionStatus.OFFLINE, error = e.error, recovering = false)
            else -> s
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

    private val _state = MutableStateFlow(ConnectionState())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val inFlight = AtomicBoolean(false)

    fun dispatch(event: ConnectionEvent) = _state.update { ConnectionReducer.reduce(it, event) }

    /**
     * Builds the engine from the saved account if there is none (or, with
     * [fresh], logs in again and replaces it), reporting the outcome. One
     * attempt at a time; a request while one runs is dropped. A running
     * engine reports its own recovery through `on_registration_changed`.
     */
    fun connect(context: Context, fresh: Boolean = false) {
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

    /**
     * The app came to the foreground: build the engine if there is none,
     * otherwise tell the core, which checks the path (the network may have
     * changed while the app was in the background) and reports the outcome.
     */
    fun onForeground(context: Context) {
        val engine = SipEngineHolder.engine
        if (engine == null) connect(context) else notifyCore(engine)
    }

    /** The default network changed (debounced by [NetworkChangeDetector]). */
    fun onNetworkChanged(context: Context) {
        val engine = SipEngineHolder.engine
        // Started offline, or the last attempt failed: try now.
        if (engine == null) connect(context) else notifyCore(engine)
    }

    /**
     * `notify_network_change`: returns at once; live calls move their media
     * by themselves and are never ended by it; progress arrives as
     * `recovering`, then `registered`, `failed` or `auth_required`.
     */
    private fun notifyCore(engine: uniffi.aria_mobile.AriaMobileEngine) {
        try {
            engine.notifyNetworkChange()
        } catch (e: Exception) {
            Log.w(TAG, "notifyNetworkChange failed: ${e.javaClass.simpleName}")
        }
    }

    fun onConnectivity(context: Context, online: Boolean) {
        dispatch(ConnectionEvent.Online(online))
        if (online && SipEngineHolder.engine == null) connect(context)
    }

    /**
     * The core's `on_registration_changed`. Keeps the latest gateway device
     * id, and makes sure a refused token is being renewed.
     */
    fun onCoreRegistration(context: Context, deviceId: String, status: String, error: String?) {
        if (status == "registered") GatewayDeviceIds.of(context).onRegistered(deviceId)
        if (status == "auth_required") EngineManager.onAuthRequired(context)
        val node = if (status == "registered") {
            try {
                SipEngineHolder.engine?.currentGateway()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        dispatch(ConnectionEvent.CoreRegistration(status, error, deviceId, node))
    }

    fun onSignedOut() = dispatch(ConnectionEvent.SignedOut)
}
