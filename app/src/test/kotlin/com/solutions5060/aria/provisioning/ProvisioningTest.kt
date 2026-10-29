package com.solutions5060.aria.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class ProvisioningTest {

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Built the way aria-api does: form-encoded query pairs, standard base64 password. */
    private fun qr(
        password: String = "s3cr+t/pw==",
        extra: Map<String, String> = emptyMap(),
        omit: Set<String> = emptySet(),
    ): String {
        val params = linkedMapOf(
            "server" to "sip.acme.example",
            "user" to "1001",
            "pass" to Base64.getEncoder().encodeToString(password.toByteArray()),
            "name" to "Ada Lovelace",
            "vm" to "*97",
            "api" to "https://api.acme.example",
            "tenant" to "acme.example",
        )
        params.putAll(extra)
        omit.forEach { params.remove(it) }
        return "aria://provision?" + params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    }

    @Test
    fun parsesEveryField() {
        val creds = parseProvisioningUri(qr(extra = mapOf("transport" to "TCP", "port" to "5080")))!!
        assertEquals("sip.acme.example", creds.server)
        assertEquals("1001", creds.username)
        assertEquals("s3cr+t/pw==", creds.password)
        assertEquals("Ada Lovelace", creds.displayName)
        assertEquals("*97", creds.voicemail)
        assertEquals("https://api.acme.example", creds.apiUrl)
        assertEquals("api.acme.example", creds.apiHost)
        assertEquals("acme.example", creds.tenantDomain)
        assertEquals("tcp", creds.transport)
        assertEquals(5080, creds.port)
    }

    @Test
    fun missingTransportDefaultsToTlsOn5061() {
        val creds = parseProvisioningUri(qr())!!
        assertEquals("tls", creds.transport)
        assertEquals(5061, creds.port)
        assertNull(validateProvisioning(creds))

        val blank = parseProvisioningUri(qr(extra = mapOf("transport" to "")))!!
        assertEquals("tls", blank.transport)
    }

    @Test
    fun explicitValidTransportIsHonoured() {
        // aria-api currently emits transport=udp&port=5060; that is an explicit choice.
        val creds = parseProvisioningUri(qr(extra = mapOf("transport" to "udp", "port" to "5060")))!!
        assertEquals("udp", creds.transport)
        assertEquals(5060, creds.port)
        assertNull(validateProvisioning(creds))

        val tlsNoPort = parseProvisioningUri(qr(extra = mapOf("transport" to "tls")))!!
        assertEquals(5061, tlsNoPort.port)
        assertEquals(5060, parseProvisioningUri(qr(extra = mapOf("transport" to "tcp")))!!.port)
    }

    @Test
    fun unknownTransportIsRejectedNotDowngraded() {
        val creds = parseProvisioningUri(qr(extra = mapOf("transport" to "ws")))!!
        assertEquals("ws", creds.transport)
        assertTrue(validateProvisioning(creds)!!.contains("transport"))
    }

    @Test
    fun requiredFieldsAndSchemeAreEnforced() {
        assertNull(parseProvisioningUri(qr(omit = setOf("server"))))
        assertNull(parseProvisioningUri(qr(omit = setOf("user"))))
        assertNull(parseProvisioningUri(qr(omit = setOf("pass"))))
        assertNull(parseProvisioningUri(qr().replace("aria://", "https://")))
        assertNull(parseProvisioningUri(qr().replace("provision", "elsewhere")))
        assertNull(parseProvisioningUri("not a uri"))
        assertNull(parseProvisioningUri(qr(extra = mapOf("pass" to "%%%not-base64"))))
    }

    @Test
    fun insecureOrBogusEndpointsAreRejected() {
        fun reject(extra: Map<String, String>) =
            validateProvisioning(parseProvisioningUri(qr(extra = extra))!!)

        assertNotNull(reject(mapOf("api" to "http://api.acme.example")))
        assertNotNull(reject(mapOf("api" to "")))
        assertNotNull(reject(mapOf("api" to "https://")))
        assertNotNull(reject(mapOf("server" to "not a host")))
        assertNotNull(reject(mapOf("server" to "localhost")))
        assertNotNull(reject(mapOf("port" to "70000")))
        assertNull(reject(mapOf("server" to "10.0.0.5")))
    }

    @Test
    fun firstValueOfARepeatedKeyWins() {
        val creds = parseProvisioningUri(qr() + "&user=9999")!!
        assertEquals("1001", creds.username)
    }

    @Test
    fun defaultPortFollowsTransport() {
        assertEquals(5061, defaultSipPort("TLS"))
        assertEquals(5060, defaultSipPort("udp"))
        assertEquals("tls", DEFAULT_SIP_TRANSPORT)
    }
}
