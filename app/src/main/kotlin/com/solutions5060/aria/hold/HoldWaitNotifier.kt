package com.solutions5060.aria.hold

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.solutions5060.aria.MainActivity
import com.solutions5060.aria.R

/**
 * How a wait is presented, the same on the card and in the notification.
 * [title] is the headline; only [WAITING]'s takes the elapsed time (`%1$s`).
 */
enum class HoldWaitPresentation(@StringRes val title: Int) {
    WAITING(R.string.ui_hold_waiting),
    SOMEONE_ANSWERED(R.string.ui_hold_someone_answered),
    RINGING(R.string.ui_hold_ringing_you);

    companion object {
        /**
         * The presentation for [state], or null when the wait is not shown:
         * `joined` (the user is on the call; Hang up would end it) and `ended`.
         * `requested` and any state this app does not know read as waiting.
         */
        fun of(state: String): HoldWaitPresentation? = when (state) {
            "joined", "ended" -> null
            "human_detected" -> SOMEONE_ANSWERED
            "ringing" -> RINGING
            else -> WAITING
        }

        /** The waits to show, card and notification alike. */
        fun shown(waits: Collection<HoldWait>): List<HoldWait> = waits.filter { it.isOpen && of(it.state) != null }
    }
}

/** The system's notifications, as far as Hold for me needs them: one per wait, tagged with its `holdWaitId`. */
interface HoldNotifications {
    /** Posts or updates the notification for [wait]. */
    fun post(wait: HoldWait)

    /** Removes the notification tagged [holdWaitId]. */
    fun cancel(holdWaitId: String)

    /** The `holdWaitId` of every Hold for me notification the system is showing. */
    fun activeIds(): Set<String>
}

/** "Waiting on hold": one ongoing notification per open wait, with Rejoin now and Hang up. */
object HoldWaitNotifier {

    const val CHANNEL_ID = "hold_wait"
    const val NOTIFICATION_ID_BASE = 7320
    const val ACTION_CANCEL = "com.solutions5060.aria.HOLD_CANCEL"
    const val EXTRA_HOLD_WAIT_ID = "holdWaitId"
    const val EXTRA_CALL_ID = "callId"
    const val EXTRA_ACTION = "action"
    const val ACTION_REJOIN = "rejoin"
    const val ACTION_HANG_UP = "hang_up"

    /** Tests only: used instead of the system's notifications. */
    @Volatile
    internal var override: HoldNotifications? = null

    internal fun notificationId(holdWaitId: String) = NOTIFICATION_ID_BASE + (holdWaitId.hashCode() and 0xff)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ui_hold_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** How long a "Ringing you…" notification may outlive its last update: 3 rounds of 30 s and a margin. */
    const val RINGING_BOUND_MS = 120_000L

    /** How long a waiting notification may outlive the maximum wait (iOS uses the same staleDate). */
    const val WAIT_MARGIN_MS = 300_000L

    /**
     * When the system should drop [wait]'s notification on its own (ms from
     * [nowMillis]), in case the end of the wait never reaches this app (the
     * socket runs in the foreground only). Null for a wait that is not shown.
     * At least one second, so an already-overdue one goes at once.
     */
    fun timeoutAfterMs(wait: HoldWait, nowMillis: Long): Long? {
        val deadline = when (HoldWaitPresentation.of(wait.state)) {
            null -> return null
            HoldWaitPresentation.RINGING -> (wait.stateSinceMillis ?: nowMillis) + RINGING_BOUND_MS
            else -> (wait.startedAtMillis ?: nowMillis) + wait.maxWaitSecs * 1_000L + WAIT_MARGIN_MS
        }
        return (deadline - nowMillis).coerceAtLeast(1_000L)
    }

    /**
     * Makes the notifications match [waits]: each shown one is posted or
     * updated; a closed or joined one, and any notification whose wait is not
     * in the model (the process died and came back), is cancelled.
     */
    fun sync(context: Context?, waits: List<HoldWait>) {
        val notifications = override ?: context?.let { SystemHoldNotifications(it) } ?: return
        val open = HoldWaitPresentation.shown(waits)
        open.forEach { notifications.post(it) }
        val keep = open.mapTo(HashSet()) { it.holdWaitId }
        (notifications.activeIds() - keep).forEach { notifications.cancel(it) }
    }
}

internal class SystemHoldNotifications(private val context: Context) : HoldNotifications {

    override fun post(wait: HoldWait) {
        val presentation = HoldWaitPresentation.of(wait.state) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val id = HoldWaitNotifier.notificationId(wait.holdWaitId)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
        // The chronometer shows the elapsed time, so "Waiting on hold · %s" loses its time part here.
        val title = context.getString(presentation.title, "")
            .let { if (presentation == HoldWaitPresentation.WAITING) it.trimEnd(' ', '·') else it }
        val now = System.currentTimeMillis()
        val builder = NotificationCompat.Builder(context, HoldWaitNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(title)
            .setContentText(wait.remoteName ?: wait.remoteNumber)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setUsesChronometer(true)
            .setWhen(wait.startedAtMillis ?: now)
            .addAction(0, context.getString(R.string.ui_hold_rejoin_now), action(wait, HoldWaitNotifier.ACTION_REJOIN, id, flags))
            .addAction(0, context.getString(R.string.ui_hold_hang_up), action(wait, HoldWaitNotifier.ACTION_HANG_UP, id, flags))
        HoldWaitNotifier.timeoutAfterMs(wait, now)?.let { builder.setTimeoutAfter(it) }
        runCatching { NotificationManagerCompat.from(context).notify(wait.holdWaitId, id, builder.build()) }
    }

    private fun action(wait: HoldWait, action: String, id: Int, flags: Int): PendingIntent {
        val intent = Intent(context, HoldWaitActionReceiver::class.java)
            .setAction(HoldWaitNotifier.ACTION_CANCEL)
            // Distinct data per wait and action so the PendingIntents do not collapse into one.
            .setData(android.net.Uri.parse("aria-hold://${wait.holdWaitId}/$action"))
            .putExtra(HoldWaitNotifier.EXTRA_HOLD_WAIT_ID, wait.holdWaitId)
            .putExtra(HoldWaitNotifier.EXTRA_CALL_ID, wait.callId)
            .putExtra(HoldWaitNotifier.EXTRA_ACTION, action)
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }

    override fun cancel(holdWaitId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(holdWaitId, HoldWaitNotifier.notificationId(holdWaitId)) }
    }

    override fun activeIds(): Set<String> = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.id in HoldWaitNotifier.NOTIFICATION_ID_BASE..(HoldWaitNotifier.NOTIFICATION_ID_BASE + 0xff) }
            .mapNotNull { it.tag }
            .toSet()
    }.getOrDefault(emptySet())
}
