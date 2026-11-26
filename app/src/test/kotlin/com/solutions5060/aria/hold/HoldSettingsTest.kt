package com.solutions5060.aria.hold

import com.solutions5060.aria.service.PbxApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Task D5: the default greeting in Settings, and the notification a missed pickup leaves. */
class HoldSettingsTest {

    private val server = MockWebServer()

    @Before fun startServer() = server.start()

    @After fun stopServer() = server.shutdown()

    /** The real routes against the mock PBX, as [LiveHoldApi] calls them. */
    private inner class MockApi : HoldApi {
        private val client = PbxApiClient(server.url("/").toString().trimEnd('/'))
        override suspend fun start(callId: String, greeting: HoldGreeting?, displayName: String?) = error("unused")
        override suspend fun cancel(callId: String, action: String) = error("unused")
        override suspend fun stillOnHold(callId: String) = error("unused")
        override suspend fun openWaits() = error("unused")
        override suspend fun settings() = HoldJson.settings(client.holdSettings("jwt"))
        override suspend fun setGreeting(g: HoldGreeting) = HoldJson.settings(client.setHoldGreeting("jwt", g.wire))
    }

    private class Mirror(var value: HoldGreeting? = null) : GreetingMirror {
        override fun get() = value
        override fun set(g: HoldGreeting) {
            value = g
        }
    }

    private fun body(g: String, assistant: Boolean = true) =
        """{"greeting":"$g","assistant_available":$assistant,"max_wait_secs":10800}"""

    @Test
    fun settingsGreetingRoundTrips() = runBlocking {
        val mirror = Mirror()
        val setting = HoldGreetingSetting(MockApi(), mirror)

        // Opening: the PBX's greeting is read and mirrored locally.
        server.enqueue(MockResponse().setBody(body("line")))
        val opened = setting.load()
        assertEquals(HoldGreeting.LINE, opened.greeting)
        assertEquals(HoldGreeting.LINE, mirror.value)
        assertEquals("GET", server.takeRequest().method)

        // A pick is mirrored and PUT; the PBX's answer is what stays.
        server.enqueue(MockResponse().setBody(body("silence")))
        assertTrue(setting.pick(HoldGreeting.SILENCE))
        assertEquals(HoldGreeting.SILENCE, mirror.value)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/me/hold-for-me/settings", put.path)
        assertEquals("silence", JSONObject(put.body.readUtf8()).getString("greeting"))

        // A refusal (500) puts the previous greeting back.
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        assertFalse(setting.pick(HoldGreeting.ASSISTANT))
        assertEquals(HoldGreeting.SILENCE, mirror.value)
        assertEquals("assistant", JSONObject(server.takeRequest().body.readUtf8()).getString("greeting"))
    }

    @Test
    fun theMirrorSurvivesAFailedRead() = runBlocking {
        val mirror = Mirror(HoldGreeting.SILENCE)
        server.enqueue(MockResponse().setResponseCode(500))
        val s = HoldGreetingSetting(MockApi(), mirror).load()
        assertEquals(HoldGreeting.SILENCE, s.greeting)
        assertFalse(s.assistantAvailable)
        assertEquals(HoldGreeting.SILENCE, mirror.value)
    }

    private fun extras(event: String = "missed", name: String = "Acme Bank", number: String = "+18005550100") = mapOf(
        "type" to "hold_wait",
        "event" to event,
        "hold_wait_id" to "w-1",
        "call_id" to "c-1",
        "remote_number" to number,
        "remote_name" to name,
    )

    @Test
    fun holdNotificationParses() {
        val n = HoldNotificationJson.parse(extras())
        assertEquals(HoldNotice("missed", "+18005550100", "Acme Bank"), n)
        assertEquals("Acme Bank", n!!.title)

        // No name: the number is the title.
        val bare = HoldNotificationJson.parse(extras(name = ""))
        assertEquals("+18005550100", bare!!.title)

        // Not ours, or incomplete.
        assertNull(HoldNotificationJson.parse(extras() + ("type" to "sms")))
        assertNull(HoldNotificationJson.parse(emptyMap()))
        assertNull(HoldNotificationJson.parse(extras(number = " ")))
        assertNull(HoldNotificationJson.parse(extras(event = "weird")))
        for (e in listOf("far_hung_up", "missed", "max_wait", "lost")) assertNotNull(HoldNotificationJson.parse(extras(event = e)))

        // Another app's extras cannot stuff the banner.
        assertEquals(64, HoldNotificationJson.parse(extras(name = "x".repeat(500)))!!.remoteName.length)
    }
}
