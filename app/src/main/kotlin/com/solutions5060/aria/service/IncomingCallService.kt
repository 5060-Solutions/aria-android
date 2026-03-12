package com.solutions5060.aria.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.solutions5060.aria.AriaApplication
import com.solutions5060.aria.R

/**
 * Foreground service for maintaining an active phone call.
 *
 * Android requires a foreground service with type "phoneCall" to keep
 * the audio session alive when the app is in the background.
 */
class IncomingCallService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerName = intent?.getStringExtra("caller_name") ?: "Active Call"

        val notification = NotificationCompat.Builder(this, AriaApplication.CHANNEL_ACTIVE_CALL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Aria")
            .setContentText("In call with $callerName")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
