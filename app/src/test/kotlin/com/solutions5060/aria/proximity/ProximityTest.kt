package com.solutions5060.aria.proximity

import com.solutions5060.aria.move.ActiveCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {

    @Test
    fun theHashIsSha256HexAndStable() {
        val a = NetworkIdentity.hash("192.168.1.57", 24, "192.168.1.1", true)
        val b = NetworkIdentity.hash("192.168.1.200", 24, "192.168.1.1", true)
        assertNotNull(a)
        assertEquals(64, a!!.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        // Two hosts on the same subnet and gateway share the identity.
        assertEquals(a, b)
    }

    @Test
    fun anotherSubnetOrGatewayIsAnotherNetwork() {
        val home = NetworkIdentity.hash("192.168.1.57", 24, "192.168.1.1", true)
        assertNotEquals(home, NetworkIdentity.hash("10.0.0.5", 24, "10.0.0.1", true))
        assertNotEquals(home, NetworkIdentity.hash("192.168.1.57", 24, "192.168.1.254", true))
        assertNotEquals(home, NetworkIdentity.hash("192.168.1.57", 16, "192.168.1.1", true))
    }

    @Test
    fun mobileDataAndUnusableAddressesHaveNoIdentity() {
        assertNull(NetworkIdentity.hash("10.20.30.40", 24, "10.20.30.1", wifiOrEthernet = false))
        assertNull(NetworkIdentity.hash(null, 24, null, true))
        assertNull(NetworkIdentity.hash("not-an-ip", 24, null, true))
        assertNull(NetworkIdentity.hash("192.168.1.5", 32, null, true))
    }

    @Test
    fun theSubnetIsMasked() {
        assertEquals("192.168.1.0", NetworkIdentity.maskedIpv4("192.168.1.57", 24))
        assertEquals("172.16.0.0", NetworkIdentity.maskedIpv4("172.16.200.3", 12))
        assertNull(NetworkIdentity.maskedIpv4("300.1.1.1", 24))
    }

    private fun call(id: String) = ActiveCall(id, "active", "inbound", "5", null, null, "reg-desk", "Desk", null)

    @Test
    fun leavingANetworkDuringAnotherDevicesCallIsReportedOncePerCall() {
        val s = HandoffSettings()
        val calls = listOf(call("a"), call("b"))
        assertEquals(listOf("a", "b"), LeftNetworkPolicy.callsToReport("h1", "h2", s, calls, emptySet()).map { it.id })
        assertEquals(listOf("b"), LeftNetworkPolicy.callsToReport("h1", null, s, calls, setOf("a")).map { it.id })
    }

    @Test
    fun nothingIsReportedWithoutALeaveOrWhenTheUserSaidNo() {
        val calls = listOf(call("a"))
        assertTrue(LeftNetworkPolicy.callsToReport(null, "h2", HandoffSettings(), calls, emptySet()).isEmpty())
        assertTrue(LeftNetworkPolicy.callsToReport("h1", "h1", HandoffSettings(), calls, emptySet()).isEmpty())
        assertTrue(LeftNetworkPolicy.callsToReport("h1", "h2", HandoffSettings(enabled = false), calls, emptySet()).isEmpty())
        assertTrue(LeftNetworkPolicy.callsToReport("h1", "h2", HandoffSettings(), emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun settingsUseTheContractsNamesAndDefaults() {
        val d = HandoffSettings()
        assertTrue(d.enabled)
        assertFalse(d.autoMoveOnLeave)
        assertFalse(d.ble)
        assertEquals(d, ProximityJson.settings("{}"))
        val s = ProximityJson.settings("""{"suggest_nearby":false,"auto_move_on_leave":true,"auto_move_on_desktop_lock":true,"ble_opt_in":true}""")
        assertEquals(HandoffSettings(enabled = false, autoMoveOnLeave = true, ble = true), s)
        val body = JSONObject(ProximityJson.settingsBody(s))
        assertEquals(setOf("suggest_nearby", "auto_move_on_leave", "ble_opt_in"), body.keys().asSequence().toSet())
        assertTrue(body.getBoolean("ble_opt_in"))
    }

    @Test
    fun theProximityBodyCarriesTheHashAndAtMost32Tokens() {
        val hash = NetworkIdentity.hash("192.168.1.5", 24, "192.168.1.1", true)
        val none = JSONObject(ProximityJson.proximityBody(hash))
        assertEquals(hash, none.getString("network_hash"))
        assertFalse(none.has("ble_seen"))
        assertFalse(JSONObject(ProximityJson.proximityBody(null)).has("network_hash"))
        val many = (1..40).map { BleSeen("%032x".format(it), -60) }
        val seen = JSONObject(ProximityJson.proximityBody(hash, many)).getJSONArray("ble_seen")
        assertEquals(32, seen.length())
        assertEquals(-60, seen.getJSONObject(0).getInt("rssi"))
        assertEquals("%032x".format(1), seen.getJSONObject(0).getString("token"))
    }

    @Test
    fun leftNetworkIsAContextEventAndItsAnswerParses() {
        assertEquals("left_network", JSONObject(ProximityJson.leftNetworkBody()).getString("event"))
        val moved = ProximityJson.contextResult("""{"action":"moved","call_id":"c","move_id":"m"}""")
        assertEquals(ContextResult("moved", "c", "m"), moved)
        assertEquals(ContextResult("none", null, null), ProximityJson.contextResult("""{"action":"none","call_id":null,"move_id":null}"""))
    }

    @Test
    fun theTokenIsTheServiceUuidAndBackAgain() {
        val hex = "00112233445566778899aabbccddeeff"
        val uuid = BleToken.toUuid(hex)!!
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", uuid.toString())
        assertEquals(hex, BleToken.fromUuid(uuid))
        assertNull(BleToken.toUuid("00112233"))
        assertNull(BleToken.toUuid(hex.uppercase()))
        assertNull(BleToken.toUuid("zz".repeat(16)))
        // A standard 16-bit service (heart rate, 0x180D) is not a token.
        assertNull(BleToken.fromUuid(java.util.UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")))
    }

    @Test
    fun onlyTheStrongest32AreReported() {
        val heard = (1..40).associate { "%032x".format(it) to -it }
        val out = BleToken.strongest(heard)
        assertEquals(32, out.size)
        assertEquals(-1, out.first().rssi)
    }

    @Test
    fun theTokenIsRefetchedJustBeforeItRotates() {
        assertEquals(BleToken.DEFAULT_ROTATE_MS, BleToken.refreshDelayMs(null, 0))
        assertEquals(50_000L, BleToken.refreshDelayMs(60_000L, 0))
        assertEquals(BleToken.MIN_REFRESH_MS, BleToken.refreshDelayMs(5_000L, 0))
    }

    @Test
    fun theTokenResponseParses() {
        val t = ProximityJson.bleToken("""{"token":"00112233445566778899aabbccddeeff","rotates_at":"2026-11-25T22:15:00Z"}""")
        assertEquals("00112233445566778899aabbccddeeff", t.hex)
        assertEquals(1_795_644_900_000L, t.expiresAtMillis)
    }
}
