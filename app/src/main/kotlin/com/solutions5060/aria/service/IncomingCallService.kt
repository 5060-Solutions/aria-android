package com.solutions5060.aria.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.solutions5060.aria.AriaApplication
import com.solutions5060.aria.MainActivity
import com.solutions5060.aria.R

/**
 * Foreground service that runs during an incoming or active call.
 *
 * For incoming calls: started from AriaFirebaseMessagingService when an
 * FCM push arrives. Shows a full-screen notification with answer/decline
 * and plays the ringtone. Stops when the call is answered or times out.
 *
 * For active calls: shows a persistent "in call" notification while the
 * call is ongoing.
 */
class IncomingCallService : Service() {

    companion object {
        private const val TAG = "IncomingCallService"
        const val EXTRA_CALL_TOKEN = "call_token"
        const val EXTRA_CALLER_URI = "caller_uri"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_GATEWAY_URL = "gateway_url"
        const val EXTRA_MODE = "mode"
        const val MODE_INCOMING = "incoming"
        const val MODE_ACTIVE = "active"
        const val ACTION_STOP = "com.solutions5060.aria.STOP_RINGING"

        private const val NOTIFICATION_ID = 2001
        private const val TIMEOUT_MS = 30_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop ringing requested")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ACTIVE
        val callerUri = intent?.getStringExtra(EXTRA_CALLER_URI) ?: "Unknown"
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME)
        val callToken = intent?.getStringExtra(EXTRA_CALL_TOKEN) ?: ""

        val notification = if (mode == MODE_INCOMING) {
            Log.i(TAG, "Starting incoming call service: $callerUri")
            buildIncomingNotification(callToken, callerUri, callerName)
        } else {
            Log.i(TAG, "Starting active call service: $callerUri")
            buildActiveNotification(callerName ?: callerUri)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Auto-stop incoming calls after timeout
        if (mode == MODE_INCOMING) {
            android.os.Handler(mainLooper).postDelayed({
                Log.i(TAG, "Incoming call timed out")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }, TIMEOUT_MS)
        }

        return START_NOT_STICKY
    }

    private fun buildIncomingNotification(
        callToken: String,
        callerUri: String,
        callerName: String?,
    ): Notification {
        val displayName = callerName ?: callerUri.removePrefix("sip:").substringBefore("@")

        // Full-screen intent to open the app
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_TOKEN, callToken)
            putExtra(EXTRA_CALLER_URI, callerUri)
            putExtra(EXTRA_CALLER_NAME, callerName)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Decline intent
        val stopIntent = Intent(this, IncomingCallService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        return NotificationCompat.Builder(this, AriaApplication.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Incoming Call")
            .setContentText(displayName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSound(ringtone)
            .setVibrate(longArrayOf(0, 1000, 500, 1000))
            .addAction(R.drawable.ic_phone, "Answer", fullScreenPending)
            .addAction(0, "Decline", stopPending)
            .build()
    }

    private fun buildActiveNotification(callerName: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, AriaApplication.CHANNEL_ACTIVE_CALL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Aria")
            .setContentText("In call with $callerName")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPending)
            .build()
    }
}
