package com.solutions5060.aria

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.solutions5060.aria.bridge.AriaMobileCore

class AriaApplication : Application() {

    companion object {
        const val CHANNEL_INCOMING_CALL = "incoming_call"
        const val CHANNEL_ACTIVE_CALL = "active_call"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize the Rust runtime
        AriaMobileCore.initRuntime()

        // Create notification channels
        createNotificationChannels()
    }

    override fun onTerminate() {
        AriaMobileCore.shutdownRuntime()
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
            setSound(null, null) // CallKit handles audio
            enableVibration(true)
        }

        val activeChannel = NotificationChannel(
            CHANNEL_ACTIVE_CALL,
            "Active Calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ongoing call notification"
        }

        manager.createNotificationChannel(incomingChannel)
        manager.createNotificationChannel(activeChannel)
    }
}
