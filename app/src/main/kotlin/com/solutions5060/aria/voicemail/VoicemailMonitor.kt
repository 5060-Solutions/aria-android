package com.solutions5060.aria.voicemail

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.MainActivity
import com.solutions5060.aria.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Message-waiting: which voicemails are unread, and which of those the user
 * has not been told about yet. Pure; unit-tested.
 */
object Mwi {
    data class Result(
        val unread: Int,
        /** Unread messages that arrived since the last check, newest first. */
        val fresh: List<VoicemailMessage>,
        /** Every unread id seen so far: next time's [seen]. */
        val seen: Set<String>,
    )

    /**
     * Compares [messages] with the unread ids already [seen]. With
     * [firstCheck] (no earlier check on this install or account) everything
     * is taken as seen, so a new install does not announce old voicemail.
     */
    fun check(messages: List<VoicemailMessage>, seen: Set<String>, firstCheck: Boolean): Result {
        val unread = messages.filter { !it.read }
        val fresh = if (firstCheck) emptyList() else unread.filter { it.id !in seen }
        return Result(unread.size, fresh, unread.map { it.id }.toSet())
    }
}

/**
 * Polls the PBX for voicemail: when the app comes to the foreground, every
 * 60 s while it stays there, and shortly after each call (a caller sent to
 * voicemail leaves the message after the call ends). Publishes the unread
 * count for the Voicemail tab badge and posts one notification for new
 * messages. The PBX does not push MWI to the app, so this is the source.
 */
object VoicemailMonitor {

    private const val TAG = "VoicemailMonitor"
    const val CHANNEL = "voicemail"
    private const val NOTIFICATION_ID = 4_201
    const val POLL_INTERVAL_MS = 60_000L

    /** After a call: a message takes a few seconds to be stored, a long one longer. */
    private val AFTER_CALL_DELAYS_MS = listOf(15_000L, 60_000L)

    private const val PREFS = "aria_prefs"
    private const val KEY_SEEN = "vm_seen_unread_ids"
    private const val KEY_CHECKED = "vm_checked_once"

    /** Extra on the notification's intent: open this tab. */
    const val EXTRA_OPEN_TAB = "com.solutions5060.aria.OPEN_TAB"

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val lock = Mutex()
    private var poller: Job? = null

    @Volatile
    internal var fetch: (Context) -> List<VoicemailMessage> = { VoicemailRepository(it).messages() }

    /** Starts polling every [POLL_INTERVAL_MS], checking at once. */
    fun startPolling(context: Context) {
        val app = context.applicationContext
        if (poller?.isActive == true) return
        poller = AppScope.scope.launch {
            while (isActive) {
                check(app)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    /** A call just ended. */
    fun afterCall(context: Context) {
        val app = context.applicationContext
        AppScope.scope.launch {
            for (d in AFTER_CALL_DELAYS_MS) {
                delay(d)
                check(app)
            }
        }
    }

    /** Checks now (e.g. after the voicemail screen marked messages read). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        AppScope.scope.launch { check(app) }
    }

    fun clear(context: Context) {
        stopPolling()
        _unread.value = 0
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_SEEN)
            remove(KEY_CHECKED)
        }
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private suspend fun check(context: Context) = lock.withLock {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if ((prefs.getString("api_url", "") ?: "").isEmpty()) return@withLock
        val messages = try {
            fetch(context)
        } catch (e: Exception) {
            // Offline, no box, or a PBX without voicemail: keep the last count.
            Log.d(TAG, "Voicemail check failed: ${e.javaClass.simpleName}")
            return@withLock
        }
        val result = Mwi.check(
            messages,
            prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty(),
            firstCheck = !prefs.getBoolean(KEY_CHECKED, false),
        )
        prefs.edit {
            putStringSet(KEY_SEEN, result.seen)
            putBoolean(KEY_CHECKED, true)
        }
        _unread.value = result.unread
        if (result.unread == 0) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } else if (result.fresh.isNotEmpty()) {
            notify(context, result.fresh, result.unread)
        }
    }

    private fun notify(context: Context, fresh: List<VoicemailMessage>, unread: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val res = context.resources
        val title = if (fresh.size == 1) {
            res.getString(R.string.voicemail_new_from, VoicemailJson.displayCaller(fresh.first().callerId))
        } else {
            res.getQuantityString(R.plurals.voicemail_new_count, fresh.size, fresh.size)
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TAB, "voicemail")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_voicemail)
            .setContentTitle(title)
            .setContentText(res.getQuantityString(R.plurals.voicemail_unread_count, unread, unread))
            .setNumber(unread)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Who called is personal: hide it on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_voicemail)
                    .setContentTitle(res.getQuantityString(R.plurals.voicemail_new_count, fresh.size, fresh.size))
                    .build(),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Voicemail notification refused")
        }
    }
}
