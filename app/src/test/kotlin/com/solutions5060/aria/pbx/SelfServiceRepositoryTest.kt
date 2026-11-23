package com.solutions5060.aria.pbx

import com.solutions5060.aria.service.PbxApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SelfServiceRepositoryTest {

    private val server = MockWebServer()
    private var relogins = 0

    private val auth = object : PbxAuth {
        var jwt: String? = "old-jwt"
        override fun savedJwt() = jwt
        override fun relogin(): String {
            relogins++
            jwt = "new-jwt"
            return "new-jwt"
        }
        override fun extensionId() = "ext-uuid"
        override fun tenantId() = "tenant-uuid"
    }

    private lateinit var repo: SelfServiceRepository

    @Before
    fun setUp() {
        server.start()
        repo = SelfServiceRepository(PbxApiClient(server.url("").toString().trimEnd('/')), auth)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    private val forwardJson = """{"id":"f1","extension_id":"ext-uuid","tenant_id":"t","forward_type":"no_answer",
        "destination":"+15550100","ring_timeout_secs":25,"enabled":true,"created_at":"2026-11-01T10:00:00Z"}"""

    @Test
    fun readsFeaturesWithTheJwt() {
        server.enqueue(json(200, """{"id":"x","extension_id":"ext-uuid","tenant_id":"t","do_not_disturb":true,
            "music_on_hold_url":null,"max_concurrent_calls":2,"hotdesk_enabled":false,"updated_at":"2026-11-01T10:00:00Z"}"""))
        val f = repo.features()
        assertTrue(f.doNotDisturb)
        assertEquals(2, f.maxConcurrentCalls)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/extensions/ext-uuid/features", req.path)
        assertEquals("Bearer old-jwt", req.getHeader("Authorization"))
    }

    @Test
    fun setsOnlyDndBecauseTheRestIsTheAdministrators() {
        server.enqueue(MockResponse().setResponseCode(204))
        repo.setDoNotDisturb(true)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/extensions/ext-uuid/features", req.path)
        // The PBX refuses unknown fields with 422 and admin fields with 403.
        assertEquals(setOf("do_not_disturb"), JSONObject(req.body.readUtf8()).keys().asSequence().toSet())
    }

    @Test
    fun anExpiredJwtIsRenewedOnceAndRetried() {
        server.enqueue(json(401, """{"error":"expired","status":401}"""))
        server.enqueue(json(200, "[]"))
        assertEquals(emptyList<CallForward>(), repo.forwards())
        assertEquals(1, relogins)
        assertEquals("Bearer old-jwt", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new-jwt", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun listsForwardsSkippingTypesItDoesNotKnow() {
        server.enqueue(json(200, "[$forwardJson, " + forwardJson.replace("no_answer", "time_of_day").replace("f1", "f2") + "]"))
        val list = repo.forwards()
        assertEquals(1, list.size)
        assertEquals(CallForward("f1", ForwardType.NO_ANSWER, "+15550100", 25, true), list.single())
        assertEquals("/api/v1/extensions/ext-uuid/forwards", server.takeRequest().path)
    }

    @Test
    fun createsAForwardThatDoesNotExistYet() {
        server.enqueue(json(201, forwardJson))
        val saved = repo.saveForward(ForwardType.NO_ANSWER, null, " +15550100 ", 25, true)
        assertEquals("f1", saved?.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/extensions/ext-uuid/forwards", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("no_answer", body.getString("forward_type"))
        assertEquals("+15550100", body.getString("destination"))
        assertEquals(25, body.getInt("ring_timeout_secs"))
        assertTrue(body.getBoolean("enabled"))
        assertEquals("tenant-uuid", body.getString("tenant_id"))
    }

    @Test
    fun updatesAnExistingForwardInPlace() {
        server.enqueue(json(200, forwardJson.replace("\"enabled\":true", "\"enabled\":false")))
        val existing = CallForward("f1", ForwardType.NO_ANSWER, "+15550100", 25, true)
        val saved = repo.saveForward(ForwardType.NO_ANSWER, existing, "+15550100", 25, false)
        assertFalse(saved!!.enabled)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/forwards/f1", req.path)
        assertFalse(JSONObject(req.body.readUtf8()).getBoolean("enabled"))
    }

    @Test
    fun anEmptyDestinationDeletes() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertNull(repo.saveForward(ForwardType.BUSY, CallForward("f9", ForwardType.BUSY, "200", 20, true), "  ", 20, true))
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/v1/forwards/f9", req.path)
    }

    @Test
    fun thePbxsReasonIsKeptWithTheFieldItNamed() {
        server.enqueue(json(422, """{"error":"This extension cannot forward there: international calls are off.","status":422,"field":"destination","profile":true}"""))
        try {
            repo.saveForward(ForwardType.UNCONDITIONAL, null, "+4420", 20, true)
            fail()
        } catch (e: SelfServiceException) {
            assertEquals(422, e.code)
            assertEquals("destination", e.field)
            assertEquals("This extension cannot forward there: international calls are off.", e.message)
        }
    }

    @Test
    fun readsServerCallsWithALimit() {
        server.enqueue(json(200, """[{"id":"c1","direction":"inbound","from_uri":"sip:+15550100@pbx","to_uri":"sip:101@pbx",
            "from_number":"+15550100","from_name":"Ada","to_number":"101","to_name":null,"disposition":"no_answer",
            "started_at":"2026-11-23T10:00:00Z","answered_at":null,"ended_at":"2026-11-23T10:00:30Z","duration_secs":0}]"""))
        val calls = repo.serverCalls(500)
        assertEquals("Ada", calls.single().fromName)
        assertNull(calls.single().toName)
        assertEquals("/api/v1/extensions/ext-uuid/cdrs?limit=200", server.takeRequest().path)
    }
}
