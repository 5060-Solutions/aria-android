package com.solutions5060.aria.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.aria_mobile.AriaMobileEngineInterface
import java.lang.reflect.Proxy

class GatewayDeviceIdsTest {

    private val map = mutableMapOf<String, String>()
    private val ids = GatewayDeviceIds(object : GatewayDeviceIds.Store {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    })

    /** An engine that records the gateway calls made on it; anything else is unexpected. */
    private class FakeEngine {
        val calls = mutableListOf<String>()
        val engine = Proxy.newProxyInstance(
            AriaMobileEngineInterface::class.java.classLoader,
            arrayOf(AriaMobileEngineInterface::class.java),
        ) { _, method, args ->
            when (method.name) {
                "updatePushToken" -> calls += "updatePushToken(${args[0]}, ${args[1]})"
                "unregisterDevice" -> calls += "unregisterDevice(${args[0]})"
                else -> throw UnsupportedOperationException(method.name)
            }
            null
        } as AriaMobileEngineInterface
    }

    @Test
    fun beforeAnyGatewayRegistrationThePbxIdIsUsed() {
        map[GatewayDeviceIds.KEY_PBX] = "pbx-1"
        assertEquals("pbx-1", ids.gateway())
    }

    @Test
    fun theLatestRegisteredIdReplacesTheOldOneAndLeavesThePbxIdAlone() {
        map[GatewayDeviceIds.KEY_PBX] = "pbx-1"
        assertTrue(ids.onRegistered("gw-a"))
        assertFalse(ids.onRegistered("gw-a"))
        // Failover to another gateway instance.
        assertTrue(ids.onRegistered("gw-b"))
        assertEquals("gw-b", ids.gateway())
        assertEquals("pbx-1", ids.pbx())
        assertFalse(ids.onRegistered(""))
        assertEquals("gw-b", ids.gateway())
    }

    @Test
    fun pushTokensGoToTheCurrentGatewayId() {
        val fake = FakeEngine()
        assertFalse(ids.updatePushToken(fake.engine, "tok-0"))
        ids.onRegistered("gw-a")
        ids.updatePushToken(fake.engine, "tok-1")
        ids.onRegistered("gw-b")
        ids.updatePushToken(fake.engine, "tok-2")
        assertEquals(listOf("updatePushToken(gw-a, tok-1)", "updatePushToken(gw-b, tok-2)"), fake.calls)
    }

    @Test
    fun unregisterUsesTheGatewayIdAndForgetsIt() {
        val fake = FakeEngine()
        map[GatewayDeviceIds.KEY_PBX] = "pbx-1"
        ids.onRegistered("gw-b")
        ids.unregister(fake.engine)
        assertEquals(listOf("unregisterDevice(gw-b)"), fake.calls)
        assertNull(map[GatewayDeviceIds.KEY_GATEWAY])
        assertEquals("pbx-1", map[GatewayDeviceIds.KEY_PBX])
    }
}
