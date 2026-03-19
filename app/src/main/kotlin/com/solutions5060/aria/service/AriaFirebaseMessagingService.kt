package com.solutions5060.aria.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.solutions5060.aria.AriaApplication
import com.solutions5060.aria.MainActivity
import com.solutions5060.aria.R
import uniffi.aria_mobile.PushCallPayload

/**
 * Firebase Cloud Messaging service for receiving push notifications.
 *
 * When a VoIP push arrives, reports the call to the Telecom framework
 * via ConnectionService (Android's equivalent of CallKit).
 */
class AriaFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AriaFCM"
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token refreshed: ${token.take(10)}...")

        // Store the token and update the gateway if registered
        // SharedPreferences or DataStore would be used in production
        SipEngineHolder.engine?.let { engine ->
            // Re-register with new token
            Log.i(TAG, "Updating push token with gateway")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(TAG, "FCM message received: ${message.data}")

        val data = message.data

        // Check if this is a VoIP call notification
        val callToken = data["call_token"] ?: return
        val callerUri = data["caller_uri"] ?: "Unknown"
        val callerName = data["caller_name"]
        val gatewayUrl = data["gateway_url"] ?: return

        Log.i(TAG, "Incoming call from $callerUri (token: ${callToken.take(8)})")

        // Report incoming call via Telecom ConnectionService
        reportIncomingCall(callToken, callerUri, callerName)

        // Also fetch the full call offer from the gateway
        Thread {
            try {
                val payload = PushCallPayload(
                    callToken = callToken,
                    callerUri = callerUri,
                    callerName = callerName,
                    gatewayUrl = gatewayUrl
                )
                SipEngineHolder.engine?.handlePushNotification(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle push notification: $e")
            }
        }.start()
    }

    private fun reportIncomingCall(callToken: String, callerUri: String, callerName: String?) {
        val telecomManager = getSystemService(TelecomManager::class.java)

        // Register phone account if needed
        val componentName = ComponentName(this, AriaConnectionService::class.java)
        val phoneAccountHandle = PhoneAccountHandle(componentName, "aria_voip")

        val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Aria VoIP")
            .setCapabilities(
                PhoneAccount.CAPABILITY_SELF_MANAGED or
                PhoneAccount.CAPABILITY_CALL_PROVIDER
            )
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)

        // Add incoming call
        val extras = Bundle().apply {
            putString("call_token", callToken)
            putString("caller_uri", callerUri)
            putString("caller_name", callerName)
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts("sip", callerUri, null)
            )
        }

        try {
            telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot report incoming call (missing permission): $e")
            // Fallback: show notification
            showIncomingCallNotification(callToken, callerUri, callerName)
        }
    }

    private fun showIncomingCallNotification(
        callToken: String,
        callerUri: String,
        callerName: String?
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("call_token", callToken)
            putExtra("caller_uri", callerUri)
            putExtra("caller_name", callerName)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, AriaApplication.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Incoming Call")
            .setContentText(callerName ?: callerUri)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_phone,
                "Answer",
                pendingIntent
            )
            .build()

        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(1001, notification)
    }
}
