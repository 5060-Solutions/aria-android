package com.solutions5060.aria.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.solutions5060.aria.AriaApplication
import com.solutions5060.aria.call.CallActivity
import com.solutions5060.aria.R

/**
 * Foreground service that runs while a call is up, keeping the process and
 * microphone access alive with a persistent "in call" notification.
 *
 * Started with [start] when the user answers or dials, which is when the app
 * is most likely in the foreground: Android 14 only lets a microphone-type
 * foreground service start from the foreground. Stopped with [stop], which
 * sends a stop command rather than calling stopService, so the start command
 * is always handled (and startForeground always called) first. Stopping a
 * service started with startForegroundService before it calls
 * startForeground crashes the app.
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
        private const val ACTION_STOP = "com.solutions5060.aria.STOP_CALL_SERVICE"

        /** Set while a start has been requested and no stop sent yet. Main-thread only. */
        private var requested = false

        /** Starts (or updates) the in-call foreground service. Call on the main thread. */
        fun start(context: Context, callerName: String) {
            val intent = Intent(context, IncomingCallService::class.java)
                .putExtra(EXTRA_CALLER_NAME, callerName)
            try {
                context.startForegroundService(intent)
                requested = true
            } catch (e: Exception) {
                // Android 12+ refuses a foreground service started from the
                // background (ForegroundServiceStartNotAllowedException). The
                // call goes on without it; see the class comment.
                Log.w(TAG, "Could not start the call service: ${e.javaClass.simpleName}")
            }
        }

        /** Stops the service, after its start command if that is still pending. Main thread. */
        fun stop(context: Context) {
            if (!requested) return
            requested = false
            val intent = Intent(context, IncomingCallService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Only when the app is in the background with no foreground
                // service, i.e. the service never got to run: nothing to order.
                Log.w(TAG, "Stop command refused (${e.javaClass.simpleName}); stopping directly")
                context.stopService(Intent(context, IncomingCallService::class.java))
            }
        }

        private const val NOTIFICATION_ID = 2001

        /**
         * Call tokens this process has received through the legitimate FCM push
         * pipeline. [com.solutions5060.aria.call.CallActivity] only answers a
         * `call_token` intent extra that is present here, so a fabricated token
         * cannot drive the answer flow.
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
        if (intent?.action == ACTION_STOP) {
            // Commands arrive in order, so the start before this one has
            // already called startForeground.
            Log.i(TAG, "Stopping active call service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        Log.i(TAG, "Starting active call service")
        if (!promote(buildActiveNotification(callerName))) stopSelf()
        return START_NOT_STICKY
    }

    /**
     * startForeground with the most capable type Android allows here. False
     * if none was.
     *
     * The microphone type is tried first. Android 14+ refuses it
     * (SecurityException) when the service starts from the background, e.g.
     * answering from a Bluetooth headset, a watch or Android Auto with the app
     * closed; the phone-call type alone still keeps the process alive. Null
     * means the untyped call, before API 29.
     */
    private fun promote(notification: Notification): Boolean {
        val attempts: List<Int?> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            )
            // The microphone type arrived in API 30.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            else -> listOf(null)
        }
        for (type in attempts) {
            try {
                if (type != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, type)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && attempts.indexOf(type) > 0) {
                    // The call continues, but Android may silence the
                    // microphone while the app is in the background.
                    Log.w(TAG, "Call service running without the microphone type")
                }
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "startForeground refused for type $type: ${e.message}")
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) is one.
                Log.w(TAG, "startForeground not allowed for type $type: ${e.javaClass.simpleName}")
            }
        }
        Log.e(TAG, "Could not run the call service in the foreground")
        return false
    }

    private fun buildActiveNotification(callerName: String): Notification {
        // The call screen alone, so tapping this on a locked phone shows the
        // call without unlocking into the rest of the app.
        val openIntent = Intent(this, CallActivity::class.java).apply {
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
