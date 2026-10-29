package com.solutions5060.aria.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.CallState
import uniffi.aria_mobile.MobileException

class CallWaitingTest {

    private fun call(
        key: String,
        phase: CallPhase,
        direction: CallDirection = CallDirection.INBOUND,
        connected: Boolean = phase == CallPhase.ACTIVE || phase == CallPhase.HELD,
    ) = CallUiState(
        key = key,
        callId = "id-$key",
        remoteUri = "sip:$key@example.com",
        remoteName = null,
        direction = direction,
        phase = phase,
        onHold = phase == CallPhase.HELD,
        connectedAtElapsed = if (connected) 1_000L else null,
    )

    // ── Busy decision ────────────────────────────────────────────────────

    @Test
    fun incomingRingsWithNoCall() {
        assertFalse(CallWaiting.shouldRejectIncomingAsBusy(emptyList()))
    }

    @Test
    fun incomingRingsAlongsideOneCall() {
        assertFalse(CallWaiting.shouldRejectIncomingAsBusy(listOf(call("a", CallPhase.ACTIVE))))
        assertFalse(CallWaiting.shouldRejectIncomingAsBusy(listOf(call("a", CallPhase.HELD))))
    }

    @Test
    fun incomingIsBusyWithTwoCalls() {
        val two = listOf(call("a", CallPhase.HELD), call("b", CallPhase.ACTIVE, CallDirection.OUTBOUND))
        assertTrue(CallWaiting.shouldRejectIncomingAsBusy(two))
    }

    @Test
    fun anEndedCallStillOnScreenDoesNotCount() {
        val calls = listOf(call("a", CallPhase.ENDED), call("b", CallPhase.ACTIVE))
        assertFalse(CallWaiting.shouldRejectIncomingAsBusy(calls))
    }

    @Test
    fun aSecondCallerWhileOneRingsIsBusy() {
        assertTrue(CallWaiting.shouldRejectIncomingAsBusy(listOf(call("a", CallPhase.INCOMING))))
    }

    // ── Outgoing ─────────────────────────────────────────────────────────

    @Test
    fun outgoingAllowedWithNoneOrOneAnsweredCall() {
        assertNull(CallWaiting.outgoingRefusal(emptyList()))
        assertNull(CallWaiting.outgoingRefusal(listOf(call("a", CallPhase.ACTIVE))))
        assertTrue(CallWaiting.canAddCall(call("a", CallPhase.ACTIVE), listOf(call("a", CallPhase.ACTIVE))))
    }

    @Test
    fun outgoingRefusedWithTwoCallsOrOneNotYetAnswered() {
        val two = listOf(call("a", CallPhase.HELD), call("b", CallPhase.ACTIVE))
        assertNotNull(CallWaiting.outgoingRefusal(two))
        assertFalse(CallWaiting.canAddCall(two[1], two))
        assertNotNull(CallWaiting.outgoingRefusal(listOf(call("a", CallPhase.INCOMING))))
        assertNotNull(CallWaiting.outgoingRefusal(listOf(call("a", CallPhase.RINGING, CallDirection.OUTBOUND))))
    }

    // ── Foreground ───────────────────────────────────────────────────────

    @Test
    fun ringingCallTakesTheScreen() {
        val calls = listOf(call("a", CallPhase.ACTIVE), call("b", CallPhase.INCOMING))
        assertEquals("b", CallWaiting.foreground(calls)?.key)
        assertEquals(listOf("a"), CallWaiting.others(calls, CallWaiting.foreground(calls)).map { it.key })
    }

    @Test
    fun callBeingPlacedOutranksTheHeldOne() {
        val calls = listOf(call("a", CallPhase.HELD), call("b", CallPhase.RINGING, CallDirection.OUTBOUND))
        assertEquals("b", CallWaiting.foreground(calls)?.key)
    }

    @Test
    fun activeCallOutranksHeldAfterSwap() {
        val calls = listOf(call("a", CallPhase.ACTIVE), call("b", CallPhase.HELD, CallDirection.OUTBOUND))
        assertEquals("a", CallWaiting.foreground(calls)?.key)
    }

    @Test
    fun heldCallComesForwardWhenTheOtherEnds() {
        val calls = listOf(call("a", CallPhase.HELD), call("b", CallPhase.ENDED, CallDirection.OUTBOUND))
        assertEquals("a", CallWaiting.foreground(calls)?.key)
        assertTrue(CallWaiting.others(calls, CallWaiting.foreground(calls)).isEmpty())
    }

    @Test
    fun lastEndedCallStaysOnScreen() {
        assertEquals("a", CallWaiting.foreground(listOf(call("a", CallPhase.ENDED)))?.key)
        assertNull(CallWaiting.foreground(emptyList()))
    }

    // ── Event routing ────────────────────────────────────────────────────

    private data class Rec(val name: String, val callId: String?, val finished: Boolean = false)

    @Test
    fun eventsRouteToTheRecordWithThatCallId() {
        val records = listOf(Rec("first", "incoming-abc"), Rec("second", "outgoing-xyz"))
        assertEquals("second", CallWaiting.recordFor("outgoing-xyz", records, { it.callId }, { it.finished })?.name)
        assertEquals("first", CallWaiting.recordFor("incoming-abc", records, { it.callId }, { it.finished })?.name)
    }

    @Test
    fun eventsForUnknownOrFinishedCallsAreDropped() {
        val records = listOf(Rec("done", "outgoing-1", finished = true), Rec("noid", null))
        assertNull(CallWaiting.recordFor("outgoing-1", records, { it.callId }, { it.finished }))
        assertNull(CallWaiting.recordFor("outgoing-2", records, { it.callId }, { it.finished }))
    }

    // ── Hold ─────────────────────────────────────────────────────────────

    @Test
    fun autoHeldCallIsHeldInTelecomAndOnScreen() {
        for (dir in CallDirection.entries) {
            assertEquals(CallPhase.HELD, CallStateMapping.phaseFor(CallState.ON_HOLD, dir))
            assertEquals(TelecomUpdate.Hold, CallStateMapping.telecomUpdateFor(CallState.ON_HOLD, dir))
            assertEquals(CallPhase.ACTIVE, CallStateMapping.phaseFor(CallState.CONNECTED, dir))
            assertEquals(TelecomUpdate.Active, CallStateMapping.telecomUpdateFor(CallState.CONNECTED, dir))
        }
    }

    @Test
    fun resumingWhileAnotherIsActiveSwaps() {
        val active = call("a", CallPhase.ACTIVE)
        val held = call("b", CallPhase.HELD, CallDirection.OUTBOUND)
        assertEquals(CallWaiting.ResumeAction.Swap("id-a", "id-b"), CallWaiting.resumeAction(held, active))
    }

    @Test
    fun resumingAloneIsAPlainResume() {
        val held = call("b", CallPhase.HELD)
        assertEquals(CallWaiting.ResumeAction.Resume("id-b"), CallWaiting.resumeAction(held, null))
        assertEquals(CallWaiting.ResumeAction.Resume("id-b"), CallWaiting.resumeAction(held, call("c", CallPhase.HELD)))
    }

    @Test
    fun onlyAHeldConnectedCallCanBeResumed() {
        assertNull(CallWaiting.resumeAction(call("a", CallPhase.ACTIVE), null))
        assertNull(CallWaiting.resumeAction(call("a", CallPhase.RINGING, CallDirection.OUTBOUND), null))
    }

    // ── Attended transfer ────────────────────────────────────────────────

    @Test
    fun answeredAndPlacedCallsCanBeJoined() {
        val inbound = call("a", CallPhase.HELD, CallDirection.INBOUND)
        val outbound = call("b", CallPhase.ACTIVE, CallDirection.OUTBOUND)
        val pair = CallWaiting.attendedTransferPair(listOf(outbound, inbound))
        assertEquals("a", pair?.transferred?.key)
        assertEquals("b", pair?.consult?.key)
    }

    @Test
    fun sameDirectionCallsCannotBeJoined() {
        val both = listOf(call("a", CallPhase.HELD), call("b", CallPhase.ACTIVE))
        assertNull(CallWaiting.attendedTransferPair(both))
        val bothOut = listOf(
            call("a", CallPhase.HELD, CallDirection.OUTBOUND),
            call("b", CallPhase.ACTIVE, CallDirection.OUTBOUND),
        )
        assertNull(CallWaiting.attendedTransferPair(bothOut))
    }

    @Test
    fun consultationMustBeAnsweredBeforeJoining() {
        val calls = listOf(call("a", CallPhase.HELD), call("b", CallPhase.RINGING, CallDirection.OUTBOUND))
        assertNull(CallWaiting.attendedTransferPair(calls))
        assertNull(CallWaiting.attendedTransferPair(listOf(call("a", CallPhase.ACTIVE))))
    }

    @Test
    fun consultFirstOnlyFromALoneAnsweredIncomingCall() {
        val inbound = call("a", CallPhase.ACTIVE)
        assertTrue(CallWaiting.canConsult(inbound, listOf(inbound)))
        val outbound = call("b", CallPhase.ACTIVE, CallDirection.OUTBOUND)
        assertFalse(CallWaiting.canConsult(outbound, listOf(outbound)))
        assertFalse(CallWaiting.canConsult(inbound, listOf(inbound, call("c", CallPhase.HELD))))
    }

    // ── Error messages ───────────────────────────────────────────────────

    @Test
    fun unsupportedPairGetsAClearMessage() {
        val msg = CallWaiting.attendedTransferErrorMessage(MobileException.TransferUnsupported("same direction"))
        assertTrue(msg.contains("answered"))
        assertTrue(msg.contains("placed"))
    }

    @Test
    fun attendedFailuresSayBothCallsAreUp() {
        val errors = listOf(
            MobileException.CallFailed("REFER rejected (SIP 486)"),
            MobileException.Timeout("no outcome"),
            MobileException.InvalidState("not connected"),
            MobileException.NetworkException("down"),
            MobileException.GatewayException("busy"),
            IllegalStateException("other"),
        )
        for (e in errors) assertTrue(CallWaiting.attendedTransferErrorMessage(e).contains("Both calls are still up"))
        assertTrue(CallWaiting.attendedTransferErrorMessage(errors[0]).contains("SIP 486"))
    }

    @Test
    fun blindTransferOfAnUnsupportedCallIsExplained() {
        val msg = CallStateMapping.transferErrorMessage(MobileException.TransferUnsupported("placed call"))
        assertTrue(msg.contains("answered"))
    }
}
