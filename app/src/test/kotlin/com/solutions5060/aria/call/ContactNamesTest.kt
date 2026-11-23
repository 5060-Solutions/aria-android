package com.solutions5060.aria.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.aria_mobile.CallDirection

class ContactNamesTest {

    @Test
    fun phoneNumbersAreLookedUp() {
        assertEquals("+15550100", ContactNames.lookupNumber("sip:+15550100@pbx.example.com;user=phone"))
        assertEquals("5550100", ContactNames.lookupNumber("555-0100"))
        assertEquals("101", ContactNames.lookupNumber("sip:101@pbx"))
    }

    @Test
    fun namesAndShortCodesAreNot() {
        assertNull(ContactNames.lookupNumber("sip:alice@pbx"))
        assertNull(ContactNames.lookupNumber("sip:*9@pbx"))
        assertNull(ContactNames.lookupNumber("12"))
    }

    @Test
    fun theContactNameWinsOnScreen() {
        val base = CallUiState("k", "id", "sip:+15550100@pbx", "CALLER ID", CallDirection.INBOUND, CallPhase.INCOMING)
        assertEquals("CALLER ID", base.displayName)
        assertEquals("Ada Lovelace", base.copy(contactName = "Ada Lovelace").displayName)
        assertEquals("+15550100", base.copy(remoteName = null).displayName)
    }
}
