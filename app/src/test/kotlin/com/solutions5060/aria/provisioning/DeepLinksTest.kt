package com.solutions5060.aria.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinksTest {

    private val v1 = "aria://provision?v=1&url=https%3A%2F%2Fapi.ariaroute.com%2Fprovision%2Fsoftphone%2Ftok"

    @Test
    fun provisionLinks() {
        assertEquals(DeepLink.Provision(v1), DeepLinks.parse(DeepLinks.ACTION_VIEW, v1))
        assertTrue(DeepLinks.isV1ProvisionLink(v1))
        val legacy = "aria://provision?server=s.example.com&user=1&pass=cA=="
        assertTrue(DeepLinks.parse(DeepLinks.ACTION_VIEW, legacy) is DeepLink.Provision)
        assertFalse(DeepLinks.isV1ProvisionLink(legacy))
        assertFalse(DeepLinks.isV1ProvisionLink("aria://provision?v=10&url=x"))
        assertNull(DeepLinks.parse(DeepLinks.ACTION_VIEW, "aria://other?v=1"))
        // Only VIEW opens a setup link.
        assertNull(DeepLinks.parse(DeepLinks.ACTION_DIAL, v1))
    }

    @Test
    fun telNumbersLoseSeparatorsAndParams() {
        assertEquals(DeepLink.Dial("+15550100"), DeepLinks.parse(DeepLinks.ACTION_DIAL, "tel:+1 (555) 01-00"))
        assertEquals(DeepLink.Dial("+15550100"), DeepLinks.parse(DeepLinks.ACTION_VIEW, "tel:%2B1.555.0100;phone-context=example.com"))
        assertEquals(DeepLink.Dial("*72#"), DeepLinks.parse(DeepLinks.ACTION_VIEW, "tel:*72%23"))
        assertEquals(DeepLink.Dial("911"), DeepLinks.parse(DeepLinks.ACTION_DIAL, "tel:911"))
    }

    @Test
    fun junkTelIsIgnored() {
        assertNull(DeepLinks.parse(DeepLinks.ACTION_VIEW, "tel:"))
        assertNull(DeepLinks.parse(DeepLinks.ACTION_VIEW, "tel:call-me-maybe"))
        assertNull(DeepLinks.parse(DeepLinks.ACTION_VIEW, "tel:" + "1".repeat(80)))
    }

    @Test
    fun sipUrisKeepUserAndHostOnly() {
        assertEquals(DeepLink.Dial("sip:alice@pbx.example.com"), DeepLinks.parse(DeepLinks.ACTION_VIEW, "sip:alice@PBX.example.com;transport=tls?subject=x"))
        assertEquals(DeepLink.Dial("sip:100@pbx.example.com:5061"), DeepLinks.parse(DeepLinks.ACTION_VIEW, "sips:100@pbx.example.com:5061"))
        assertNull(DeepLinks.parse(DeepLinks.ACTION_VIEW, "sip:pbx.example.com"))
        assertNull(DeepLinks.parse(DeepLinks.ACTION_VIEW, "sip:a b@x y"))
    }

    @Test
    fun otherIntentsAreIgnored() {
        assertNull(DeepLinks.parse("android.intent.action.MAIN", null))
        assertNull(DeepLinks.parse(DeepLinks.ACTION_VIEW, "https://example.com"))
        assertNull(DeepLinks.parse("android.intent.action.CALL", "tel:123"))
    }

    @Test
    fun consumeKeepsANewerLink() {
        val a = DeepLink.Dial("1")
        val b = DeepLink.Dial("2")
        DeepLinks.offer(a)
        DeepLinks.offer(b)
        DeepLinks.consume(a)
        assertEquals(b, DeepLinks.pending.value)
        DeepLinks.consume(b)
        assertNull(DeepLinks.pending.value)
    }
}

class OpenTabTest {
    @org.junit.Test
    fun onlyKnownTabsAreRoutes() {
        org.junit.Assert.assertEquals(
            setOf("dialer", "contacts", "history", "voicemail", "settings"),
            DeepLink.OpenTab.ROUTES,
        )
    }
}
