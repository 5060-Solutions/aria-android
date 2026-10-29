package com.solutions5060.aria.call

import android.telecom.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.CallState

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
