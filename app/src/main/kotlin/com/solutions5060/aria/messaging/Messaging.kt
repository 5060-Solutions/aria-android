package com.solutions5060.aria.messaging

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A messaging number the caller reads and replies on (`GET .../messaging/inboxes`). */
data class Inbox(
    val didId: String,
    /** E.164. */
    val number: String,
    /** False: messaging is off for the number and the inbox is read-only (admins only). */
    val enabled: Boolean,
)

/** One conversation of an inbox (`GET .../inboxes/{did}/conversations`). */
data class Conversation(
    val remoteNumber: String,
    val messageCount: Int,
    val lastBody: String,
    /** `inbound` or `outbound`. */
    val lastDirection: String,
    val lastStatus: String,
    /** RFC 3339. */
    val lastAt: String,
    val lastHasMedia: Boolean,
    val optedOut: Boolean,
) {
    val lastIsInbound: Boolean get() = lastDirection == "inbound"
}

data class Attachment(val id: String, val contentType: String, val sizeBytes: Int) {
    val isImage: Boolean get() = contentType.startsWith("image/")
}

/** The server's delivery states for one message. */
enum class DeliveryStatus(val wire: String) {
    PENDING("pending"),
    SENT("sent"),
    DELIVERED("delivered"),
    FAILED("failed"),
    RECEIVED("received");

    companion object {
        fun fromWire(value: String): DeliveryStatus = entries.firstOrNull { it.wire == value } ?: PENDING
    }
}

data class ThreadMessage(
    val id: String,
    val outbound: Boolean,
    val body: String,
    val status: DeliveryStatus,
    /** The server's reason, set on a failed message (and on an inbound attachment that could not be fetched). */
    val errorMessage: String?,
    val autoReply: Boolean,
    /** RFC 3339. */
    val createdAt: String,
    val attachments: List<Attachment>,
)

data class MessageThread(val remoteNumber: String, val optedOut: Boolean, val messages: List<ThreadMessage>)

/** A `sms_received` event from `/api/v1/ws`: ids and the other party's number, never a body. */
data class SmsEvent(val didId: String, val remoteNumber: String, val messageId: String)

/** Pure parsing of the messaging API's JSON. */
object MessagingJson {

    fun inboxes(body: String): List<Inbox> {
        val a = JSONArray(body)
        return (0 until a.length()).map { i ->
            val j = a.getJSONObject(i)
            Inbox(j.getString("did_id"), j.getString("number"), j.optBoolean("enabled", true))
        }
    }

    fun conversations(body: String): List<Conversation> {
        val a = JSONArray(body)
        return (0 until a.length()).map { i ->
            val j = a.getJSONObject(i)
            Conversation(
                remoteNumber = j.getString("remote_number"),
                messageCount = j.optInt("message_count", 0),
                lastBody = j.optString("last_body"),
                lastDirection = j.optString("last_direction"),
                lastStatus = j.optString("last_status"),
                lastAt = j.optString("last_at"),
                lastHasMedia = j.optBoolean("last_has_media", false),
                optedOut = j.optBoolean("opted_out", false),
            )
        }
    }

    fun message(j: JSONObject): ThreadMessage {
        val attachments = j.optJSONArray("attachments") ?: JSONArray()
        return ThreadMessage(
            id = j.getString("id"),
            outbound = j.optString("direction") == "outbound",
            body = j.optString("body"),
            status = DeliveryStatus.fromWire(j.optString("status")),
            errorMessage = if (j.isNull("error_message")) null else j.optString("error_message").ifBlank { null },
            autoReply = j.optBoolean("auto_reply", false),
            createdAt = j.optString("created_at"),
            attachments = (0 until attachments.length()).map { i ->
                val x = attachments.getJSONObject(i)
                Attachment(x.getString("id"), x.optString("content_type"), x.optInt("size_bytes", 0))
            },
        )
    }

    /** Newest first, as the server sends it. */
    fun thread(body: String): MessageThread {
        val j = JSONObject(body)
        val a = j.optJSONArray("messages") ?: JSONArray()
        return MessageThread(
            remoteNumber = j.optString("remote_number"),
            optedOut = j.optBoolean("opted_out", false),
            messages = (0 until a.length()).map { message(a.getJSONObject(it)) },
        )
    }

    fun sentMessage(body: String): ThreadMessage = message(JSONObject(body))

    fun uploadedAttachment(body: String): Attachment {
        val x = JSONObject(body)
        return Attachment(x.getString("id"), x.optString("content_type"), x.optInt("size_bytes", 0))
    }

    /** One text frame of the event socket, or null when it is not an `sms_received` event. */
    fun smsEvent(frame: String): SmsEvent? = try {
        val j = JSONObject(frame)
        if (j.optString("type") != "sms_received") {
            null
        } else {
            SmsEvent(j.getString("did_id"), j.getString("remote_number"), j.optString("message_id"))
        }
    } catch (_: Exception) {
        null
    }

    /** "Today 4:05 PM", "Mon", "Oct 29"; the raw value when it does not parse. */
    fun displayTime(createdAt: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
        try {
            val at = OffsetDateTime.parse(createdAt).atZoneSameInstant(zone)
            val today = now.atZone(zone).toLocalDate()
            val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), today)
            when {
                days == 0L -> at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))
                days in 1..6 -> at.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
                else -> at.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
            }
        } catch (_: Exception) {
            createdAt
        }
}

/**
 * What the number a person types becomes: strict E.164, which is all the
 * server accepts (`+13035550100`). Texting is NANP only for now, so ten
 * digits, or eleven with a leading 1, get `+1`; anything else must carry its
 * own `+` and country code. Null when it is not a number.
 */
object MessageNumbers {
    fun toE164(input: String): String? {
        val trimmed = input.trim()
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty() || trimmed.any { !it.isDigit() && it !in " ()-.+" }) return null
        return when {
            trimmed.startsWith("+") -> digits.takeIf { it.length in 8..15 }?.let { "+$it" }
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> null
        }
    }

    private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")
    private val SHORT_CODE = Regex("^[0-9]{3,7}$")

    /** Strict E.164, the only form that is dialled from a thread. */
    fun isE164(value: String): Boolean = E164.matches(value)

    /** A number that may be dialled or texted back from a push or link: E.164 or a short code. */
    fun isRemoteNumber(value: String): Boolean = E164.matches(value) || SHORT_CODE.matches(value)

    /** A short code cannot be replied to. */
    fun isShortCode(value: String): Boolean = SHORT_CODE.matches(value)
}

/**
 * How many SMS segments a body takes, as the server counts them (GSM-7: 160,
 * then 153 a part; any other character makes it UCS-2: 70, then 67), and the
 * server's limit of 10.
 */
object SmsSegments {
    const val MAX_SEGMENTS = 10
    const val MAX_BODY_CHARS = 1600

    private const val GSM_BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXTENDED = "^{}\\[~]|€\u000c"

    data class Count(
        val segments: Int,
        /** Characters (or GSM-7 septets) used. */
        val units: Int,
        /** Units one segment of this body holds before it splits. */
        val perSegment: Int,
        val unicode: Boolean,
    ) {
        val tooLong: Boolean get() = segments > MAX_SEGMENTS
    }

    fun count(body: String): Count {
        if (body.isEmpty()) return Count(0, 0, 160, false)
        var septets = 0
        var gsm = true
        for (ch in body) {
            when {
                ch in GSM_BASIC && ch != '\u001b' -> septets += 1
                ch in GSM_EXTENDED -> septets += 2
                else -> {
                    gsm = false
                    break
                }
            }
        }
        return if (gsm) {
            Count(if (septets <= 160) 1 else ceilDiv(septets, 153), septets, if (septets <= 160) 160 else 153, false)
        } else {
            val units = body.length
            Count(if (units <= 70) 1 else ceilDiv(units, 67), units, if (units <= 70) 70 else 67, true)
        }
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
}

/**
 * Which conversations hold messages the person has not opened. The server
 * keeps no read state for an inbox, so this is local: a conversation is
 * unread when its latest message is inbound and newer than the last time the
 * thread was open here. An inbox seen for the first time marks everything it
 * holds as read, so a new install does not announce old history.
 */
class UnreadTracker(private val store: Store) {

    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    private fun seenKey(didId: String, number: String) = "seen|$didId|$number"
    private fun initKey(didId: String) = "init|$didId"

    /** The conversations of [didId] as the server listed them; the first call for an inbox seeds it. */
    fun observe(didId: String, conversations: List<Conversation>): Set<String> {
        if (store.get(initKey(didId)) == null) {
            conversations.forEach { store.put(seenKey(didId, it.remoteNumber), it.lastAt) }
            store.put(initKey(didId), "1")
        }
        return conversations.filter { isUnread(didId, it) }.map { it.remoteNumber }.toSet()
    }

    fun isUnread(didId: String, c: Conversation): Boolean {
        if (!c.lastIsInbound) return false
        val seen = store.get(seenKey(didId, c.remoteNumber)) ?: return true
        return instant(c.lastAt)?.let { at -> instant(seen)?.let { at.isAfter(it) } ?: true } ?: false
    }

    /** The thread was open and showed everything up to [latestAt]. */
    fun markRead(didId: String, number: String, latestAt: String) {
        val latest = instant(latestAt) ?: return
        val current = store.get(seenKey(didId, number))?.let(::instant)
        if (current != null && current.isAfter(latest)) return
        store.put(seenKey(didId, number), latestAt)
    }

    private fun instant(value: String): Instant? = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: Exception) {
        null
    }
}

/** Phone numbers inside a message body, so tapping one can call it. */
object PhoneLinks {
    data class Match(val start: Int, val end: Int, /** What to dial: digits, with a leading `+` when the text had one. */ val number: String)

    private val CANDIDATE = Regex("(?<![\\w+])\\+?\\d[\\d\\s().-]{5,}\\d(?!\\w)")

    fun find(text: String): List<Match> = CANDIDATE.findAll(text).mapNotNull { m ->
        val digits = m.value.filter { it.isDigit() }
        if (digits.length !in 7..15) return@mapNotNull null
        Match(m.range.first, m.range.last + 1, (if (m.value.startsWith("+")) "+" else "") + digits)
    }.toList()
}

/**
 * The Idempotency-Key of the draft being sent in one thread. It lives in
 * [store] (the app's preferences), so it survives leaving the screen,
 * rotation and process death: a retry of a send that got no definite answer
 * reuses it and the PBX does not send twice.
 */
class DraftKeys(private val store: UnreadTracker.Store, private val newKey: () -> String = { java.util.UUID.randomUUID().toString() }) {
    private fun key(didId: String, number: String) = "idem|$didId|$number"

    fun current(didId: String, number: String): String =
        store.get(key(didId, number)) ?: newKey().also { store.put(key(didId, number), it) }

    fun renew(didId: String, number: String) {
        store.put(key(didId, number), newKey())
    }

    companion object {
        /**
         * Whether an answer ends the draft: the PBX took the message (2xx) or
         * rejected it (4xx). A 5xx, or no answer at all ([code] null), may have
         * been stored, so the key is kept for the retry.
         */
        fun isDefinite(code: Int?): Boolean = code != null && code in 200..499
    }
}

/** The unread count across inboxes, where one inbox that fails to load keeps its last good count. */
object UnreadCounts {
    fun merge(previous: Map<String, Int>, inboxes: List<String>, fresh: Map<String, Int>): Map<String, Int> =
        inboxes.associateWith { fresh[it] ?: previous[it] ?: 0 }
}
