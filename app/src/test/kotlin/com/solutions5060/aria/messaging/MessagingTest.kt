package com.solutions5060.aria.messaging

import com.solutions5060.aria.ui.messages.PhotoRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class MessagingTest {

    private val did = "11111111-1111-1111-1111-111111111111"
    private val tenant = "22222222-2222-2222-2222-222222222222"
    private val message = "33333333-3333-3333-3333-333333333333"

    @Test
    fun `segments count GSM-7 at 160 then 153`() {
        assertEquals(0, SmsSegments.count("").segments)
        assertEquals(1, SmsSegments.count("a".repeat(160)).segments)
        assertEquals(2, SmsSegments.count("a".repeat(161)).segments)
        assertEquals(2, SmsSegments.count("a".repeat(306)).segments)
        assertEquals(3, SmsSegments.count("a".repeat(307)).segments)
        assertEquals(153, SmsSegments.count("a".repeat(200)).perSegment)
    }

    @Test
    fun `GSM-7 extension characters count twice`() {
        // 80 euro signs are 160 septets, one segment; 81 are 162, two.
        assertEquals(160, SmsSegments.count("€".repeat(80)).units)
        assertEquals(1, SmsSegments.count("€".repeat(80)).segments)
        assertEquals(2, SmsSegments.count("€".repeat(81)).segments)
    }

    @Test
    fun `one other character makes the message UCS-2 at 70 then 67`() {
        val one = SmsSegments.count("a".repeat(69) + "😀")
        assertTrue(one.unicode)
        assertEquals(2, one.segments) // the emoji is two UTF-16 units: 71 > 70
        assertEquals(1, SmsSegments.count("日".repeat(70)).segments)
        assertEquals(2, SmsSegments.count("日".repeat(71)).segments)
        assertEquals(3, SmsSegments.count("日".repeat(135)).segments)
    }

    @Test
    fun `ten segments is the limit`() {
        assertFalse(SmsSegments.count("a".repeat(1530)).tooLong)
        assertTrue(SmsSegments.count("a".repeat(1531)).tooLong)
    }

    @Test
    fun `numbers become strict E164`() {
        assertEquals("+13035550100", MessageNumbers.toE164("(303) 555-0100"))
        assertEquals("+13035550100", MessageNumbers.toE164("1 303 555 0100"))
        assertEquals("+13035550100", MessageNumbers.toE164("+1 303-555-0100"))
        assertEquals("+442071838750", MessageNumbers.toE164("+44 20 7183 8750"))
        assertNull(MessageNumbers.toE164("555-0100"))
        assertNull(MessageNumbers.toE164("call me"))
        assertNull(MessageNumbers.toE164(""))
    }

    @Test
    fun `short codes are recognised and cannot be replied to`() {
        assertTrue(MessageNumbers.isRemoteNumber("+13035550100"))
        assertTrue(MessageNumbers.isRemoteNumber("12345"))
        assertTrue(MessageNumbers.isShortCode("12345"))
        assertFalse(MessageNumbers.isShortCode("+13035550100"))
        assertFalse(MessageNumbers.isRemoteNumber("../etc"))
    }

    @Test
    fun `a thread parses with attachments, status and the server error`() {
        val body = """
            {"remote_number":"+13035550100","opted_out":true,"messages":[
              {"id":"$message","direction":"outbound","body":"hi","status":"failed","error_message":"carrier said no",
               "auto_reply":false,"created_at":"2026-11-25T10:00:00Z",
               "attachments":[{"id":"$did","content_type":"image/jpeg","size_bytes":1234}]},
              {"id":"$tenant","direction":"inbound","body":"yo","status":"received","error_message":null,
               "auto_reply":false,"created_at":"2026-11-25T09:00:00Z","attachments":[]}
            ]}
        """.trimIndent()
        val thread = MessagingJson.thread(body)
        assertTrue(thread.optedOut)
        assertEquals(2, thread.messages.size)
        val first = thread.messages[0]
        assertTrue(first.outbound)
        assertEquals(DeliveryStatus.FAILED, first.status)
        assertEquals("carrier said no", first.errorMessage)
        assertTrue(first.attachments.single().isImage)
        assertFalse(thread.messages[1].outbound)
        assertNull(thread.messages[1].errorMessage)
    }

    @Test
    fun `inboxes and conversations parse`() {
        val inboxes = MessagingJson.inboxes("""[{"did_id":"$did","number":"+13035550100","last_inbound_at":null,"enabled":false}]""")
        assertEquals(Inbox(did, "+13035550100", false), inboxes.single())
        val c = MessagingJson.conversations(
            """[{"remote_number":"+13035550111","message_count":3,"last_body":"x","last_direction":"inbound",
               "last_status":"received","last_at":"2026-11-25T10:00:00Z","last_has_media":true,"opted_out":false}]""",
        ).single()
        assertTrue(c.lastIsInbound)
        assertTrue(c.lastHasMedia)
    }

    @Test
    fun `only sms_received frames are events`() {
        val event = MessagingJson.smsEvent(
            """{"type":"sms_received","tenant_id":"$tenant","did_id":"$did","message_id":"$message","remote_number":"+13035550111","extension_ids":[]}""",
        )
        assertEquals(SmsEvent(did, "+13035550111", message), event)
        assertNull(MessagingJson.smsEvent("""{"type":"call_started"}"""))
        assertNull(MessagingJson.smsEvent("not json"))
    }

    @Test
    fun `unread is an inbound latest message newer than the last read, and a new inbox starts read`() {
        val store = object : UnreadTracker.Store {
            val map = mutableMapOf<String, String>()
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
        }
        val tracker = UnreadTracker(store)
        fun conv(number: String, at: String, dir: String = "inbound") =
            Conversation(number, 1, "x", dir, "received", at, false, false)

        val old = conv("+13035550111", "2026-11-24T10:00:00Z")
        assertTrue(tracker.observe(did, listOf(old)).isEmpty())

        val fresh = conv("+13035550111", "2026-11-25T10:00:00Z")
        assertEquals(setOf("+13035550111"), tracker.observe(did, listOf(fresh)))
        assertEquals(
            setOf("+13035550111", "+13035550222"),
            tracker.observe(did, listOf(fresh, conv("+13035550222", "2026-11-25T11:00:00Z"))),
        )
        tracker.markRead(did, "+13035550111", "2026-11-25T10:00:00Z")
        assertFalse(tracker.isUnread(did, fresh))
        assertFalse(tracker.isUnread(did, conv("+13035550111", "2026-11-25T12:00:00Z", dir = "outbound")))
        assertTrue(tracker.isUnread(did, conv("+13035550111", "2026-11-25T12:00:00Z")))
    }

    @Test
    fun `numbers in a message are found`() {
        val found = PhoneLinks.find("Call +1 (303) 555-0100 or 303.555.0111 now, order 12345 ok")
        assertEquals(listOf("+13035550100", "3035550111"), found.map { it.number })
        assertTrue(PhoneLinks.find("ticket 12345").isEmpty())
    }

    @Test
    fun `an sms push needs well formed ids and a number`() {
        val ok = mapOf(
            "type" to "sms", "tenant_id" to tenant, "did_id" to did,
            "remote_number" to "+13035550111", "message_id" to message,
        )
        assertEquals(SmsPush(tenant, did, "+13035550111", message), SmsPush.parse(ok))
        assertNull(SmsPush.parse(ok + ("did_id" to "nope")))
        assertNull(SmsPush.parse(ok + ("remote_number" to "../x")))
        assertNull(SmsPush.parse(ok - "message_id"))
        assertNull(SmsPush.parse(ok + ("type" to "call")))
    }

    @Test
    fun `a notification names a thread only with a valid id, number and a known inbox`() {
        val known = setOf(did)
        assertEquals(
            com.solutions5060.aria.provisioning.DeepLink.OpenThread(did, "+13035550111"),
            MessagingNotifier.threadLink(did, "+13035550111", known),
        )
        assertNull(MessagingNotifier.threadLink(did, "+13035550111", emptySet()))
        assertNull(MessagingNotifier.threadLink(did, "+13035550111", setOf(tenant)))
        assertNull(MessagingNotifier.threadLink("x", "+13035550111", known))
        assertNull(MessagingNotifier.threadLink(did, "a/b", known))
        assertNull(MessagingNotifier.threadLink(null, null, known))
    }

    @Test
    fun `a push fails closed without a stored tenant and for an unknown inbox`() {
        val push = SmsPush(tenant, did, "+13035550111", message)
        assertTrue(MessagingNotifier.accepts(push, tenant, setOf(did)))
        assertFalse(MessagingNotifier.accepts(push, null, setOf(did)))
        assertFalse(MessagingNotifier.accepts(push, "", setOf(did)))
        assertFalse(MessagingNotifier.accepts(push, message, setOf(did)))
        assertFalse(MessagingNotifier.accepts(push, tenant, emptySet()))
    }

    @Test
    fun `only strict E164 is dialled`() {
        assertTrue(MessageNumbers.isE164("+13035550111"))
        assertFalse(MessageNumbers.isE164("12345"))
        assertFalse(MessageNumbers.isE164("3035550111"))
    }

    @Test
    fun `a draft keeps its key until a definite answer`() {
        val store = object : UnreadTracker.Store {
            val map = mutableMapOf<String, String>()
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
        }
        var n = 0
        val keys = DraftKeys(store) { "k${n++}" }
        assertEquals("k0", keys.current(did, "+13035550111"))
        // A new instance (process death, another screen) sees the same key.
        assertEquals("k0", DraftKeys(store) { "other" }.current(did, "+13035550111"))
        assertEquals("k1", keys.current(did, "+13035550222"))
        keys.renew(did, "+13035550111")
        assertEquals("k2", keys.current(did, "+13035550111"))
        assertTrue(DraftKeys.isDefinite(201))
        assertTrue(DraftKeys.isDefinite(422))
        assertFalse(DraftKeys.isDefinite(502))
        assertFalse(DraftKeys.isDefinite(500))
        assertFalse(DraftKeys.isDefinite(null))
    }

    @Test
    fun `an inbox that fails keeps its last unread count`() {
        val merged = UnreadCounts.merge(mapOf("a" to 2, "b" to 3, "gone" to 9), listOf("a", "b", "c"), mapOf("a" to 1))
        assertEquals(mapOf("a" to 1, "b" to 3, "c" to 0), merged)
    }

    @Test
    fun `photos the server will not take are re-encoded`() {
        assertFalse(PhotoRules.needsReencode("image/jpeg", 1_000))
        assertTrue(PhotoRules.needsReencode("image/heic", 1_000))
        assertTrue(PhotoRules.needsReencode("image/png", PhotoRules.MAX_UPLOAD_BYTES + 1))
        assertTrue(PhotoRules.needsReencode(null, 10))
        assertEquals(4, PhotoRules.sampleSize(4096, 3072, 1024))
        assertEquals(1, PhotoRules.sampleSize(800, 600, 1024))
    }

    @Test
    fun `times show as clock, weekday or date`() {
        val now = Instant.parse("2026-11-25T18:00:00Z")
        val zone = ZoneOffset.UTC
        assertTrue(MessagingJson.displayTime("2026-11-25T16:05:00Z", now, zone).contains("4:05") || MessagingJson.displayTime("2026-11-25T16:05:00Z", now, zone).contains("16"))
        assertEquals("not a time", MessagingJson.displayTime("not a time", now, zone))
    }
}
