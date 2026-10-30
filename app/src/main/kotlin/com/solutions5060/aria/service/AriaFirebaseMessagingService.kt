package com.solutions5060.aria.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.call.CallManager
import kotlinx.coroutines.launch
import uniffi.aria_mobile.PushCallPayload

/**
 * Firebase Cloud Messaging service for receiving push notifications.
 *
 * When a VoIP push arrives, reports the call to the Telecom framework
 * via ConnectionService (Android's equivalent of CallKit). That is the only
 * path to an incoming-call UI.
 */
class AriaFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AriaFCM"
    }

    override fun onNewToken(token: String) {
        // Do not log the token: it is what the gateway pushes calls to.
        Log.i(TAG, "FCM token refreshed")

        // Remember it now, so a registration that starts before the update
        // below finishes still uses the new token.
        val context = applicationContext
        EngineManager.rememberPushToken(context, token)

        // The PBX and gateway round-trips (and any token renewal) can outlast
        // this service, which Android may stop once this returns, so they run
        // in the process-wide scope. If the app was never provisioned the
        // token is only remembered for the eventual first registration.
        AppScope.scope.launch {
            try {
                EngineManager.updatePushToken(context, token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update push token: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Do not log message.data — it carries the call token and other secrets.
        Log.i(TAG, "FCM message received (${message.data.size} data keys)")

        val data = message.data

        // Check if this is a VoIP call notification
        // Gateway sends camelCase keys
        val callToken = data["callToken"] ?: data["call_token"] ?: return
        val callerUri = data["callerUri"] ?: data["caller_uri"] ?: "Unknown"
        val callerName = data["callerName"] ?: data["caller_name"]
        val gatewayUrl = data["gatewayUrl"] ?: data["gateway_url"] ?: "https://push.ariaroute.com"

        // Record the token as coming from a genuine push so the exported
        // MainActivity will accept it (and reject any injected/fabricated token).
        IncomingCallService.expectCallToken(callToken)

        // Neither the token nor the caller is logged.
        Log.i(TAG, "Incoming call push")

        // Ring first; building the engine below can take a network round-trip.
        // A repeated push for a call already handled is dropped entirely.
        if (!CallManager.reportIncomingCall(applicationContext, callToken, callerUri, callerName)) return

        // Fetch the offer so the engine knows the call: it is what lets the
        // engine report a caller who hangs up before we answer. This runs on
        // Firebase's background thread, so blocking here is fine.
        try {
            val engine = EngineManager.ensureEngine(applicationContext) ?: return
            val payload = PushCallPayload(
                callToken = callToken,
                callerUri = callerUri,
                callerName = callerName,
                gatewayUrl = gatewayUrl,
            )
            EngineManager.withAuthRetry(applicationContext) { engine.handlePushNotification(payload) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle push notification: $e")
        }
    }
}
