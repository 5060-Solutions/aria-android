package com.solutions5060.aria.proximity

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.solutions5060.aria.MainActivity
import com.solutions5060.aria.R
import com.solutions5060.aria.move.ActiveCall
import com.solutions5060.aria.move.CallMoveCoordinator

/** "Continue this call here": the notification for a `handoff_suggested` (WebSocket) that arrives while the app is in the background. */
object HandoffNotifier {

    const val CHANNEL_ID = "call_handoff"
    const val ACTION_MOVE_HERE = "com.solutions5060.aria.MOVE_HERE"
    const val EXTRA_CALL_ID = "move_call_id"
    private const val NOTIFICATION_ID = 7310

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.handoff_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.handoff_channel_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, call: ActiveCall) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
        val move = PendingIntent.getBroadcast(
            context,
            call.id.hashCode(),
            Intent(context, HandoffActionReceiver::class.java).setAction(ACTION_MOVE_HERE).putExtra(EXTRA_CALL_ID, call.id),
            flags,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(context.getString(R.string.move_continue_here))
            .setContentText(context.getString(R.string.handoff_notification_text, call.displayName, call.deviceLabel))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, context.getString(R.string.move_here), move)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}

/** The notification's "Move here". Not exported: only our own PendingIntent reaches it. */
class HandoffActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HandoffNotifier.ACTION_MOVE_HERE) return
        val id = intent.getStringExtra(HandoffNotifier.EXTRA_CALL_ID) ?: return
        HandoffNotifier.cancel(context)
        CallMoveCoordinator.moveHereById(id)
    }
}
