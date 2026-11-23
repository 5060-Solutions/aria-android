package com.solutions5060.aria.call

import android.telecom.DisconnectCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.EndReason as CoreEndReason

class ConnectionLossTest {

    @Test
    fun aConnectedCallEndedAsFailedIsADrop() {
        assertTrue(CallStateMapping.isConnectionLoss(CoreEndReason.FAILED, wasAnswered = true, localHangupRequested = false))
        assertFalse(CallStateMapping.isConnectionLoss(CoreEndReason.FAILED, wasAnswered = false, localHangupRequested = false))
        assertFalse(CallStateMapping.isConnectionLoss(CoreEndReason.FAILED, wasAnswered = true, localHangupRequested = true))
        assertFalse(CallStateMapping.isConnectionLoss(CoreEndReason.REMOTE_HANGUP, wasAnswered = true, localHangupRequested = false))
    }

    @Test
    fun aDropIsATelecomError() {
        val reason = CallStateMapping.endReasonForEngineEnd(false, true, CallDirection.OUTBOUND, CoreEndReason.FAILED)
        assertEquals(EndReason.FAILED, reason)
        val update = CallStateMapping.disconnectFor(reason) as TelecomUpdate.Disconnect
        assertEquals(DisconnectCause.ERROR, update.cause)
    }

    @Test
    fun negotiatedCodecNames() {
        assertEquals("Opus", CodecPreferences.labelForSdp("opus"))
        assertEquals("G.711 µ-law", CodecPreferences.labelForSdp("PCMU"))
        assertEquals("G.711 A-law", CodecPreferences.labelForSdp("PCMA"))
        assertEquals("G.729", CodecPreferences.labelForSdp("G729"))
        assertEquals("iLBC", CodecPreferences.labelForSdp("iLBC"))
        assertEquals(null, CodecPreferences.labelForSdp(null))
    }
}
