package com.solutions5060.aria.call

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyRoutingTest {

    private val original = EmergencyRouting.coreCheck

    @After
    fun restore() {
        EmergencyRouting.coreCheck = original
    }

    @Test
    fun theCoreDecidesWhenItIsLoaded() {
        EmergencyRouting.coreCheck = { it == "42" }
        assertTrue(EmergencyRouting.isEmergency("42"))
        assertFalse(EmergencyRouting.isEmergency("911"))
    }

    @Test
    fun aMissingCoreFailsSafe() {
        // On the JVM the native library is not loaded, so the real check throws.
        EmergencyRouting.coreCheck = { throw UnsatisfiedLinkError("no core") }
        for (n in listOf("911", "112", "999", "000", " 9-1-1 ", "tel:112", "sip:911@pbx.example.com", "sips:999;user=phone")) {
            assertTrue(n, EmergencyRouting.isEmergency(n))
        }
        for (n in listOf("9111", "1000", "", "sip:alice@example.com", "+1911")) {
            assertFalse(n, EmergencyRouting.isEmergency(n))
        }
    }

    @Test
    fun normaliseStripsSchemeHostParamsAndSeparators() {
        assertEquals("911", EmergencyRouting.normalise("sip:9 1-1@pbx.example.com;transport=tls"))
        assertEquals("112", EmergencyRouting.normalise("TEL:112"))
    }
}
