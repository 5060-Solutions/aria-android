package com.solutions5060.aria.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.aria_mobile.CallDirection

class ConferenceTest {

    private fun call(
        key: String,
        phase: CallPhase,
        inConference: Boolean = false,
        callId: String? = "id-$key",
    ) = CallUiState(
        key = key,
        callId = callId,
        remoteUri = "sip:$key@example.com",
        remoteName = null,
        direction = CallDirection.OUTBOUND,
        phase = phase,
        onHold = phase == CallPhase.HELD,
        connectedAtElapsed = if (phase == CallPhase.ACTIVE || phase == CallPhase.HELD) 1L else null,
        inConference = inConference,
    )

    @Test
    fun mergesAHeldAndAnActiveCallOldestFirst() {
        val pair = Conference.mergePair(listOf(call("a", CallPhase.HELD), call("b", CallPhase.ACTIVE)))!!
        assertEquals("a", pair.first.key)
        assertEquals("b", pair.second.key)
    }

    @Test
    fun noMergeWithOneCall() {
        assertNull(Conference.mergePair(listOf(call("a", CallPhase.ACTIVE))))
    }

    @Test
    fun noMergeWhileTheSecondCallIsStillRinging() {
        assertFalse(Conference.canMerge(listOf(call("a", CallPhase.HELD), call("b", CallPhase.RINGING))))
    }

    @Test
    fun noMergeWhenAlreadyConferenced() {
        val calls = listOf(call("a", CallPhase.ACTIVE, true), call("b", CallPhase.ACTIVE, true))
        assertFalse(Conference.canMerge(calls))
        assertTrue(Conference.canSplit(calls))
    }

    @Test
    fun noMergeWithoutEngineIds() {
        assertFalse(Conference.canMerge(listOf(call("a", CallPhase.HELD, callId = null), call("b", CallPhase.ACTIVE))))
    }

    @Test
    fun anEndedConferencedCallDoesNotOfferSplit() {
        assertFalse(Conference.canSplit(listOf(call("a", CallPhase.ENDED, true), call("b", CallPhase.ACTIVE))))
    }

    @Test
    fun swapIsRefusedWhileConferenced() {
        assertFalse(Conference.canSwap(listOf(call("a", CallPhase.ACTIVE, true), call("b", CallPhase.ACTIVE, true))))
        assertTrue(Conference.canSwap(listOf(call("a", CallPhase.HELD), call("b", CallPhase.ACTIVE))))
    }
}
