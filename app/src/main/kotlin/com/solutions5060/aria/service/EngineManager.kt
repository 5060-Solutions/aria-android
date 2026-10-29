package com.solutions5060.aria.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.provisioning.DEFAULT_SIP_TRANSPORT
import com.solutions5060.aria.provisioning.defaultSipPort
import com.solutions5060.aria.security.SecurePrefs
import kotlinx.coroutines.launch
import uniffi.aria_mobile.AriaMobileEngine
import uniffi.aria_mobile.DeviceRegistration
import uniffi.aria_mobile.GatewayConfig
import uniffi.aria_mobile.MobileException
import uniffi.aria_mobile.SipCredentials
import java.util.concurrent.TimeUnit

/**
 * Builds the SIP engine from the saved account and keeps it registered.
 *
 * Several entry points need a working engine — the UI on launch, the settings
 * screen after a login, and the FCM service when a push or a token refresh
 * arrives in a process the UI never started. They all come through here so the
 * engine is configured one way, from one copy of the saved credentials.
 *
 * Everything here blocks on the network and must run off the main thread.
 */
object EngineManager {

    private const val TAG = "EngineManager"
    const val PREFS_NAME = "aria_prefs"
    private const val DEFAULT_GATEWAY_URL = "https://push.ariaroute.com"
    private const val BUNDLE_ID = "com.solutions5060.aria"
    private const val PLATFORM = "android"
    private const val PUSH_TOKEN_TIMEOUT_SECS = 10L

    /** Last FCM token seen, kept so a cold start can register without waiting on Firebase. */
    private const val KEY_FCM_TOKEN = "fcm_token"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The saved SIP account, or null when the app has not been provisioned. */
    fun savedCredentials(context: Context): SipCredentials? {
        val prefs = prefs(context)
        val username = prefs.getString("sip_username", "") ?: ""
        val domain = prefs.getString("sip_domain", "") ?: ""
        if (username.isEmpty() || domain.isEmpty()) return null
        // An account saved without a transport gets TLS, never cleartext UDP.
        val transport = prefs.getString("sip_transport", null)?.ifEmpty { null } ?: DEFAULT_SIP_TRANSPORT
        return SipCredentials(
            username = username,
            password = SecurePrefs.getString(context, "sip_password", "") ?: "",
            domain = domain,
            registrar = (prefs.getString("sip_registrar", "") ?: "").ifEmpty { null },
            transport = transport,
            port = prefs.getString("sip_port", null)?.toUShortOrNull() ?: defaultSipPort(transport).toUShort(),
            authUsername = null,
            displayName = prefs.getString("sip_display_name", "") ?: "",
        )
    }

    /** Records the latest FCM token so later registrations use it. */
    fun rememberPushToken(context: Context, token: String) {
        if (token.isNotEmpty()) SecurePrefs.putString(context, KEY_FCM_TOKEN, token)
    }

    /**
     * The current FCM token: asked of Firebase first, falling back to the last
     * one seen. Empty when neither is available — registration still succeeds,
     * the device just cannot receive call pushes until a token arrives.
     */
    fun currentPushToken(context: Context): String {
        val fresh = try {
            Tasks.await(FirebaseMessaging.getInstance().token, PUSH_TOKEN_TIMEOUT_SECS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not get FCM token: ${e.message}")
            null
        }
        if (!fresh.isNullOrEmpty()) {
            rememberPushToken(context, fresh)
            return fresh
        }
        return SecurePrefs.getString(context, KEY_FCM_TOKEN, "") ?: ""
    }

    /**
     * Returns the running engine, building and registering one from the saved
     * account if there is none. Null when the app is not provisioned or the
     * gateway could not be reached.
     *
     * [pushToken] overrides the token to register with (used when Firebase has
     * just handed us a new one).
     */
    @Synchronized
    fun ensureEngine(context: Context, pushToken: String? = null): AriaMobileEngine? {
        SipEngineHolder.engine?.let { return it }
        val appContext = context.applicationContext
        val credentials = savedCredentials(appContext) ?: return null
        val token = pushToken ?: currentPushToken(appContext)

        val prefs = prefs(appContext)
        var gatewayUrl = prefs.getString("gateway_url", null) ?: ""
        var gatewayToken = SecurePrefs.getString(appContext, "gateway_token", null) ?: ""
        val apiUrl = prefs.getString("api_url", "") ?: ""

        // Without a gateway token there is nothing to authenticate the engine
        // with, so log in to the PBX first to obtain one.
        if (gatewayToken.isEmpty() && apiUrl.isNotEmpty()) {
            Log.d(TAG, "No gateway token; performing full login")
            val gateway = pbxLogin(appContext, credentials, token)
            gatewayUrl = gateway.first
            gatewayToken = gateway.second
        }
        if (gatewayToken.isEmpty()) {
            Log.w(TAG, "No gateway credentials; engine not started")
            return null
        }

        // Registration may have changed the SIP transport, so read the account again.
        val account = savedCredentials(appContext) ?: return null
        return startEngine(appContext, gatewayUrl, gatewayToken, account, token)
    }

    /**
     * Logs in to the PBX afresh and replaces any running engine. Used by the
     * settings screen's Connect button and after provisioning.
     */
    @Synchronized
    fun login(context: Context, pushToken: String? = null): AriaMobileEngine {
        val appContext = context.applicationContext
        val credentials = savedCredentials(appContext)
            ?: throw IllegalStateException("Missing required fields. Scan a QR code to configure.")
        val token = pushToken ?: currentPushToken(appContext)
        val (gatewayUrl, gatewayToken) = pbxLogin(appContext, credentials, token)
        // Registration may have changed the SIP transport, so read the account again.
        val account = savedCredentials(appContext) ?: credentials
        return startEngine(appContext, gatewayUrl, gatewayToken, account, token)
    }

    /**
     * Forwards a rotated FCM token to the gateway. Without this the gateway
     * keeps pushing to the dead token and incoming calls stop arriving.
     */
    fun updatePushToken(context: Context, token: String) {
        val appContext = context.applicationContext
        rememberPushToken(appContext, token)
        // The PBX keeps its own copy of the token alongside the device.
        updatePbxPushToken(appContext, token)

        val running = SipEngineHolder.engine
        if (running == null) {
            // No engine in this process yet: building one registers with the
            // new token, which is the update.
            if (ensureEngine(appContext, token) != null) {
                Log.i(TAG, "Engine started with refreshed push token")
            }
            return
        }

        val deviceId = prefs(appContext).getString("device_id", null) ?: ""
        try {
            withAuthRetry(appContext) { running.updatePushToken(deviceId, token) }
            Log.i(TAG, "Push token updated with gateway")
        } catch (e: Exception) {
            // The engine re-registers from its last registration; if it never
            // completed one, register from the saved account instead.
            Log.w(TAG, "updatePushToken failed (${e.message}); re-registering")
            val credentials = savedCredentials(appContext) ?: return
            withAuthRetry(appContext) { running.registerDevice(registration(credentials, token)) }
        }
    }

    /**
     * `PUT /api/v1/devices/{id}/push-token`. A conflict (another device holds
     * the token) or any other failure is logged and does not stop the gateway
     * update; an expired session is renewed once.
     */
    private fun updatePbxPushToken(context: Context, token: String) {
        val session = PbxSession(context)
        val apiUrl = session.apiUrl()
        val deviceId = session.deviceId()
        if (apiUrl.isEmpty() || deviceId.isNullOrEmpty()) return
        val client = PbxApiClient(apiUrl)
        try {
            try {
                client.updatePushToken(session.sessionJwt() ?: session.login(), deviceId, token)
            } catch (e: PbxApiClient.HttpException) {
                if (e.code != 401) throw e
                client.updatePushToken(session.login(), deviceId, token)
            }
            Log.i(TAG, "Push token updated with PBX")
        } catch (e: PbxApiClient.HttpException) {
            if (e.code == 409) {
                Log.w(TAG, "PBX push token update refused: another device holds this token")
            } else {
                Log.w(TAG, "PBX push token update failed (${e.code})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "PBX push token update failed: ${e.message}")
        }
    }

    // ── Gateway token renewal ──────────────────────────────────────────

    @Volatile
    private var renewal: GatewayTokenRenewal? = null
    private val renewalLock = Any()

    /** The one renewal coordinator for this process. */
    fun tokenRenewal(context: Context): GatewayTokenRenewal {
        renewal?.let { return it }
        synchronized(renewalLock) {
            renewal?.let { return it }
            val appContext = context.applicationContext
            return GatewayTokenRenewal(PbxSession(appContext), install = { token ->
                SecurePrefs.putString(appContext, "gateway_token", token)
                try {
                    SipEngineHolder.engine?.setGatewayToken(token)
                } catch (e: Exception) {
                    Log.w(TAG, "setGatewayToken failed: ${e.message}")
                }
            }).also { renewal = it }
        }
    }

    /**
     * Runs an engine call, renewing the gateway token and retrying once if
     * the gateway rejected it. Blocks; never call it on the main thread.
     */
    fun <T> withAuthRetry(context: Context, call: () -> T): T =
        tokenRenewal(context).retryOnAuthFailure(call)

    /** The core's `on_auth_required`: renew in the background, off the core's thread. */
    fun onAuthRequired(context: Context) {
        val renewal = tokenRenewal(context)
        // Read before launching, so a renewal that lands first is not repeated.
        val seen = renewal.generation
        AppScope.scope.launch { renewal.renew(seen) }
    }

    /** The saved PBX session, for renewal and the per-device routes. */
    private class PbxSession(private val context: Context) : GatewayTokenRenewal.Pbx {
        fun apiUrl(): String = prefs(context).getString("api_url", "") ?: ""

        override fun sessionJwt(): String? = SecurePrefs.getString(context, "jwt", null)?.ifEmpty { null }

        override fun deviceId(): String? = prefs(context).getString("device_id", null)?.ifEmpty { null }

        override fun requestGatewayToken(jwt: String, deviceId: String): String =
            PbxApiClient(apiUrl()).renewGatewayToken(jwt, deviceId)

        override fun login(): String {
            val credentials = savedCredentials(context)
                ?: throw IllegalStateException("No saved account to log in with")
            val apiUrl = apiUrl()
            if (apiUrl.isEmpty() || credentials.password.isEmpty()) {
                throw IllegalStateException("No saved account to log in with")
            }
            val tenantDomain = prefs(context).getString("tenant_domain", "") ?: ""
            val login = PbxApiClient(apiUrl).extensionLogin(credentials.username, credentials.password, tenantDomain)
            saveSession(context, login)
            return login.jwt
        }
    }

    private fun saveSession(context: Context, login: PbxApiClient.LoginResult) {
        prefs(context).edit()
            .putString("extension_id", login.extensionId)
            .putString("tenant_id", login.tenantId)
            .apply()
        // The JWT is a bearer credential: store it encrypted.
        SecurePrefs.putString(context, "jwt", login.jwt)
    }

    /** Drops the running engine (sign-out). */
    @Synchronized
    fun reset() {
        SipEngineHolder.audioBridge?.stop()
        SipEngineHolder.engine = null
        SipEngineHolder.audioBridge = null
    }

    /**
     * PBX extension login plus device registration. Persists the results and
     * returns the gateway URL and token the engine authenticates with.
     */
    private fun pbxLogin(
        context: Context,
        credentials: SipCredentials,
        pushToken: String,
    ): Pair<String, String> {
        val prefs = prefs(context)
        val apiUrl = prefs.getString("api_url", "") ?: ""
        if (apiUrl.isEmpty() || credentials.password.isEmpty()) {
            throw IllegalStateException("Missing required fields. Scan a QR code to configure.")
        }
        val tenantDomain = prefs.getString("tenant_domain", "") ?: ""

        val client = PbxApiClient(apiUrl)
        val login = client.extensionLogin(credentials.username, credentials.password, tenantDomain)
        saveSession(context, login)

        var gatewayUrl = prefs.getString("gateway_url", null)?.ifEmpty { null } ?: DEFAULT_GATEWAY_URL
        var gatewayToken = ""
        val deviceId = try {
            val device = client.registerDevice(jwt = login.jwt, pushToken = pushToken, platform = PLATFORM)
            gatewayUrl = device.gatewayUrl.ifEmpty { DEFAULT_GATEWAY_URL }
            gatewayToken = device.gatewayToken
            device.sipTransport?.let { transport ->
                prefs.edit()
                    .putString("sip_transport", transport)
                    .putString("sip_port", (device.sipPort ?: defaultSipPort(transport)).toString())
                    .apply()
            }
            device.deviceId
        } catch (e: PbxApiClient.HttpException) {
            // The PBX stored the device but could not get a gateway token
            // (gateway down or not configured). Ask for one for that device.
            val storedId = e.deviceId
            if ((e.code != 502 && e.code != 503) || storedId == null) throw e
            Log.w(TAG, "Registration got no gateway token (${e.code}); renewing for the stored device")
            storedId
        }
        prefs.edit()
            .putString("device_id", deviceId)
            .putString("gateway_url", gatewayUrl)
            .apply()

        // The session JWT is not a gateway token: the gateway rejects it, so
        // never fall back to it.
        if (gatewayToken.isEmpty()) gatewayToken = client.renewGatewayToken(login.jwt, deviceId)
        // A bearer credential: store encrypted.
        SecurePrefs.putString(context, "gateway_token", gatewayToken)
        // Do not log either token, even a prefix.
        Log.d(TAG, "PBX login completed, gatewayUrl=$gatewayUrl")
        return gatewayUrl to gatewayToken
    }

    private fun startEngine(
        context: Context,
        gatewayUrl: String,
        gatewayToken: String,
        credentials: SipCredentials,
        pushToken: String,
    ): AriaMobileEngine {
        val engine = AriaMobileEngine(
            GatewayConfig(
                baseUrl = gatewayUrl.ifEmpty { DEFAULT_GATEWAY_URL },
                apiKey = gatewayToken,
            )
        )
        val bridge = AudioBridge(context)
        engine.setAudioBridge(bridge)
        // Call state reaches Telecom and the UI only through these events.
        engine.setEventHandler(CallManager.eventHandler)
        val renewal = tokenRenewal(context)
        val seen = renewal.generation
        try {
            engine.registerDevice(registration(credentials, pushToken))
        } catch (e: MobileException.AuthenticationException) {
            // The saved token has expired. This engine is not installed yet,
            // so give it the new token directly.
            val fresh = renewal.renewBlocking(seen) ?: throw e
            engine.setGatewayToken(fresh)
            engine.registerDevice(registration(credentials, pushToken))
        }

        SipEngineHolder.audioBridge?.stop()
        SipEngineHolder.engine = engine
        SipEngineHolder.audioBridge = bridge
        Log.d(TAG, "Engine initialized")
        return engine
    }

    private fun registration(credentials: SipCredentials, pushToken: String) =
        DeviceRegistration(
            platform = PLATFORM,
            pushToken = pushToken,
            bundleId = BUNDLE_ID,
            sip = credentials,
        )
}
