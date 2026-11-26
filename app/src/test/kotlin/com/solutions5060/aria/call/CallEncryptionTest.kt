package com.solutions5060.aria.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.aria_mobile.CallState

class CallEncryptionTest {

    @Test
    fun connectedCallsMapToWhatTheCoreReports() {
        assertEquals(CallEncryption.ENCRYPTED, CallEncryption.of(CallPhase.ACTIVE, true))
        assertEquals(CallEncryption.UNENCRYPTED, CallEncryption.of(CallPhase.ACTIVE, false))
        assertEquals(CallEncryption.ENCRYPTED, CallEncryption.of(CallPhase.HELD, true))
        assertEquals(CallEncryption.UNENCRYPTED, CallEncryption.of(CallPhase.HELD, false))
    }

    @Test
    fun nothingIsClaimedBeforeTheCallConnectsOrTheCoreSays() {
        for (phase in listOf(CallPhase.INCOMING, CallPhase.CONNECTING, CallPhase.RINGING)) {
            assertEquals(phase.name, CallEncryption.UNKNOWN, CallEncryption.of(phase, false))
            assertEquals(phase.name, CallEncryption.UNKNOWN, CallEncryption.of(phase, true))
        }
        assertEquals(CallEncryption.UNKNOWN, CallEncryption.of(CallPhase.ACTIVE, null))
    }

    @Test
    fun callStateEventsCarryEncryptionOnceConnected() {
        // CallInfo.encrypted, both values, on Connected and on later events
        // (a hold, or a re-send with the state unchanged when it changes).
        assertEquals(true, CallEncryption.reported(CallState.CONNECTED, true))
        assertEquals(false, CallEncryption.reported(CallState.CONNECTED, false))
        assertEquals(true, CallEncryption.reported(CallState.ON_HOLD, true))
        assertEquals(false, CallEncryption.reported(CallState.ON_HOLD, false))
        // Before Connected the core says false: nothing negotiated yet.
        for (state in listOf(CallState.IDLE, CallState.INCOMING, CallState.RINGING, CallState.CONNECTING, CallState.ENDED)) {
            assertNull(state.name, CallEncryption.reported(state, false))
        }
    }

    @Test
    fun theNoticeComesOncePerCall() {
        val notices = UnencryptedCallNotices()
        assertFalse(notices.shouldNotify("a", CallEncryption.UNKNOWN))
        assertFalse(notices.shouldNotify("a", CallEncryption.ENCRYPTED))
        // Drops to unencrypted: notified once, not again on later events or
        // after it recovers and drops again.
        assertTrue(notices.shouldNotify("a", CallEncryption.UNENCRYPTED))
        assertFalse(notices.shouldNotify("a", CallEncryption.UNENCRYPTED))
        assertFalse(notices.shouldNotify("a", CallEncryption.ENCRYPTED))
        assertFalse(notices.shouldNotify("a", CallEncryption.UNENCRYPTED))
        // Another call gets its own notice.
        assertTrue(notices.shouldNotify("b", CallEncryption.UNENCRYPTED))
    }

    @Test
    fun endedCallsAreForgotten() {
        val notices = UnencryptedCallNotices()
        notices.shouldNotify("ended", CallEncryption.UNENCRYPTED)
        notices.shouldNotify("live", CallEncryption.UNENCRYPTED)
        notices.retainOnly(setOf("live"))
        assertFalse(notices.shouldNotify("live", CallEncryption.UNENCRYPTED))
        assertTrue(notices.shouldNotify("ended", CallEncryption.UNENCRYPTED))
    }
}
