package com.solutions5060.aria.hold

import android.telecom.DisconnectCause
import com.solutions5060.aria.call.CallStateMapping
import com.solutions5060.aria.call.EndReason
import com.solutions5060.aria.history.CallHistoryRecorder
import com.solutions5060.aria.pbx.MeFeatures
import com.solutions5060.aria.pbx.SelfServiceJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.aria_mobile.CallDirection
import uniffi.aria_mobile.EndReason as CoreEndReason

class HoldForMeTest {

    @Test
    fun featuresAdvertiseHoldForMe() {
        assertEquals(MeFeatures(true), SelfServiceJson.meFeatures("""{"role":"user","features":["hold_for_me"]}"""))
        assertEquals(MeFeatures(true), SelfServiceJson.meFeatures("""{"features":["other","hold_for_me"]}"""))
        assertEquals(MeFeatures(false), SelfServiceJson.meFeatures("""{"features":[]}"""))
        assertEquals(MeFeatures(false), SelfServiceJson.meFeatures("""{"features":["other"]}"""))
        // An older PBX sends no field; a malformed one is not a list.
        assertEquals(MeFeatures(false), SelfServiceJson.meFeatures("""{"role":"user"}"""))
        assertEquals(MeFeatures(false), SelfServiceJson.meFeatures("""{"features":"hold_for_me"}"""))
        assertEquals(MeFeatures(false), SelfServiceJson.meFeatures("""{"features":null}"""))
    }

    @Test
    fun heldForYouIsNotADroppedCall() {
        val reason = CallStateMapping.endReasonFor(CoreEndReason.HELD_FOR_YOU)
        assertEquals(EndReason.HELD_FOR_YOU, reason)
        assertEquals(DisconnectCause.REMOTE, CallStateMapping.disconnectCauseFor(EndReason.HELD_FOR_YOU))
        // No "Call dropped" banner.
        assertFalse(CallStateMapping.isConnectionLoss(CoreEndReason.HELD_FOR_YOU, wasAnswered = true, localHangupRequested = false))
        assertEquals(
            EndReason.HELD_FOR_YOU,
            CallStateMapping.endReasonForEngineEnd(false, true, CallDirection.OUTBOUND, CoreEndReason.HELD_FOR_YOU),
        )
        // Not a missed call, and not a failure, in Recents.
        val entry = CallHistoryRecorder.entryFor(
            "sip:bank@example.com", "Acme Bank", CallDirection.OUTBOUND, 1_000L, 60, EndReason.HELD_FOR_YOU,
        )!!
        assertFalse(entry.missed)
        assertFalse(entry.rejected)
        assertFalse(entry.answeredElsewhere)
    }

    @Test
    fun fcmHoldReturnIsParsed() {
        val camel = HoldReturnPush.from(mapOf("holdReturnId" to "w-1", "holdReturnName" to "Acme Bank"))
        assertEquals(HoldReturnPush("w-1", "Acme Bank"), camel)
        val snake = HoldReturnPush.from(mapOf("hold_return_id" to "w-2", "hold_return_name" to "Acme"))
        assertEquals(HoldReturnPush("w-2", "Acme"), snake)
        // camelCase wins over snake_case.
        assertEquals(
            HoldReturnPush("w-3", null),
            HoldReturnPush.from(mapOf("holdReturnId" to "w-3", "hold_return_id" to "w-9")),
        )
        // Each is omitted when its header is absent; empty counts as absent.
        assertEquals(HoldReturnPush(null, null), HoldReturnPush.from(mapOf("callToken" to "t")))
        assertNull(HoldReturnPush.from(mapOf("holdReturnId" to "", "holdReturnName" to "  ")).id)
        assertNull(HoldReturnPush.from(mapOf("holdReturnId" to "", "holdReturnName" to "  ")).name)
    }
}
