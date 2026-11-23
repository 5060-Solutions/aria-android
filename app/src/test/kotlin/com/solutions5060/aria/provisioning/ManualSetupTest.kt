package com.solutions5060.aria.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSetupTest {

    private fun valid(input: ManualSetupInput): ProvisioningCredentials {
        val r = validateManualSetup(input)
        assertTrue(r.toString(), r is ManualSetupResult.Valid)
        return (r as ManualSetupResult.Valid).credentials
    }

    private fun errors(input: ManualSetupInput) =
        (validateManualSetup(input) as ManualSetupResult.Invalid).errors

    @Test
    fun aBlankApiUrlUsesTheAriaCloud() {
        val c = valid(ManualSetupInput("Acme.sip.ariaroute.com", "101", "s3cret"))
        assertEquals(DEFAULT_API_URL, c.apiUrl)
        assertEquals("acme.sip.ariaroute.com", c.server)
        assertEquals("acme.sip.ariaroute.com", c.tenantDomain)
        assertEquals("101", c.username)
        assertEquals("tls", c.transport)
        assertEquals(5061, c.port)
    }

    @Test
    fun pastedUrlsAroundTheServerAreAccepted() {
        assertEquals("pbx.example.com", valid(ManualSetupInput("https://pbx.example.com/", "1", "p")).server)
        assertEquals("pbx.example.com", valid(ManualSetupInput("sip:pbx.example.com", "1", "p")).server)
    }

    @Test
    fun aBareApiHostGetsHttps() {
        assertEquals("https://api.example.com", valid(ManualSetupInput("a.example.com", "1", "p", "api.example.com/")).apiUrl)
        assertEquals("https://api.example.com:8443/pbx", valid(ManualSetupInput("a.example.com", "1", "p", "https://API.example.com:8443/pbx")).apiUrl)
    }

    @Test
    fun httpIsRefusedNotUpgraded() {
        assertEquals(ManualSetupError.API_URL_NOT_HTTPS, errors(ManualSetupInput("a.example.com", "1", "p", "http://api.example.com"))[ManualSetupField.API_URL])
    }

    @Test
    fun credentialsInTheApiUrlAreRefused() {
        assertEquals(ManualSetupError.API_URL_INVALID, errors(ManualSetupInput("a.example.com", "1", "p", "https://u:p@api.example.com"))[ManualSetupField.API_URL])
        assertEquals(ManualSetupError.API_URL_INVALID, errors(ManualSetupInput("a.example.com", "1", "p", "https://api.example.com/?x=1"))[ManualSetupField.API_URL])
    }

    @Test
    fun everyMissingFieldIsReported() {
        val e = errors(ManualSetupInput(" ", "", ""))
        assertEquals(ManualSetupError.SERVER_REQUIRED, e[ManualSetupField.SERVER])
        assertEquals(ManualSetupError.EXTENSION_REQUIRED, e[ManualSetupField.EXTENSION])
        assertEquals(ManualSetupError.PASSWORD_REQUIRED, e[ManualSetupField.PASSWORD])
        assertEquals(null, e[ManualSetupField.API_URL])
    }

    @Test
    fun malformedValuesAreReported() {
        val e = errors(ManualSetupInput("not a host", "10 1", "p"))
        assertEquals(ManualSetupError.SERVER_INVALID, e[ManualSetupField.SERVER])
        assertEquals(ManualSetupError.EXTENSION_INVALID, e[ManualSetupField.EXTENSION])
    }

    @Test
    fun passwordsAreKeptVerbatim() {
        assertEquals(" pass word ", valid(ManualSetupInput("a.example.com", "1", " pass word ")).password)
    }
}
