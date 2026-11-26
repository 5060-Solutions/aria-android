package com.solutions5060.aria.move

import com.solutions5060.aria.service.PbxApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CallMoveTest {

    private val activeJson = """
        [{"id":"call-1","state":"active","direction":"inbound",
          "remote":{"number":"+13035550100","name":null},
          "started_at":"2026-11-25T22:00:00Z",
          "device":{"registration_id":"reg-desk","label":"Desk phone","user_agent":"Yealink","transport":"tls"},
          "sip_call_id":"abc@desk"}]
    """.trimIndent()

    private fun call(
        id: String = "call-1",
        reg: String = "reg-desk",
        number: String = "+13035550100",
        sip: String? = "abc@desk",
    ) = ActiveCall(id, "active", "inbound", number, null, 1_000L, reg, "Desk phone", sip)

    // ── JSON ───────────────────────────────────────────────────────────

    @Test
    fun activeCallsParseTheContractShape() {
        val calls = MoveJson.activeCalls(activeJson)
        assertEquals(1, calls.size)
        val c = calls[0]
        assertEquals("call-1", c.id)
        assertEquals("+13035550100", c.remoteNumber)
        assertNull(c.remoteName)
        assertEquals("+13035550100", c.displayName)
        assertEquals("Desk phone", c.deviceLabel)
        assertEquals("reg-desk", c.deviceRegistrationId)
        assertEquals("abc@desk", c.sipCallId)
        assertEquals(1_795_644_000_000L, c.startedAtMillis)
    }

    @Test
    fun aMissingStartTimeIsNullNotAnError() {
        val c = MoveJson.activeCalls("""[{"id":"x","remote":{"number":"1"},"device":{"registration_id":"r"},"started_at":"soon"}]""")[0]
        assertNull(c.startedAtMillis)
    }

    @Test
    fun devicesParse() {
        val list = MoveJson.devices("""[{"registration_id":"r1","label":"Aria iOS","user_agent":"ua","transport":"tls","online":true},{"registration_id":"r2","label":"Old","online":false}]""")
        assertEquals(listOf(true, false), list.map { it.online })
        assertEquals("Aria iOS", list[0].label)
    }

    @Test
    fun webSocketEventsParse() {
        val active = MoveJson.event("""{"type":"call_active_elsewhere","call":{"id":"c","remote":{"number":"5"},"device":{"registration_id":"r","label":"L"}}}""")
        assertTrue(active is MoveEvent.CallActiveElsewhere)
        val moved = MoveJson.event("""{"type":"call_moved","call_id":"c","from_registration_id":"a","to_registration_id":"b"}""")
        assertEquals(MoveEvent.CallMoved("c", "a", "b"), moved)
        assertEquals(MoveEvent.CallEnded("c"), MoveJson.event("""{"type":"call_ended","call_id":"c"}"""))
        assertTrue(MoveJson.event("""{"type":"handoff_suggested","call_id":"c","to_registration_id":"r","reason":"nearby"}""") == MoveEvent.HandoffSuggested("c", "r", "nearby"))
        assertNull(MoveJson.event("""{"type":"sms_received"}"""))
        assertNull(MoveJson.event("not json"))
    }

    @Test
    fun moveIdIsRequired() {
        assertEquals("m-1", MoveJson.moveId("""{"move_id":"m-1"}"""))
        try {
            MoveJson.moveId("{}")
            fail()
        } catch (_: IllegalStateException) {
        }
    }

    // ── The auto-answer rule ───────────────────────────────────────────

    private val pending = PendingMove("move-1", "call-1", 5_000L, requestedAtMillis = 100_000L)

    @Test
    fun anInviteWithoutTheHeaderRings() {
        assertEquals(MoveDecision.Ring, CallMovePolicy.decide(null, pending, 101_000L))
        assertEquals(MoveDecision.Ring, CallMovePolicy.decide("", pending, 101_000L))
    }

    @Test
    fun theMatchingMoveIdIsAnsweredWithTheOriginalStart() {
        assertEquals(MoveDecision.AutoAnswer(5_000L), CallMovePolicy.decide("move-1", pending, 101_000L))
        assertEquals(MoveDecision.AutoAnswer(5_000L), CallMovePolicy.decide("MOVE-1", pending, 101_000L))
    }

    @Test
    fun aMoveIdThatIsNotOursRingsAsMoving() {
        assertEquals(MoveDecision.RingMoving, CallMovePolicy.decide("someone-else", pending, 101_000L))
        assertEquals(MoveDecision.RingMoving, CallMovePolicy.decide("move-1", null, 101_000L))
    }

    @Test
    fun anExpiredRequestIsNotAnsweredAutomatically() {
        val late = 100_000L + CallMovePolicy.PENDING_TTL_MS + 1
        assertEquals(MoveDecision.RingMoving, CallMovePolicy.decide("move-1", pending, late))
    }

    // ── Banner and own call ────────────────────────────────────────────

    @Test
    fun theBannerListsCallsOnOtherDevices() {
        val banner = CallMovePolicy.bannerCalls(listOf(call()), "reg-phone", emptySet(), emptySet())
        assertEquals(1, banner.size)
    }

    @Test
    fun theAppsOwnCallIsIgnoredByRegistration() {
        assertTrue(CallMovePolicy.bannerCalls(listOf(call(reg = "reg-phone")), "reg-phone", emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun theAppsOwnCallIsIgnoredByCallId() {
        assertTrue(CallMovePolicy.bannerCalls(listOf(call()), "reg-phone", setOf("abc@desk"), emptySet()).isEmpty())
    }

    @Test
    fun withoutARegistrationALiveLocalCallToTheSameNumberIsOwn() {
        assertTrue(CallMovePolicy.bannerCalls(listOf(call()), null, emptySet(), setOf("sip:3035550100@pbx")).isEmpty())
        assertEquals(1, CallMovePolicy.bannerCalls(listOf(call()), null, emptySet(), setOf("sip:999@pbx")).size)
    }

    @Test
    fun aDismissedCallStaysHidden() {
        assertTrue(CallMovePolicy.bannerCalls(listOf(call()), "r", emptySet(), emptySet(), dismissedIds = setOf("call-1")).isEmpty())
    }

    @Test
    fun ownCallFindsTheLegOnThisRegistrationFirst() {
        val mine = call(id = "mine", reg = "reg-phone")
        assertEquals("mine", CallMovePolicy.ownCall(listOf(call(), mine), "reg-phone", emptySet(), null)?.id)
        assertEquals("call-1", CallMovePolicy.ownCall(listOf(call()), null, emptySet(), "3035550100")?.id)
        assertNull(CallMovePolicy.ownCall(listOf(call(), call(id = "two")), null, emptySet(), "3035550100"))
    }

    @Test
    fun moveTargetsAreOnlineAndNotWhereTheCallIs() {
        val devices = listOf(
            MoveDevice("a", "Desk", null, null, true),
            MoveDevice("b", "Phone", null, null, true),
            MoveDevice("c", "Offline", null, null, false),
            MoveDevice("d", "Tablet", null, null, true),
        )
        assertEquals(listOf("d"), CallMovePolicy.targets(devices, "a", "b").map { it.registrationId })
    }

    @Test
    fun numbersMatchAcrossFormats() {
        assertTrue(CallMovePolicy.sameNumber("+13035550100", "sip:3035550100@pbx.example"))
        assertFalse(CallMovePolicy.sameNumber("+13035550100", "+13035550101"))
        assertFalse(CallMovePolicy.sameNumber("", "5"))
    }

    // ── The REST calls, against a mocked server ────────────────────────

    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun api() = PbxApiClient(server.url("/").toString().trimEnd('/'))

    @Test
    fun moveHerePostsTheOwnRegistrationAndReturnsTheMoveId() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"move_id":"m-9"}"""))
        val body = api().moveCall("jwt", "call-1", "reg-phone")
        assertEquals("m-9", MoveJson.moveId(body))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/me/calls/call-1/move", req.path)
        assertEquals("Bearer jwt", req.getHeader("Authorization"))
        assertEquals("reg-phone", JSONObject(req.body.readUtf8()).getString("to_registration_id"))
    }

    @Test
    fun currentDeviceIsLookedUpBySipInstance() {
        server.enqueue(MockResponse().setBody("""{"registration_id":"reg-phone","label":"Aria Android","online":true}"""))
        val device = MoveJson.device(api().currentDevice("jwt", "urn:uuid:1234"))
        assertEquals("reg-phone", device?.registrationId)
        assertEquals("/api/v1/me/devices/current?sip_instance=urn%3Auuid%3A1234", server.takeRequest().path)
    }

    @Test
    fun theProximityRoutesNameThisDeviceInTheQuery() {
        val urn = "urn:uuid:1234"
        val q = "?sip_instance=urn%3Auuid%3A1234"
        repeat(5) { server.enqueue(MockResponse().setBody("{}")) }
        val client = api()
        client.devices("jwt", urn)
        client.reportProximity("jwt", urn, """{"network_hash":"ab"}""")
        client.postContext("jwt", urn, """{"event":"left_network"}""")
        client.bleToken("jwt", urn)
        client.callHandoff("jwt")
        assertEquals("/api/v1/me/devices$q", server.takeRequest().path)
        val prox = server.takeRequest()
        assertEquals("POST /api/v1/me/devices/current/proximity$q", "${prox.method} ${prox.path}")
        val ctx = server.takeRequest()
        assertEquals("POST /api/v1/me/devices/current/context$q", "${ctx.method} ${ctx.path}")
        assertEquals("left_network", JSONObject(ctx.body.readUtf8()).getString("event"))
        assertEquals("/api/v1/me/devices/current/ble-token$q", server.takeRequest().path)
        assertEquals("/api/v1/me/call-handoff", server.takeRequest().path)
    }

    @Test
    fun refusalsKeepTheirStatusCode() {
        for (code in listOf(404, 409, 422)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":"no"}"""))
            try {
                api().moveCall("jwt", "c", "r")
                fail()
            } catch (e: PbxApiClient.HttpException) {
                assertEquals(code, e.code)
            }
        }
    }

    @Test
    fun activeCallsAreReadFromTheMeRoute() {
        server.enqueue(MockResponse().setBody(activeJson))
        val calls = MoveJson.activeCalls(api().activeCalls("jwt"))
        assertNotNull(calls.firstOrNull())
        assertEquals("/api/v1/me/calls/active", server.takeRequest().path)
    }
}
