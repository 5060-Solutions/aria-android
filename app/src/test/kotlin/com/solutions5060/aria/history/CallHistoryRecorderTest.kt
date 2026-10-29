package com.solutions5060.aria.history

import com.solutions5060.aria.call.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.aria_mobile.CallDirection

class CallHistoryRecorderTest {

    private fun entry(
        reason: EndReason,
        direction: CallDirection = CallDirection.INBOUND,
        duration: Int = 0,
        uri: String = "sip:1002@example.com",
        name: String? = "Bob",
    ) = CallHistoryRecorder.entryFor(uri, name, direction, 1_700_000_000_000, duration, reason, id = "id-1")

    @Test
    fun remoteHangupKeepsCapturedDetails() {
        val e = entry(EndReason.REMOTE_HANGUP, CallDirection.OUTBOUND, duration = 75)!!
        assertEquals("sip:1002@example.com", e.remoteUri)
        assertEquals("Bob", e.remoteName)
        assertEquals("outbound", e.direction)
        assertEquals(1_700_000_000_000, e.timestamp)
        assertEquals(75, e.durationSeconds)
        assertFalse(e.missed)
        assertFalse(e.rejected)
        assertEquals("1:15", CallHistoryRecorder.summary(e))
    }

    @Test
    fun outgoingCancelledWhileRingingIsNotAnswered() {
        // makeCall throwing Cancelled ends the call as LOCAL_HANGUP, unanswered.
        val e = entry(EndReason.LOCAL_HANGUP, CallDirection.OUTBOUND)!!
        assertEquals("outbound", e.direction)
        assertFalse(e.missed)
        assertFalse(e.rejected)
        assertEquals("Not answered", CallHistoryRecorder.summary(e))
    }

    @Test
    fun answeredElsewhereIsNotMissed() {
        val e = entry(EndReason.ANSWERED_ELSEWHERE)!!
        assertTrue(e.answeredElsewhere)
        assertFalse(e.missed)
        assertFalse(e.rejected)
        assertEquals("Answered elsewhere", CallHistoryRecorder.summary(e))
        // Survives a save and load.
        val back = CallHistoryRecorder.decode(CallHistoryRecorder.encode(listOf(e))).single()
        assertTrue(back.answeredElsewhere)
    }

    @Test
    fun unansweredIncomingIsMissed() {
        val e = entry(EndReason.MISSED)!!
        assertTrue(e.missed)
        assertFalse(e.rejected)
        assertEquals("inbound", e.direction)
        assertEquals("Missed", CallHistoryRecorder.summary(e))
    }

    @Test
    fun declinedIncomingIsRejectedNotMissed() {
        val e = entry(EndReason.REJECTED)!!
        assertTrue(e.rejected)
        assertFalse(e.missed)
        assertEquals("Declined", CallHistoryRecorder.summary(e))
    }

    @Test
    fun transferredCallKeepsItsDurationAndSaysSo() {
        val e = entry(EndReason.TRANSFERRED, duration = 30)!!
        assertTrue(e.transferred)
        assertFalse(e.missed)
        assertEquals("0:30 · Transferred", CallHistoryRecorder.summary(e))
        assertEquals(e, CallHistoryRecorder.decode(CallHistoryRecorder.encode(listOf(e))).single())
    }

    @Test
    fun unconnectedOutgoingSaysNotAnswered() {
        val e = entry(EndReason.LOCAL_HANGUP, CallDirection.OUTBOUND)!!
        assertFalse(e.missed)
        assertEquals("Not answered", CallHistoryRecorder.summary(e))
    }

    @Test
    fun blankRemoteIsNotRecordedAndBlankNameIsDropped() {
        assertNull(entry(EndReason.REMOTE_HANGUP, uri = " "))
        assertNull(entry(EndReason.REMOTE_HANGUP, name = "  ")!!.remoteName)
        assertEquals(0, entry(EndReason.FAILED, duration = -4)!!.durationSeconds)
    }

    @Test
    fun dialNumberIsTheUserPart() {
        assertEquals("1002", entry(EndReason.REMOTE_HANGUP)!!.dialNumber)
    }

    @Test
    fun prependPutsNewestFirstAndCaps() {
        val older = (0 until CallHistoryRecorder.MAX_ENTRIES).map { i ->
            entry(EndReason.REMOTE_HANGUP)!!.copy(id = "old-$i")
        }
        val newest = entry(EndReason.MISSED)!!.copy(id = "new")
        val result = CallHistoryRecorder.prepend(older, newest)
        assertEquals(CallHistoryRecorder.MAX_ENTRIES, result.size)
        assertEquals("new", result.first().id)
        assertEquals("old-${CallHistoryRecorder.MAX_ENTRIES - 2}", result.last().id)
    }

    @Test
    fun roundTripsThroughJson() {
        val entries = listOf(
            entry(EndReason.REJECTED)!!,
            entry(EndReason.REMOTE_HANGUP, CallDirection.OUTBOUND, 12, name = null)!!.copy(id = "id-2"),
        )
        assertEquals(entries, CallHistoryRecorder.decode(CallHistoryRecorder.encode(entries)))
    }

    @Test
    fun decodesEntriesWrittenBeforeRejectedExisted() {
        val legacy = """[{"id":"a","remoteUri":"sip:1@x","remoteName":null,"direction":"inbound",""" +
            """"timestamp":5,"durationSeconds":0,"missed":true}]"""
        val decoded = CallHistoryRecorder.decode(legacy)
        assertEquals(1, decoded.size)
        assertTrue(decoded[0].missed)
        assertFalse(decoded[0].rejected)
        assertNull(decoded[0].remoteName)
    }

    @Test
    fun badInputDecodesToWhatCanBeRead() {
        assertTrue(CallHistoryRecorder.decode(null).isEmpty())
        assertTrue(CallHistoryRecorder.decode("not json").isEmpty())
        val mixed = """[{"id":"broken"},{"id":"b","remoteUri":"sip:2@x","remoteName":"Al","direction":"outbound",""" +
            """"timestamp":1,"durationSeconds":3,"missed":false}]"""
        val decoded = CallHistoryRecorder.decode(mixed)
        assertEquals(1, decoded.size)
        assertNotNull(decoded.single().remoteName)
    }
}
