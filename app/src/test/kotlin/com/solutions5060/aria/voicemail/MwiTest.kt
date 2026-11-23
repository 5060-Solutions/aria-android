package com.solutions5060.aria.voicemail

import org.junit.Assert.assertEquals
import org.junit.Test

class MwiTest {

    private fun msg(id: String, read: Boolean = false) =
        VoicemailMessage(id, "box", "sip:+15550100@pbx", 12, read, "2026-11-23T10:00:00Z", null)

    @Test
    fun theFirstCheckAnnouncesNothing() {
        val r = Mwi.check(listOf(msg("a"), msg("b"), msg("c", read = true)), emptySet(), firstCheck = true)
        assertEquals(2, r.unread)
        assertEquals(emptyList<VoicemailMessage>(), r.fresh)
        assertEquals(setOf("a", "b"), r.seen)
    }

    @Test
    fun onlyNewUnreadMessagesAreAnnounced() {
        val r = Mwi.check(listOf(msg("new"), msg("a"), msg("b", read = true)), setOf("a", "b"), firstCheck = false)
        assertEquals(2, r.unread)
        assertEquals(listOf("new"), r.fresh.map { it.id })
        assertEquals(setOf("new", "a"), r.seen)
    }

    @Test
    fun aMessageReadElsewhereLeavesTheCountAndIsNotReannounced() {
        val r = Mwi.check(listOf(msg("a", read = true)), setOf("a"), firstCheck = false)
        assertEquals(0, r.unread)
        assertEquals(emptyList<VoicemailMessage>(), r.fresh)
        assertEquals(emptySet<String>(), r.seen)
    }
}
