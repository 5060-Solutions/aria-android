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
import com.solutions5060.aria.service.GatewayConnection
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.pbx.SelfServiceState
import com.solutions5060.aria.voicemail.VoicemailMonitor
import com.solutions5060.aria.messaging.MessagingNotifier
import com.solutions5060.aria.service.NetworkMonitor
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
        Txt.install(this)

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

        // Back in the foreground: build the engine if there is none (started
        // offline, or a push-only process), or confirm the registration.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (com.solutions5060.aria.fixtures.Fixtures.active) return
                GatewayConnection.onForeground(this@AriaApplication)
                // DND / forwarding may have been changed from another device or the portal.
                if (EngineManager.savedCredentials(this@AriaApplication) != null) {
                    SelfServiceState.refresh(this@AriaApplication)
                    // Checks now, then every minute until the app goes to the background.
                    VoicemailMonitor.startPolling(this@AriaApplication)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                VoicemailMonitor.stopPolling()
            }
        })
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
            getString(R.string.channel_incoming_calls),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_incoming_calls_desc)
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
            getString(R.string.channel_active_calls),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_active_calls_desc)
        }

        val voicemailChannel = NotificationChannel(
            VoicemailMonitor.CHANNEL,
            getString(R.string.voicemail_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )

        val messagesChannel = NotificationChannel(
            MessagingNotifier.CHANNEL,
            getString(R.string.messages_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.messages_channel_desc)
        }

        manager.createNotificationChannel(voicemailChannel)
        manager.createNotificationChannel(messagesChannel)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_INCOMING_CALL)
        manager.createNotificationChannel(incomingChannel)
        manager.createNotificationChannel(activeChannel)
    }
}
