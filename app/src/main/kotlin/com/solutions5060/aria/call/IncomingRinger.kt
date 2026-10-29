package com.solutions5060.aria.call

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.solutions5060.aria.AriaApplication

/** Whether the app must ring by itself because the incoming-call notification cannot. */
object IncomingAlertPolicy {

    /**
     * The notification channel carries the ringtone, so the app rings by
     * itself only when that notification will make no sound: notifications
     * turned off (POST_NOTIFICATIONS denied, or blocked in settings), or the
     * call channel turned down below a sounding importance or silenced.
     * Ringing in both cases would ring twice.
     */
    fun ringInApp(notificationsEnabled: Boolean, channelImportance: Int?, channelHasSound: Boolean): Boolean {
        if (!notificationsEnabled) return true
        if (channelImportance == null) return true
        if (channelImportance < NotificationManager.IMPORTANCE_DEFAULT) return true
        return !channelHasSound
    }
}

/**
 * Rings and vibrates for an incoming call when the notification cannot (see
 * [IncomingAlertPolicy]). Follows the ringer mode: silent stays silent and
 * vibrate only vibrates. Started by [IncomingCallNotifier.show] and stopped
 * by [IncomingCallNotifier.cancel], which runs on answer, decline and end.
 * Main thread only.
 */
object IncomingRinger {

    private const val TAG = "IncomingRinger"
    private val VIBRATION_PATTERN = longArrayOf(0, 1000, 500, 1000)

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    /** Restarts a ringtone that does not loop by itself (before API 28). */
    private val replay = object : Runnable {
        override fun run() {
            val tone = ringtone ?: return
            if (!tone.isPlaying) tone.play()
            handler.postDelayed(this, 1_000)
        }
    }

    /** Rings if the incoming-call notification cannot. */
    fun startIfNeeded(context: Context) {
        val appContext = context.applicationContext
        val channel = NotificationManagerCompat.from(appContext)
            .getNotificationChannel(AriaApplication.CHANNEL_INCOMING_CALL)
        val needed = IncomingAlertPolicy.ringInApp(
            notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
            channelImportance = channel?.importance,
            channelHasSound = channel?.sound != null,
        )
        if (needed) start(appContext)
    }

    private fun start(context: Context) {
        stop()
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) {
            // Do Not Disturb is on; without the notification there is no way
            // to know whether this caller is allowed through, so stay quiet.
            Log.i(TAG, "Do Not Disturb is on; not ringing")
            return
        }
        Log.i(TAG, "Notifications cannot ring; ringing from the app")
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> return
            AudioManager.RINGER_MODE_VIBRATE -> vibrate(context)
            else -> {
                vibrate(context)
                playRingtone(context)
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(replay)
        try {
            ringtone?.stop()
        } catch (_: Exception) {}
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null
    }

    private fun playRingtone(context: Context) {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val tone = RingtoneManager.getRingtone(context, uri) ?: return
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
            tone.play()
            ringtone = tone
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) handler.postDelayed(replay, 1_000)
        } catch (e: Exception) {
            Log.w(TAG, "Could not play the ringtone: ${e.message}")
        }
    }

    private fun vibrate(context: Context) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
        vibrator = v
    }
}
