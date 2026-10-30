package com.solutions5060.aria.voicemail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class VoicemailJsonTest {

    // The shape aria-api serialises (aria-db VoicemailMessage: box_id and read renamed).
    private val listBody = """
        [
          {"id":"m1","box_id":"b1","tenant_id":"t","caller_id":"sip:1002@acme.example","duration_secs":42,
           "recording_url":"/spool/x.wav","storage_key":null,"read":false,
           "created_at":"2026-10-29T11:05:00.123456Z","transcription":"Call me back","transcription_language":"en"},
          {"id":"m2","box_id":"b1","tenant_id":"t","caller_id":"+15551234567","duration_secs":7,
           "recording_url":"/spool/y.wav","storage_key":"k","read":true,
           "created_at":"2026-10-28T09:00:00Z","transcription":null,"transcription_language":null}
        ]
    """.trimIndent()

    @Test
    fun parsesTheMessageList() {
        val messages = VoicemailJson.parseMessages(listBody)
        assertEquals(2, messages.size)
        val first = messages[0]
        assertEquals("m1", first.id)
        assertEquals("b1", first.boxId)
        assertEquals(42, first.durationSecs)
        assertFalse(first.read)
        assertEquals("Call me back", first.transcription)
        assertTrue(messages[1].read)
        assertNull(messages[1].transcription)
    }

    @Test
    fun readsBoxMeAndGrant() {
        assertEquals("box-9", VoicemailJson.parseBoxId("""{"id":"box-9","extension_id":"e","pin_configured":true}"""))
        assertEquals("ext-1", VoicemailJson.parseActingExtension("""{"subject":"s","acting_extension":"ext-1"}"""))
        assertNull(VoicemailJson.parseActingExtension("""{"subject":"s","tenant_id":"t"}"""))
        assertEquals("g", VoicemailJson.parseGrantToken("""{"token":"g","expires_in":300}"""))
    }

    @Test
    fun formatsForDisplay() {
        assertEquals("1002", VoicemailJson.displayCaller("sip:1002@acme.example"))
        assertEquals("+15551234567", VoicemailJson.displayCaller("+15551234567"))
        assertEquals("0:42", VoicemailJson.displayDuration(42))
        assertEquals("2:05", VoicemailJson.displayDuration(125))
        assertTrue(VoicemailJson.displayTime("2026-10-29T11:05:00.123456Z", ZoneOffset.UTC).contains("11:05"))
        assertEquals("garbage", VoicemailJson.displayTime("garbage"))
    }
}
