package com.solutions5060.aria

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import android.content.Context
import com.solutions5060.aria.security.SecurePrefs
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.call.TelecomAccount
import com.solutions5060.aria.service.NetworkMonitor
import uniffi.aria_mobile.initRuntime
import uniffi.aria_mobile.shutdownRuntime

class AriaApplication : Application() {

    companion object {
        /**
         * Rings for incoming calls. A self-managed call plays its own
         * ringtone, and a channel's sound cannot change after creation, so this
         * replaces the old silent "incoming_call" channel rather than editing it.
         */
        const val CHANNEL_INCOMING_CALL = "incoming_call_ring"
        private const val LEGACY_CHANNEL_INCOMING_CALL = "incoming_call"
        const val CHANNEL_ACTIVE_CALL = "active_call"
        private const val TAG = "AriaApp"
    }

    private var networkMonitor: NetworkMonitor? = null

    override fun onCreate() {
        super.onCreate()

        // Migrate any legacy plaintext secrets into encrypted storage before
        // anything reads credentials.
        SecurePrefs.migrateFrom(
            this,
            getSharedPreferences("aria_prefs", Context.MODE_PRIVATE),
        )

        // Initialize the Rust runtime (via UniFFI)
        initRuntime()

        // Create notification channels
        createNotificationChannels()

        // Register phone account for Telecom integration
        TelecomAccount.register(this)
        CallManager.init(this)

        // Start network monitoring for registration resilience.
        // Uses SipEngineHolder.engine which is set when the user provisions.
        networkMonitor = NetworkMonitor(this)
        networkMonitor?.start()
        Log.i(TAG, "Network monitor started")
    }

    override fun onTerminate() {
        networkMonitor?.stop()
        shutdownRuntime()
        super.onTerminate()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val incomingChannel = NotificationChannel(
            CHANNEL_INCOMING_CALL,
            "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications for incoming VoIP calls"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000)
        }

        val activeChannel = NotificationChannel(
            CHANNEL_ACTIVE_CALL,
            "Active Calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing call notification"
        }

        manager.deleteNotificationChannel(LEGACY_CHANNEL_INCOMING_CALL)
        manager.createNotificationChannel(incomingChannel)
        manager.createNotificationChannel(activeChannel)
    }
}
