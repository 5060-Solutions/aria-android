package com.solutions5060.aria.call

import android.telecom.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.CallState
import uniffi.aria_mobile.MobileException
import uniffi.aria_mobile.EndReason as CoreEndReason

class CallStateMappingTest {

    @Test
    fun outgoingCallProgressesThroughDialingToActive() {
        val out = CallDirection.OUTBOUND
        assertEquals(CallPhase.RINGING, CallStateMapping.phaseFor(CallState.RINGING, out))
        assertEquals(CallPhase.CONNECTING, CallStateMapping.phaseFor(CallState.CONNECTING, out))
        assertEquals(CallPhase.ACTIVE, CallStateMapping.phaseFor(CallState.CONNECTED, out))
        assertEquals(TelecomUpdate.Dialing, CallStateMapping.telecomUpdateFor(CallState.RINGING, out))
        assertEquals(TelecomUpdate.Dialing, CallStateMapping.telecomUpdateFor(CallState.CONNECTING, out))
        assertEquals(TelecomUpdate.Active, CallStateMapping.telecomUpdateFor(CallState.CONNECTED, out))
    }

    @Test
    fun incomingRingingIsNotReportedAsDialing() {
        val inbound = CallDirection.INBOUND
        assertEquals(CallPhase.INCOMING, CallStateMapping.phaseFor(CallState.RINGING, inbound))
        assertEquals(CallPhase.INCOMING, CallStateMapping.phaseFor(CallState.INCOMING, inbound))
        assertEquals(TelecomUpdate.Ringing, CallStateMapping.telecomUpdateFor(CallState.RINGING, inbound))
        assertEquals(TelecomUpdate.Ringing, CallStateMapping.telecomUpdateFor(CallState.INCOMING, inbound))
    }

    @Test
    fun holdMapsBothWays() {
        val dir = CallDirection.OUTBOUND
        assertEquals(CallPhase.HELD, CallStateMapping.phaseFor(CallState.ON_HOLD, dir))
        assertEquals(TelecomUpdate.Hold, CallStateMapping.telecomUpdateFor(CallState.ON_HOLD, dir))
        // set_hold(false) re-emits Connected.
        assertEquals(TelecomUpdate.Active, CallStateMapping.telecomUpdateFor(CallState.CONNECTED, dir))
    }

    @Test
    fun idleChangesNothing() {
        assertNull(CallStateMapping.phaseFor(CallState.IDLE, CallDirection.OUTBOUND))
        assertEquals(TelecomUpdate.None, CallStateMapping.telecomUpdateFor(CallState.IDLE, CallDirection.OUTBOUND))
    }

    @Test
    fun endedCarriesTheCauseOfWhoeverHungUp() {
        val dir = CallDirection.OUTBOUND
        assertEquals(CallPhase.ENDED, CallStateMapping.phaseFor(CallState.ENDED, dir))
        assertEquals(
            TelecomUpdate.Disconnect(DisconnectCause.REMOTE),
            CallStateMapping.telecomUpdateFor(CallState.ENDED, dir),
        )
        assertEquals(
            TelecomUpdate.Disconnect(DisconnectCause.LOCAL),
            CallStateMapping.telecomUpdateFor(CallState.ENDED, dir, EndReason.LOCAL_HANGUP),
        )
    }

    @Test
    fun everyEndReasonHasADistinctDisconnectCause() {
        assertEquals(DisconnectCause.LOCAL, CallStateMapping.disconnectCauseFor(EndReason.LOCAL_HANGUP))
        assertEquals(DisconnectCause.REMOTE, CallStateMapping.disconnectCauseFor(EndReason.REMOTE_HANGUP))
        assertEquals(DisconnectCause.REJECTED, CallStateMapping.disconnectCauseFor(EndReason.REJECTED))
        assertEquals(DisconnectCause.MISSED, CallStateMapping.disconnectCauseFor(EndReason.MISSED))
        assertEquals(DisconnectCause.ERROR, CallStateMapping.disconnectCauseFor(EndReason.FAILED))
        assertEquals(EndReason.entries.size, EndReason.entries.map { CallStateMapping.disconnectCauseFor(it) }.toSet().size)
    }

    @Test
    fun engineEndIsAttributedToTheRightSide() {
        // We asked to hang up.
        assertEquals(
            EndReason.LOCAL_HANGUP,
            CallStateMapping.endReasonForEngineEnd(localHangupRequested = true, wasAnswered = true, direction = CallDirection.OUTBOUND),
        )
        // Far end hung up an answered call.
        assertEquals(
            EndReason.REMOTE_HANGUP,
            CallStateMapping.endReasonForEngineEnd(localHangupRequested = false, wasAnswered = true, direction = CallDirection.INBOUND),
        )
        // Caller gave up before we answered: Ended for incoming-<token>.
        assertEquals(
            EndReason.MISSED,
            CallStateMapping.endReasonForEngineEnd(localHangupRequested = false, wasAnswered = false, direction = CallDirection.INBOUND),
        )
        // Outgoing call that ended before it was answered (busy, declined, failed).
        assertEquals(
            EndReason.REMOTE_HANGUP,
            CallStateMapping.endReasonForEngineEnd(localHangupRequested = false, wasAnswered = false, direction = CallDirection.OUTBOUND),
        )
    }

    @Test
    fun transferDisconnectsAsOtherWithAReason() {
        assertEquals(
            TelecomUpdate.Disconnect(DisconnectCause.OTHER, "transferred"),
            CallStateMapping.disconnectFor(EndReason.TRANSFERRED),
        )
        assertEquals(TelecomUpdate.Disconnect(DisconnectCause.REMOTE, null), CallStateMapping.disconnectFor(EndReason.REMOTE_HANGUP))
    }

    @Test
    fun transferErrorsSayTheCallIsStillUp() {
        assertTrue(CallStateMapping.transferErrorMessage(MobileException.InvalidTarget("bad")).contains("transfer target"))
        assertTrue(CallStateMapping.transferErrorMessage(MobileException.InvalidState("x")).contains("can't be transferred"))
        for (e in listOf(
            MobileException.CallFailed("x"),
            MobileException.Timeout("x"),
            MobileException.GatewayException("x"),
            IllegalStateException("x"),
        )) {
            assertTrue(e.toString(), CallStateMapping.transferErrorMessage(e).contains("still on the call"))
        }
        assertTrue(CallStateMapping.transferErrorMessage(MobileException.Timeout("x")).contains("didn't go through"))
    }

    @Test
    fun transferTargetIsReducedToADialString() {
        assertEquals("5551234567", CallStateMapping.normalizeTransferTarget(" (555) 123-4567 "))
        assertEquals("+4420*1#", CallStateMapping.normalizeTransferTarget("+44 20 *1#"))
        assertEquals("sip:bob@acme.example", CallStateMapping.normalizeTransferTarget(" sip:bob@acme.example "))
        assertEquals("", CallStateMapping.normalizeTransferTarget("abc"))
    }

    @Test
    fun cancelledDialIsAQuietLocalHangup() {
        val cancelled = MobileException.Cancelled("cancelled")
        val reason = CallStateMapping.endReasonForDialFailure(cancelled)
        assertEquals(EndReason.LOCAL_HANGUP, reason)
        assertNull(CallStateMapping.dialFailureMessage(cancelled))
        assertEquals(TelecomUpdate.Disconnect(DisconnectCause.LOCAL), CallStateMapping.disconnectFor(reason))
    }

    @Test
    fun otherDialErrorsAreFailures() {
        val failed = MobileException.CallFailed("486 Busy Here")
        val reason = CallStateMapping.endReasonForDialFailure(failed)
        assertEquals(EndReason.FAILED, reason)
        assertEquals("Call failed: 486 Busy Here", CallStateMapping.dialFailureMessage(failed))
        assertEquals(TelecomUpdate.Disconnect(DisconnectCause.ERROR), CallStateMapping.disconnectFor(reason))
        assertEquals(EndReason.FAILED, CallStateMapping.endReasonForDialFailure(MobileException.AuthenticationException("401")))
    }

    @Test
    fun anExpiredTokenFailsTheDialWithAClearMessage() {
        val error = MobileException.AuthenticationException("401")
        assertEquals(EndReason.FAILED, CallStateMapping.endReasonForDialFailure(error))
        val message = CallStateMapping.dialFailureMessage(error)!!
        assertTrue(message.contains("please call again"))
    }

    @Test
    fun aFailedTransferShowsTheSipStatus() {
        val busy = MobileException.CallFailed("Call failed: the transfer failed (SIP 486); the call is still up")
        assertEquals(
            "The transfer failed: the line is busy (SIP 486). You're still on the call.",
            CallStateMapping.transferErrorMessage(busy),
        )
        val other = MobileException.CallFailed("Call failed: the transfer failed (SIP 500); the call is still up")
        assertEquals(
            "The transfer failed: the phone system reported an error (SIP 500). You're still on the call.",
            CallStateMapping.transferErrorMessage(other),
        )
        // A REFER the PBX refused outright carries no SIP status.
        assertEquals(
            "The phone system refused the transfer. You're still on the call.",
            CallStateMapping.transferErrorMessage(MobileException.CallFailed("Call failed: the PBX refused the transfer")),
        )
        assertTrue(CallStateMapping.transferErrorMessage(MobileException.Timeout("Timeout")).contains("still on the call"))
    }

    @Test
    fun sipStatusIsReadOnlyWhereTheCoreWroteIt() {
        assertEquals(486, CallStateMapping.sipStatusIn("the transfer failed (SIP 486); the call is still up"))
        assertNull(CallStateMapping.sipStatusIn("accept refused (HTTP 502)"))
        assertNull(CallStateMapping.sipStatusIn(null))
    }

    @Test
    fun onlyARefusedAnswerIsDeclinedByTheApp() {
        // The core resolves an accept whose outcome is unknown.
        assertFalse(CallStateMapping.acceptFailureNeedsReject(MobileException.Timeout("Timeout")))
        assertFalse(CallStateMapping.acceptFailureNeedsReject(MobileException.NetworkException("reset")))
        assertTrue(CallStateMapping.acceptFailureNeedsReject(MobileException.CallFailed("accept refused (HTTP 409)")))
        assertTrue(CallStateMapping.acceptFailureNeedsReject(IllegalStateException("Not connected")))
    }

    @Test
    fun coreEndReasonsMapToTelecomAndHistory() {
        val expected = mapOf(
            CoreEndReason.LOCAL_HANGUP to (EndReason.LOCAL_HANGUP to DisconnectCause.LOCAL),
            CoreEndReason.CANCELLED to (EndReason.LOCAL_HANGUP to DisconnectCause.LOCAL),
            CoreEndReason.REMOTE_HANGUP to (EndReason.REMOTE_HANGUP to DisconnectCause.REMOTE),
            CoreEndReason.CALLER_CANCELLED to (EndReason.MISSED to DisconnectCause.MISSED),
            CoreEndReason.ANSWERED_ELSEWHERE to (EndReason.ANSWERED_ELSEWHERE to DisconnectCause.ANSWERED_ELSEWHERE),
            CoreEndReason.REJECTED to (EndReason.REJECTED to DisconnectCause.REJECTED),
            CoreEndReason.TRANSFERRED to (EndReason.TRANSFERRED to DisconnectCause.OTHER),
            CoreEndReason.FAILED to (EndReason.FAILED to DisconnectCause.ERROR),
        )
        assertEquals(CoreEndReason.entries.toSet(), expected.keys)
        for ((core, pair) in expected) {
            val (reason, cause) = pair
            assertEquals(core.toString(), reason, CallStateMapping.endReasonFor(core))
            assertEquals(core.toString(), cause, CallStateMapping.disconnectCauseFor(reason))
        }
        assertEquals("transferred", CallStateMapping.disconnectReasonFor(EndReason.TRANSFERRED))
    }

    @Test
    fun theCoreReasonWinsUnlessThisSideHungUp() {
        val inbound = CallDirection.INBOUND
        assertEquals(
            EndReason.ANSWERED_ELSEWHERE,
            CallStateMapping.endReasonForEngineEnd(false, false, inbound, CoreEndReason.ANSWERED_ELSEWHERE),
        )
        assertEquals(
            EndReason.LOCAL_HANGUP,
            CallStateMapping.endReasonForEngineEnd(true, true, inbound, CoreEndReason.REMOTE_HANGUP),
        )
        // Without a reason (an older core), the old inference stands.
        assertEquals(EndReason.MISSED, CallStateMapping.endReasonForEngineEnd(false, false, inbound, null))
    }

    @Test
    fun onlyWellFormedCallTokensAreAccepted() {
        assertTrue(CallStateMapping.isWellFormedCallToken("abc123-_.~:+/="))
        assertFalse(CallStateMapping.isWellFormedCallToken(""))
        assertFalse(CallStateMapping.isWellFormedCallToken("   "))
        assertFalse(CallStateMapping.isWellFormedCallToken("has space"))
        assertFalse(CallStateMapping.isWellFormedCallToken("line\nbreak"))
        assertFalse(CallStateMapping.isWellFormedCallToken("caf\u00e9"))
        assertTrue(CallStateMapping.isWellFormedCallToken("a".repeat(8192)))
        assertFalse(CallStateMapping.isWellFormedCallToken("a".repeat(8193)))
    }

    @Test
    fun statusTextFollowsThePhase() {
        assertEquals("Incoming Call", CallStateMapping.statusText(CallPhase.INCOMING, 0))
        assertEquals("Calling...", CallStateMapping.statusText(CallPhase.CONNECTING, 0))
        assertEquals("Ringing...", CallStateMapping.statusText(CallPhase.RINGING, 0))
        assertEquals("01:05", CallStateMapping.statusText(CallPhase.ACTIVE, 65))
        assertEquals("On Hold", CallStateMapping.statusText(CallPhase.HELD, 65))
        assertEquals("Call Ended", CallStateMapping.statusText(CallPhase.ENDED, 65))
        assertEquals("00:00", CallStateMapping.formatElapsed(-3))
    }
}
