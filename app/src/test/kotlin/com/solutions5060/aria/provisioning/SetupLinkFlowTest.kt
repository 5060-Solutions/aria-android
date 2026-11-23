package com.solutions5060.aria.provisioning

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.aria_mobile.ProvisionException
import uniffi.aria_mobile.ProvisionPreview
import uniffi.aria_mobile.ProvisionedAccount

class SetupLinkFlowTest {

    private val v1 = "aria://provision?v=1&url=https%3A%2F%2Fapi.ariaroute.com%2Fprovision%2Fsoftphone%2Ftok"

    private val preview = ProvisionPreview(
        tenantName = "Acme", extension = "101", displayName = "Ada", domain = "acme.sip.ariaroute.com",
        expiresAt = null, serverHost = "api.ariaroute.com",
    )

    private fun account(transport: String = "tls", port: UShort = 0u, proxy: String? = null) = ProvisionedAccount(
        username = "101", authUsername = null, password = "pw", domain = "acme.sip.ariaroute.com",
        transport = transport, port = port, outboundProxy = proxy, voicemail = "*97", srtp = "sdes",
        tenantName = "Acme", displayName = "Ada", serverHost = "api.ariaroute.com",
    )

    @Test
    fun v1PreviewsThenRedeemsThroughTheCore() = runBlocking {
        var redeemed = 0
        val flow = SetupLinkFlow(preview = { preview }, redeemLink = { redeemed++; account() })
        val prepared = flow.prepare(v1)
        assertTrue(prepared is PreparedSetupLink.V1)
        assertEquals("api.ariaroute.com", prepared.summary.serverHost)
        assertEquals("Acme", prepared.summary.tenantName)
        assertEquals(0, redeemed)
        val creds = flow.redeem(prepared)
        assertEquals(1, redeemed)
        assertEquals("https://api.ariaroute.com", creds.apiUrl)
        assertEquals("acme.sip.ariaroute.com", creds.tenantDomain)
        assertEquals("101", creds.username)
        assertEquals("*97", creds.voicemail)
        assertEquals(5061, creds.port)
    }

    @Test
    fun coreErrorsMapToScreenErrors() = runBlocking {
        val cases = mapOf(
            ProvisionException.Expired("x") to SetupLinkError.EXPIRED,
            ProvisionException.Network("x") to SetupLinkError.NETWORK,
            ProvisionException.Server("x") to SetupLinkError.SERVER,
            ProvisionException.InvalidLink("x") to SetupLinkError.INVALID,
        )
        for ((thrown, expected) in cases) {
            try {
                SetupLinkFlow(preview = { throw thrown }).prepare(v1)
                fail("expected $expected")
            } catch (e: SetupLinkException) {
                assertEquals(expected, e.error)
            }
        }
        val flow = SetupLinkFlow(preview = { preview }, redeemLink = { throw ProvisionException.Mismatch("x") })
        try {
            flow.redeem(flow.prepare(v1))
            fail()
        } catch (e: SetupLinkException) {
            assertEquals(SetupLinkError.MISMATCH, e.error)
        }
    }

    @Test
    fun legacyLinksAreParsedLocallyWithoutTheCore() = runBlocking {
        val flow = SetupLinkFlow(preview = { fail("core called"); preview }, redeemLink = { fail(); account() })
        val legacy = "aria://provision?server=sip.example.com&user=200&pass=cHc%3D&api=https%3A%2F%2Fapi.example.com&tenant=t.example.com"
        val prepared = flow.prepare(legacy)
        assertTrue(prepared is PreparedSetupLink.Legacy)
        assertEquals("api.example.com", prepared.summary.serverHost)
        assertEquals("pw", flow.redeem(prepared).password)
    }

    @Test
    fun legacyLinksToHttpAreRefused() = runBlocking {
        val legacy = "aria://provision?server=sip.example.com&user=200&pass=cHc%3D&api=http%3A%2F%2Fapi.example.com"
        try {
            SetupLinkFlow().prepare(legacy)
            fail()
        } catch (e: SetupLinkException) {
            assertEquals(SetupLinkError.INSECURE, e.error)
        }
    }

    @Test
    fun accountMappingHandlesProxyPortAndAutoTransport() {
        val c = SetupLinkFlow.credentialsFrom(account(transport = "auto", proxy = "edge.example.com:5070"))
        assertEquals("tls", c.transport)
        assertEquals("edge.example.com", c.server)
        assertEquals(5070, c.port)
        assertFalse(c.apiUrl.startsWith("http:"))
        assertEquals(5080, SetupLinkFlow.credentialsFrom(account(transport = "udp", port = 5080u)).port)
    }
}
