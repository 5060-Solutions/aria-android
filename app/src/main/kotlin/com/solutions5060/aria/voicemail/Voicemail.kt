package com.solutions5060.aria.voicemail

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One voicemail message, as `GET /api/v1/voicemail/boxes/{box_id}/messages`
 * returns it (aria-db `VoicemailMessage`).
 */
data class VoicemailMessage(
    val id: String,
    val boxId: String,
    val callerId: String,
    val durationSecs: Int,
    val read: Boolean,
    /** RFC 3339 timestamp. */
    val createdAt: String,
    val transcription: String?,
)

/** Pure parsing of the Aria PBX voicemail API's JSON. */
object VoicemailJson {

    fun parseMessages(body: String): List<VoicemailMessage> {
        val array = JSONArray(body)
        return (0 until array.length()).map { parseMessage(array.getJSONObject(it)) }
    }

    fun parseMessage(json: JSONObject): VoicemailMessage = VoicemailMessage(
        id = json.getString("id"),
        boxId = json.optString("box_id"),
        callerId = json.optString("caller_id").ifEmpty { "Unknown" },
        durationSecs = json.optInt("duration_secs", 0),
        read = json.optBoolean("read", false),
        createdAt = json.optString("created_at"),
        transcription = if (json.isNull("transcription")) null else json.optString("transcription").takeIf { it.isNotBlank() },
    )

    /** `GET /api/v1/extensions/{ext_id}/voicemail` → the box id. */
    fun parseBoxId(body: String): String = JSONObject(body).getString("id")

    /** `GET /api/v1/me` → the extension this credential acts as, if any. */
    fun parseActingExtension(body: String): String? =
        JSONObject(body).optString("acting_extension").takeIf { it.isNotEmpty() }

    /** `POST .../audio-grant` → the single-object token for `?token=`. */
    fun parseGrantToken(body: String): String = JSONObject(body).getString("token")

    /** "Oct 29, 7:05 AM" in [zone], or the raw value if it does not parse. */
    fun displayTime(createdAt: String, zone: ZoneId = ZoneId.systemDefault()): String =
        try {
            OffsetDateTime.parse(createdAt).atZoneSameInstant(zone)
                .format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault()))
        } catch (_: Exception) {
            createdAt
        }

    fun displayDuration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return "%d:%02d".format(Locale.ROOT, safe / 60, safe % 60)
    }

    /** The caller id a person recognises: a SIP URI's user part, or the id as sent. */
    fun displayCaller(callerId: String): String =
        callerId.removePrefix("sip:").substringBefore("@").ifEmpty { callerId }
}
