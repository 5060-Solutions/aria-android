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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.solutions5060.aria.R
import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallUiState
import com.solutions5060.aria.service.PbxApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
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

    // ── Service, events and policy (Task D2) ───────────────────────────

    private val server = MockWebServer()

    @Before fun startServer() = server.start()

    @After fun stopServer() = server.shutdown()

    private fun api() = PbxApiClient(server.url("/").toString().trimEnd('/'))

    private val waitJson = """
        {"hold_wait_id":"w-1","call_id":"call-1","state":"waiting","greeting":"line",
         "started_at":"2026-11-26T15:04:05Z","state_since":"2026-11-26T15:04:06Z","max_wait_secs":10800,
         "false_alarms":0,"remote":{"number":"+18005550100","name":"Acme Bank"},"ring_reason":null,"ended_reason":null}
    """.trimIndent()

    @Test
    fun startPostsTheGreetingAndName() {
        server.enqueue(MockResponse().setResponseCode(202).setBody(waitJson))
        val body = api().holdStart("jwt", "call-1", "assistant", "Acme Bank")
        assertEquals("w-1", HoldJson.wait(body).holdWaitId)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/me/calls/call-1/hold-for-me", req.path)
        assertEquals("Bearer jwt", req.getHeader("Authorization"))
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("assistant", sent.getString("greeting"))
        assertEquals("Acme Bank", sent.getString("display_name"))

        // Both fields are optional: omitted, not null.
        server.enqueue(MockResponse().setResponseCode(202).setBody(waitJson))
        api().holdStart("jwt", "call-1", null, null)
        val bare = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(bare.has("greeting"))
        assertFalse(bare.has("display_name"))
    }

    @Test
    fun cancelUsesTheActionQuery() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(202).setBody(waitJson)) }
        api().holdCancel("jwt", "call-1", "rejoin")
        val del = server.takeRequest()
        assertEquals("DELETE", del.method)
        assertEquals("/api/v1/me/calls/call-1/hold-for-me?action=rejoin", del.path)
        api().holdCancel("jwt", "call-1", "hang_up")
        assertEquals("/api/v1/me/calls/call-1/hold-for-me?action=hang_up", server.takeRequest().path)
        api().holdStillOnHold("jwt", "call-1")
        val still = server.takeRequest()
        assertEquals("POST", still.method)
        assertEquals("/api/v1/me/calls/call-1/hold-for-me/still-on-hold", still.path)
        api().holdWaits("jwt")
        assertEquals("/api/v1/me/hold-waits", server.takeRequest().path)
    }

    @Test
    fun settingsRoutesAndShape() {
        server.enqueue(MockResponse().setBody("""{"greeting":"line","assistant_available":true,"max_wait_secs":10800}"""))
        val s = HoldJson.settings(api().holdSettings("jwt"))
        assertEquals(HoldSettings(HoldGreeting.LINE, true, 10800), s)
        assertEquals("/api/v1/me/hold-for-me/settings", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("""{"greeting":"assistant","assistant_available":false,"max_wait_secs":600}"""))
        api().setHoldGreeting("jwt", "silence")
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("silence", JSONObject(put.body.readUtf8()).getString("greeting"))
    }

    private fun refusalFor(code: Int, body: String): HoldRefusal {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
        try {
            api().holdStart("jwt", "call-1", null, null)
        } catch (e: PbxApiClient.HttpException) {
            return HoldJson.refusal(e.code, e.body)
        }
        fail("expected a refusal")
        throw AssertionError()
    }

    @Test
    fun refusalsMapToCopy() {
        fun reason(r: String) = """{"error":"refused","reason":"$r"}"""
        assertEquals(R.string.ui_hold_error_emergency, refusalFor(409, reason("emergency")).message)
        assertEquals(R.string.ui_hold_error_busy, refusalFor(409, reason("conference")).message)
        assertEquals(R.string.ui_hold_error_busy, refusalFor(409, reason("transfer")).message)
        assertEquals(R.string.ui_hold_error_already, refusalFor(409, reason("already_holding")).message)
        assertEquals(R.string.ui_hold_error_start, refusalFor(409, reason("not_outgoing")).message)
        assertEquals(R.string.ui_hold_error_start, refusalFor(409, reason("not_answered")).message)
        assertEquals(R.string.ui_hold_error_start, refusalFor(409, reason("something_new")).message)
        assertEquals(R.string.ui_hold_error_start, refusalFor(409, "not json").message)
        assertEquals(R.string.ui_hold_error_start, refusalFor(404, """{"error":"no"}""").message)
        assertEquals(R.string.ui_hold_error_start, refusalFor(403, """{"reason":"emergency"}""").message)
        // A network failure has no status and no body.
        assertEquals(R.string.ui_hold_error_start, HoldJson.refusal(0, null).message)
    }

    @Test
    fun holdEventParsesTheContract() {
        val text = """
            {"type":"hold_wait_state","tenant_id":"t","extension_id":"e",
             "hold_wait":{"hold_wait_id":"w-1","call_id":"call-1","state":"ringing","greeting":"assistant",
               "started_at":"2026-11-26T15:04:05Z","state_since":"2026-11-26T15:10:00Z","max_wait_secs":10800,
               "false_alarms":1,"remote":{"number":"+18005550100","name":null},"ring_reason":"human","ended_reason":null},
             "transcript":[{"speaker":"assistant","text":"I'm an automated assistant for Sam."},
                           {"speaker":"caller","text":"Hi, this is Dana from Acme."}]}
        """.trimIndent()
        val e = HoldJson.event(text) as HoldEvent.State
        val w = e.wait
        assertEquals("w-1", w.holdWaitId)
        assertEquals("call-1", w.callId)
        assertEquals("ringing", w.state)
        assertEquals(HoldGreeting.ASSISTANT, w.greeting)
        assertEquals(1_795_705_445_000L, w.startedAtMillis)
        assertEquals(1, w.falseAlarms)
        assertEquals(10800, w.maxWaitSecs)
        assertEquals("+18005550100", w.remoteNumber)
        assertNull(w.remoteName)
        assertEquals("human", w.ringReason)
        assertNull(w.endedReason)
        assertTrue(w.isOpen)
        assertEquals(
            listOf(TranscriptLine("assistant", "I'm an automated assistant for Sam."), TranscriptLine("caller", "Hi, this is Dana from Acme.")),
            e.transcript,
        )
        assertFalse(HoldJson.wait(waitJson.replace("\"waiting\"", "\"ended\"")).isOpen)
        // `requested` is shown as waiting.
        assertEquals("requested", HoldJson.wait(waitJson.replace("\"waiting\"", "\"requested\"")).state)
        // Other events and junk are not ours.
        assertNull(HoldJson.event("""{"type":"call_moved"}"""))
        assertNull(HoldJson.event("not json"))
        assertEquals(1, HoldJson.waits("[$waitJson]").size)
    }

    private fun outgoing(phase: CallPhase = CallPhase.ACTIVE, connected: Long? = 5L, dir: CallDirection = CallDirection.OUTBOUND) =
        CallUiState("k", "c", "sip:+18005550100@acme", "Acme Bank", dir, phase, connectedAtElapsed = connected)

    @Test
    fun theButtonNeedsTheFlagAnOutgoingCallAndNoConference() {
        val id = "pbx-call"
        assertTrue(HoldForMePolicy.canHold(true, outgoing(), false, id))
        assertFalse("flag off", HoldForMePolicy.canHold(false, outgoing(), false, id))
        assertFalse("incoming", HoldForMePolicy.canHold(true, outgoing(dir = CallDirection.INBOUND), false, id))
        assertFalse("in a conference", HoldForMePolicy.canHold(true, outgoing(), true, id))
        assertFalse("not connected", HoldForMePolicy.canHold(true, outgoing(phase = CallPhase.RINGING, connected = null), false, id))
        assertFalse("no PBX call", HoldForMePolicy.canHold(true, outgoing(), false, null))
    }

    @Test
    fun theDisplayNameIsTheContactNameElseTheNetworkNameElseOmitted() {
        assertEquals("Amelia", HoldForMePolicy.displayName(outgoing().copy(contactName = "Amelia")))
        assertEquals("Acme Bank", HoldForMePolicy.displayName(outgoing()))
        assertNull(HoldForMePolicy.displayName(outgoing().copy(remoteName = null)))
        assertEquals("a".repeat(64), HoldForMePolicy.displayName(outgoing().copy(remoteName = "a".repeat(80))))
    }
}
