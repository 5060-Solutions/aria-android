package com.solutions5060.aria.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.solutions5060.aria.AriaApplication
import com.solutions5060.aria.MainActivity
import com.solutions5060.aria.R

/**
 * Foreground service that runs while a call is connected, keeping the process
 * and microphone access alive with a persistent "in call" notification.
 *
 * Ringing is not done here: an incoming call is reported to Telecom, and the
 * connection's `onShowIncomingCallUi` posts the one incoming-call
 * notification (see [com.solutions5060.aria.call.IncomingCallNotifier]).
 */
class IncomingCallService : Service() {

    companion object {
        private const val TAG = "IncomingCallService"
        const val EXTRA_CALL_TOKEN = "call_token"
        const val EXTRA_CALLER_NAME = "caller_name"

        private const val NOTIFICATION_ID = 2001

        /**
         * Call tokens this process has received through the legitimate FCM push
         * pipeline. The exported [com.solutions5060.aria.MainActivity] only acts
         * on a `call_token` intent extra if it is present here, so an arbitrary
         * app cannot inject a fabricated token into the answer flow.
         *
         * In-memory only (cleared on process death); a token is a short-lived
         * per-call secret issued by the gateway.
         */
        private val expectedCallTokens =
            java.util.Collections.synchronizedSet(HashSet<String>())

        /** Records a token as originating from a genuine incoming-call push. */
        fun expectCallToken(token: String) {
            if (token.isNotEmpty()) expectedCallTokens.add(token)
        }

        /** True if [token] was issued via the push pipeline (provenance check). */
        fun isExpectedCallToken(token: String): Boolean =
            expectedCallTokens.contains(token)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        Log.i(TAG, "Starting active call service")
        val notification = buildActiveNotification(callerName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
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
