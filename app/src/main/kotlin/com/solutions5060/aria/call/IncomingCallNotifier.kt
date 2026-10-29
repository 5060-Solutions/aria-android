package com.solutions5060.aria.call

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.solutions5060.aria.AriaApplication
import com.solutions5060.aria.MainActivity
import com.solutions5060.aria.R

/**
 * The single incoming-call UI outside the app: a call-style notification with
 * a full-screen intent, posted when Telecom calls
 * [android.telecom.Connection.onShowIncomingCallUi].
 *
 * The full-screen intent only opens the ringing call screen; it never answers
 * on its own, because on a locked phone the system fires it without the user
 * touching anything. Answering takes the explicit Answer action.
 */
object IncomingCallNotifier {

    private const val NOTIFICATION_ID = 1001

    const val ACTION_SHOW_INCOMING = "com.solutions5060.aria.SHOW_INCOMING_CALL"
    const val ACTION_ANSWER = "com.solutions5060.aria.ANSWER_CALL"
    const val ACTION_DECLINE = "com.solutions5060.aria.DECLINE_CALL"

    fun show(context: Context, recordKey: String, callerName: String, callToken: String) {
        val fullScreen = activityIntent(context, ACTION_SHOW_INCOMING, callToken, requestCode = 0)
        val answer = activityIntent(context, ACTION_ANSWER, callToken, requestCode = 1)
        val decline = PendingIntent.getBroadcast(
            context,
            2,
            Intent(context, CallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(CallManager.EXTRA_CALL_TOKEN, callToken)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val caller = Person.Builder().setName(callerName).setImportant(true).build()
        val notification = NotificationCompat.Builder(context, AriaApplication.CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Incoming Call")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setAutoCancel(false)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setGroup(recordKey)
            .build()
        // Ring until answered, declined or cancelled; the channel carries the ringtone.
        notification.flags = notification.flags or Notification.FLAG_INSISTENT

        try {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was denied; the in-app call screen still rings.
            android.util.Log.w("IncomingCallNotifier", "Cannot post incoming-call notification: $e")
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun activityIntent(context: Context, action: String, callToken: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply {
                this.action = action
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(CallManager.EXTRA_CALL_TOKEN, callToken)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
