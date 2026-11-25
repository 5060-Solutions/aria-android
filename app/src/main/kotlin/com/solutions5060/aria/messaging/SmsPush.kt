package com.solutions5060.aria.messaging

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.solutions5060.aria.MainActivity
import com.solutions5060.aria.R
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.ui.components.PhoneFormat

/**
 * The push the gateway sends when a text arrives:
 * `{type: "sms", tenant_id, did_id, remote_number, message_id}`. Ids only;
 * the message itself is fetched through the API when the thread opens.
 */
data class SmsPush(val tenantId: String, val didId: String, val remoteNumber: String, val messageId: String) {

    companion object {
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isUuid(value: String): Boolean = UUID.matches(value)

        /** The push [data] carries, or null when it is not a well-formed SMS push. */
        fun parse(data: Map<String, String>): SmsPush? {
            if (data["type"] != "sms") return null
            val tenant = data["tenant_id"] ?: return null
            val did = data["did_id"] ?: return null
            val number = data["remote_number"] ?: return null
            val message = data["message_id"] ?: return null
            if (!UUID.matches(tenant) || !UUID.matches(did) || !UUID.matches(message)) return null
            if (!MessageNumbers.isRemoteNumber(number)) return null
            return SmsPush(tenant.lowercase(), did.lowercase(), number, message.lowercase())
        }
    }
}

/** The "Messages" notification channel and the notification a text push raises. */
object MessagingNotifier {

    private const val TAG = "MessagingNotifier"
    const val CHANNEL = "messages"

    /** Extras on the notification's intent: open this conversation. */
    const val EXTRA_THREAD_DID = "com.solutions5060.aria.THREAD_DID"
    const val EXTRA_THREAD_NUMBER = "com.solutions5060.aria.THREAD_NUMBER"

    /**
     * Handles a push; true when it was an SMS push (handled or ignored on
     * purpose), false when it is something else. A push for another tenant
     * than the signed-in one is dropped.
     */
    fun handlePush(context: Context, data: Map<String, String>): Boolean {
        if (data["type"] != "sms") return false
        val push = SmsPush.parse(data) ?: run {
            Log.w(TAG, "Malformed SMS push ignored")
            return true
        }
        if (EngineManager.savedCredentials(context) == null) return true
        val tenant = context.getSharedPreferences(EngineManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("tenant_id", null)
        if (!accepts(push, tenant, KnownInboxes.load(context))) {
            Log.w(TAG, "SMS push for an unknown tenant or inbox ignored")
            return true
        }
        notify(context, push)
        return true
    }

    private fun notify(context: Context, push: SmsPush) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val id = "${push.didId}|${push.remoteNumber}".hashCode()
        val res = context.resources
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_THREAD_DID, push.didId)
                .putExtra(EXTRA_THREAD_NUMBER, push.remoteNumber)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(res.getString(R.string.messages_notification_title, PhoneFormat.display(push.remoteNumber)))
            .setContentText(res.getString(R.string.messages_notification_body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Who texted is personal: hide it on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_chat)
                    .setContentTitle(res.getString(R.string.messages_notification_public))
                    .build(),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Message notification refused")
        }
    }

    /**
     * Fails closed: the push must carry the signed-in tenant (none stored
     * means no match) and an inbox this seat has loaded from the PBX.
     */
    fun accepts(push: SmsPush, storedTenant: String?, knownInboxes: Set<String>): Boolean =
        !storedTenant.isNullOrEmpty() &&
            storedTenant.equals(push.tenantId, ignoreCase = true) &&
            push.didId.lowercase() in knownInboxes

    /** The thread a notification intent names, or null when an extra is missing, malformed or names no known inbox. */
    fun threadLink(didId: String?, number: String?, knownInboxes: Set<String>): com.solutions5060.aria.provisioning.DeepLink.OpenThread? {
        if (didId == null || number == null) return null
        if (!SmsPush.isUuid(didId) || !MessageNumbers.isRemoteNumber(number)) return null
        if (didId.lowercase() !in knownInboxes) return null
        return com.solutions5060.aria.provisioning.DeepLink.OpenThread(didId.lowercase(), number)
    }

    /** Takes down the notification for a conversation once it is open. */
    fun cancel(context: Context, didId: String, number: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel("$didId|$number".hashCode())
    }
}

/** The ids of the inboxes this seat last loaded from the PBX; what a push or an intent may name. */
object KnownInboxes {
    private const val KEY = "known_inboxes"

    fun save(context: Context, ids: Collection<String>) {
        context.getSharedPreferences("aria_messages", Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, ids.map { it.lowercase() }.toSet()).apply()
    }

    fun load(context: Context): Set<String> =
        context.getSharedPreferences("aria_messages", Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
}
