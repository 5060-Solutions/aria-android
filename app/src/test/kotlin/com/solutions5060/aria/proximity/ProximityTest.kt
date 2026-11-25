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
        assertTrue(LeftNetworkPolicy.callsToReport("h1", "h2", HandoffSettings(network = false), calls, emptySet()).isEmpty())
        assertTrue(LeftNetworkPolicy.callsToReport("h1", "h2", HandoffSettings(), emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun bluetoothIsOffByDefault() {
        assertFalse(HandoffSettings().ble)
        assertFalse(ProximityJson.settings("{}").ble)
        assertTrue(ProximityJson.settings("""{"enabled":true,"network":false,"ble":true}""").ble)
    }

    @Test
    fun theBodiesCarryTheEventAndTheHash() {
        val left = JSONObject(ProximityJson.leftNetworkBody("reg", "call"))
        assertEquals("left_network", left.getString("type"))
        assertEquals("call", left.getString("call_id"))
        assertTrue(JSONObject(ProximityJson.networkBody("reg", null)).isNull("network_hash"))
        assertEquals(2, JSONObject(ProximityJson.bleSeenBody("reg", listOf("aa", "bb"))).getJSONArray("tokens").length())
    }

    @Test
    fun bleTokensRoundTripAndAreBounded() {
        val bytes = BleToken.toBytes("00ff10a5")
        assertEquals("00ff10a5", BleToken.toHex(bytes!!))
        assertNull(BleToken.toBytes(""))
        assertNull(BleToken.toBytes("abc"))
        assertNull(BleToken.toBytes("zz"))
        assertNull(BleToken.toBytes("00".repeat(BleToken.MAX_BYTES + 1)))
    }

    @Test
    fun theTokenIsRefetchedJustBeforeItExpires() {
        assertEquals(BleToken.DEFAULT_ROTATE_MS, BleToken.refreshDelayMs(null, 0))
        assertEquals(50_000L, BleToken.refreshDelayMs(60_000L, 0))
        assertEquals(BleToken.MIN_REFRESH_MS, BleToken.refreshDelayMs(5_000L, 0))
    }

    @Test
    fun theTokenResponseParses() {
        val t = ProximityJson.bleToken("""{"token":"0a0b","expires_at":"2026-11-25T22:00:00Z"}""")
        assertEquals("0a0b", t.hex)
        assertEquals(1_795_644_000_000L, t.expiresAtMillis)
    }
}
