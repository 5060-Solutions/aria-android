package com.solutions5060.aria

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.solutions5060.aria.service.AriaConnectionService
import uniffi.aria_mobile.initRuntime
import uniffi.aria_mobile.shutdownRuntime

class AriaApplication : Application() {

    companion object {
        const val CHANNEL_INCOMING_CALL = "incoming_call"
        const val CHANNEL_ACTIVE_CALL = "active_call"
        private const val TAG = "AriaApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize the Rust runtime (via UniFFI)
        initRuntime()

        // Create notification channels
        createNotificationChannels()

        // Register phone account for Telecom integration
        registerPhoneAccount()
    }

    override fun onTerminate() {
        shutdownRuntime()
        super.onTerminate()
    }

    private fun registerPhoneAccount() {
        try {
            val telecomManager = getSystemService(TelecomManager::class.java)
            val componentName = ComponentName(this, AriaConnectionService::class.java)
            val handle = PhoneAccountHandle(componentName, "aria_voip")
            val account = PhoneAccount.builder(handle, "Aria VoIP")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager.registerPhoneAccount(account)
            Log.i(TAG, "Phone account registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register phone account (non-fatal): $e")
        }
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
