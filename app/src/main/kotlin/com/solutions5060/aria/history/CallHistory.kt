package com.solutions5060.aria.history

import android.content.Context
import com.solutions5060.aria.call.EndReason
import org.json.JSONArray
import org.json.JSONObject
import uniffi.aria_mobile.CallDirection
import java.util.UUID

data class CallHistoryEntry(
    val id: String,
    val remoteUri: String,
    val remoteName: String?,
    val direction: String,
    /** Wall-clock time the call started (rang, or was dialled). */
    val timestamp: Long,
    /** Connected time; 0 for a call that never connected. */
    val durationSeconds: Int,
    /** An incoming call nobody answered. */
    val missed: Boolean,
    /** An incoming call this user declined. */
    val rejected: Boolean = false,
) {
    val displayName: String get() = remoteName ?: remoteUri

    /** The number to dial to call back: the URI's user part. */
    val dialNumber: String get() = remoteUri.removePrefix("sip:").substringBefore("@")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("remoteUri", remoteUri)
        put("remoteName", remoteName ?: JSONObject.NULL)
        put("direction", direction)
        put("timestamp", timestamp)
        put("durationSeconds", durationSeconds)
        put("missed", missed)
        put("rejected", rejected)
    }

    companion object {
        fun fromJson(json: JSONObject): CallHistoryEntry = CallHistoryEntry(
            id = json.getString("id"),
            remoteUri = json.getString("remoteUri"),
            remoteName = if (json.isNull("remoteName")) null else json.optString("remoteName").takeIf { it.isNotEmpty() },
            direction = json.getString("direction"),
            timestamp = json.getLong("timestamp"),
            durationSeconds = json.getInt("durationSeconds"),
            missed = json.getBoolean("missed"),
            // Absent in entries written before the field existed.
            rejected = json.optBoolean("rejected", false),
        )
    }
}

/**
 * Turns a finished call into a history entry and keeps the list bounded.
 * Pure, so the rules for what gets recorded are unit-tested.
 */
object CallHistoryRecorder {

    const val MAX_ENTRIES = 100

    /**
     * The entry for a call that ended for [reason]. Null when there is nothing
     * to show (no remote party).
     */
    fun entryFor(
        remoteUri: String,
        remoteName: String?,
        direction: CallDirection,
        startedAtMillis: Long,
        durationSeconds: Int,
        reason: EndReason,
        id: String = UUID.randomUUID().toString(),
    ): CallHistoryEntry? {
        if (remoteUri.isBlank()) return null
        return CallHistoryEntry(
            id = id,
            remoteUri = remoteUri,
            remoteName = remoteName?.takeIf { it.isNotBlank() },
            direction = if (direction == CallDirection.INBOUND) "inbound" else "outbound",
            timestamp = startedAtMillis,
            durationSeconds = durationSeconds.coerceAtLeast(0),
            missed = reason == EndReason.MISSED,
            rejected = reason == EndReason.REJECTED,
        )
    }

    /** [history] with [entry] first, trimmed to [MAX_ENTRIES]. */
    fun prepend(history: List<CallHistoryEntry>, entry: CallHistoryEntry): List<CallHistoryEntry> =
        (listOf(entry) + history).take(MAX_ENTRIES)

    fun encode(history: List<CallHistoryEntry>): String =
        JSONArray().apply { history.forEach { put(it.toJson()) } }.toString()

    /** Decodes a stored list, skipping entries that cannot be read rather than losing them all. */
    fun decode(json: String?): List<CallHistoryEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            try {
                CallHistoryEntry.fromJson(array.getJSONObject(i))
            } catch (_: Exception) {
                null
            }
        }
    }

    /** The secondary line in the history list. */
    fun summary(entry: CallHistoryEntry): String = when {
        entry.missed -> "Missed"
        entry.rejected -> "Declined"
        entry.durationSeconds <= 0 -> if (entry.direction == "outbound") "Not answered" else "Not connected"
        else -> "%d:%02d".format(java.util.Locale.ROOT, entry.durationSeconds / 60, entry.durationSeconds % 60)
    }
}

/** SharedPreferences-backed persistence for the call history list. */
object CallHistoryStore {
    private const val PREFS = "aria_history"
    private const val KEY = "call_history"

    fun load(context: Context): List<CallHistoryEntry> =
        CallHistoryRecorder.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

    fun save(context: Context, history: List<CallHistoryEntry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, CallHistoryRecorder.encode(history)).apply()
    }

    @Synchronized
    fun add(context: Context, entry: CallHistoryEntry) {
        save(context, CallHistoryRecorder.prepend(load(context), entry))
    }
}
