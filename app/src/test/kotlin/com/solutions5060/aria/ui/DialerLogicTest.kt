package com.solutions5060.aria.ui

import com.solutions5060.aria.service.ConnectionStatus
import com.solutions5060.aria.ui.components.RegistrationStatus
import com.solutions5060.aria.ui.connection.gatewayHost
import com.solutions5060.aria.ui.connection.toRegistrationStatus
import com.solutions5060.aria.ui.contacts.Contact
import com.solutions5060.aria.ui.contacts.ContactsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialerLogicTest {

    private val contacts = listOf(
        Contact("Amelia Chen", "+1 (555) 201-4410"),
        Contact("Maya Patel", "1017"),
        Contact("Front Desk", "1000"),
    )

    @Test
    fun matchesFormattedAndCountryCodeVariants() {
        assertEquals("Amelia Chen", ContactsSource.match("5552014410", contacts)?.name)
        assertEquals("Amelia Chen", ContactsSource.match("+15552014410", contacts)?.name)
        assertEquals("Amelia Chen", ContactsSource.match("15552014410", contacts)?.name)
    }

    @Test
    fun extensionsMatchOnlyExactly() {
        assertEquals("Maya Patel", ContactsSource.match("1017", contacts)?.name)
        assertNull(ContactsSource.match("101", contacts))
        assertNull(ContactsSource.match("017", contacts))
    }

    @Test
    fun shortOrUnknownNumbersDoNotMatch() {
        assertNull(ContactsSource.match("", contacts))
        assertNull(ContactsSource.match("55", contacts))
        assertNull(ContactsSource.match("5559999999", contacts))
    }

    @Test
    fun digitsKeepOnlyALeadingPlus() {
        assertEquals("+15552014410", ContactsSource.digitsOf("+1 (555) 201-4410"))
        assertEquals("1234", ContactsSource.digitsOf("12+34"))
    }

    @Test
    fun everyConnectionStatusHasAChipStatus() {
        assertEquals(RegistrationStatus.Registered, ConnectionStatus.REGISTERED.toRegistrationStatus())
        assertEquals(RegistrationStatus.Connecting, ConnectionStatus.CONNECTING.toRegistrationStatus())
        assertEquals(RegistrationStatus.Error, ConnectionStatus.ERROR.toRegistrationStatus())
        assertEquals(RegistrationStatus.Offline, ConnectionStatus.OFFLINE.toRegistrationStatus())
        assertEquals(RegistrationStatus.SignedOut, ConnectionStatus.SIGNED_OUT.toRegistrationStatus())
    }

    @Test
    fun gatewayHostStripsSchemeAndPath() {
        assertEquals("push.ariaroute.com", gatewayHost("https://push.ariaroute.com/v1/register?x=1"))
        assertEquals("node2.example", gatewayHost("node2.example"))
        assertNull(gatewayHost(null))
        assertNull(gatewayHost(""))
    }
}
