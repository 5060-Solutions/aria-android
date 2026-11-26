package com.solutions5060.aria.fixtures

import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallWaiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixturesTest {

    private fun set(scene: FixtureScene, playing: Boolean = false) =
        FixtureSet.standard(scene = scene, voicemailPlaying = playing, nowMillis = 1_800_000_000_000L, nowElapsed = 10_000_000L)

    @Test
    fun onboardingIsSignedOutWithNoCall() {
        val s = set(FixtureScene.ONBOARDING)
        assertNull(s.account)
        assertTrue(s.calls.isEmpty())
    }

    @Test
    fun mainIsSignedInWithDataAndNoCall() {
        val s = set(FixtureScene.MAIN)
        assertNotNull(s.account)
        assertTrue(s.calls.isEmpty())
        assertTrue(s.history.any { it.missed })
        assertTrue(s.voicemail.any { !it.read && it.transcription != null })
        assertTrue(s.contacts.size >= 8)
    }

    @Test
    fun incomingShowsTheRingingCall() {
        val fg = CallWaiting.foreground(set(FixtureScene.INCOMING).calls)
        assertEquals(CallPhase.INCOMING, fg?.phase)
    }

    @Test
    fun inCallIsConnectedWithATimer() {
        val calls = set(FixtureScene.IN_CALL).calls
        assertEquals(1, calls.size)
        assertEquals(CallPhase.ACTIVE, calls[0].phase)
        assertEquals(10_000_000L - 754_000L, calls[0].connectedAtElapsed)
    }

    @Test
    fun unencryptedInCallIsTheSameCallWithoutSrtp() {
        val calls = set(FixtureScene.IN_CALL_UNENCRYPTED).calls
        assertEquals(1, calls.size)
        assertEquals(CallPhase.ACTIVE, calls[0].phase)
        assertEquals(false, calls[0].encrypted)
        assertEquals(true, set(FixtureScene.IN_CALL).calls[0].encrypted)
    }

    @Test
    fun twoCallsHasOneActiveInFrontAndOneHeld() {
        val calls = set(FixtureScene.TWO_CALLS).calls
        val fg = CallWaiting.foreground(calls)
        assertEquals(CallPhase.ACTIVE, fg?.phase)
        val others = CallWaiting.others(calls, fg)
        assertEquals(listOf(CallPhase.HELD), others.map { it.phase })
    }

    @Test
    fun callWaitingPutsTheRingingCallInFront() {
        val calls = set(FixtureScene.CALL_WAITING).calls
        assertEquals(CallPhase.INCOMING, CallWaiting.foreground(calls)?.phase)
    }

    @Test
    fun playingSelectsTheFirstVoicemail() {
        val s = set(FixtureScene.MAIN, playing = true)
        assertEquals(s.voicemail.first().id, s.voicemailPlayingId)
        assertNull(set(FixtureScene.MAIN).voicemailPlayingId)
    }

    @Test
    fun scenesHaveDistinctIds() {
        val ids = FixtureScene.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun holdForMeSceneIsAConnectedOutgoingCallWithTheFlagOnUnlessTurnedOff() {
        val s = set(FixtureScene.IN_CALL_HOLD_FOR_ME)
        assertEquals("in-call-hold-for-me", FixtureScene.IN_CALL_HOLD_FOR_ME.id)
        assertEquals(1, s.calls.size)
        assertEquals(CallPhase.ACTIVE, s.calls[0].phase)
        assertEquals(uniffi.aria_mobile.CallDirection.OUTBOUND, s.calls[0].direction)
        assertTrue(s.holdForMe)
        assertEquals(false, FixtureSet.standard(scene = FixtureScene.IN_CALL_HOLD_FOR_ME, holdForMe = false, nowMillis = 1L, nowElapsed = 1L).holdForMe)
        assertEquals(false, set(FixtureScene.IN_CALL).holdForMe)
    }
}
