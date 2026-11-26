package com.solutions5060.aria.ui

import com.solutions5060.aria.pbx.DialCountry
import com.solutions5060.aria.ui.components.PhoneFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneFormatTest {

    private fun typing(s: String, country: String = "US") = PhoneFormat.asYouType(s, country)

    @Test
    fun usNationalAndTollFree() {
        assertEquals("(800) 444-4444", typing("8004444444"))
        assertEquals("(720) 555-0123", typing("7205550123"))
    }

    @Test
    fun partialTypingGrowsWithoutMangling() {
        listOf("1", "10", "100", "1001", "80044", "800444").forEach { assertEquals(it, typing(it)) }
        assertEquals("800-4444", typing("8004444"))
        assertEquals("(800) 444-444", typing("800444444"))
        // Every step keeps the typed digits.
        val full = "8004444444"
        for (n in 1..full.length) assertEquals(full.take(n), typing(full.take(n)).filter { it.isDigit() })
    }

    @Test
    fun internationalNumbers() {
        assertEquals("+44 20 7946 0958", typing("+442079460958"))
        assertEquals("+1 800-444-4444", typing("+18004444444"))
        assertEquals("+44 20 7946 0958", typing("+442079460958", "US"))
        assertEquals("+", typing("+"))
        assertEquals("+4", typing("+4"))
    }

    @Test
    fun extensionsStarCodesAndShortCodesAreNeverMangled() {
        listOf("1001", "1017", "*97", "*80101", "#", "*", "#31#", "911", "933", "711", "1", "*8100123456789").forEach {
            assertEquals(it, typing(it))
            assertEquals(it, PhoneFormat.display(it, "US"))
        }
        assertNull(PhoneFormat.hint("1001", "US"))
        assertNull(PhoneFormat.hint("*97", "US"))
        assertNull(PhoneFormat.hint("911", "US"))
    }

    @Test
    fun sanitizeStripsFormattingAndKeepsPlusStarHash() {
        assertEquals("8004444444", PhoneFormat.sanitize("(800) 444-4444"))
        assertEquals("8004444444", PhoneFormat.sanitize("800-444-4444"))
        assertEquals("8004444444", PhoneFormat.sanitize(" 800 444 4444 "))
        assertEquals("8004444444", PhoneFormat.sanitize("800.444.4444"))
        assertEquals("+442079460958", PhoneFormat.sanitize("+44 (0) 20 7946 0958".replace("(0) ", "")))
        assertEquals("+18004444444", PhoneFormat.sanitize("+1 (800) 444-4444"))
        assertEquals("*97", PhoneFormat.sanitize("*97"))
        assertEquals("#31#800", PhoneFormat.sanitize("#31# 800"))
        assertEquals("1001", PhoneFormat.sanitize("10+01"))
        assertEquals("", PhoneFormat.sanitize("abc"))
    }

    @Test
    fun formattingNeverChangesWhatIsDialed() {
        // The dialed string is the sanitized typing, whatever is shown.
        assertEquals("8004444444", PhoneFormat.sanitize(typing("8004444444")))
        assertEquals("+442079460958", PhoneFormat.sanitize(typing("+442079460958")))
    }

    @Test
    fun hintShowsInternationalFormOfAValidNationalNumber() {
        assertEquals("+1 (800) 444-4444", PhoneFormat.hint("8004444444", "US"))
        assertNull(PhoneFormat.hint("+18004444444", "US"))
        assertNull(PhoneFormat.hint("8004444", "US"))
        assertNull(PhoneFormat.hint("0000000000", "US"))
    }

    @Test
    fun nonUsDefaultCountry() {
        assertEquals("020 7946 0958", typing("02079460958", "GB"))
        assertEquals("+44 20 7946 0958", PhoneFormat.hint("02079460958", "GB"))
        assertEquals("020 7946 0958", PhoneFormat.display("+442079460958", "GB"))
        assertEquals("+1 800-444-4444", PhoneFormat.display("+18004444444", "GB"))
        assertEquals("1001", typing("1001", "GB"))
    }

    @Test
    fun unknownCountryFallsBackToUs() {
        assertEquals("(800) 444-4444", typing("8004444444", "ZZ"))
        assertEquals("(800) 444-4444", typing("8004444444", ""))
    }

    @Test
    fun listsShowNationalForTheDefaultCountryAndInternationalOtherwise() {
        assertEquals("(800) 444-4444", PhoneFormat.display("+18004444444", "US"))
        assertEquals("(800) 444-4444", PhoneFormat.display("8004444444", "US"))
        assertEquals("(800) 444-4444", PhoneFormat.display("18004444444", "US"))
        assertEquals("(800) 444-4444", PhoneFormat.display("800-444-4444", "US"))
        assertEquals("+44 20 7946 0958", PhoneFormat.display("+442079460958", "US"))
    }

    @Test
    fun listsLeaveNonNumbersAlone() {
        listOf("Front Desk", "1000", "*97", "sip:alice@example.com", "alice@example.com", "911", "", "12345", "+1", "0000000000")
            .forEach { assertEquals(it, PhoneFormat.display(it, "US")) }
    }

    @Test
    fun dialCountryPrefersPbxThenDeviceThenUs() {
        assertEquals("GB", DialCountry.effective("GB", "US"))
        assertEquals("DE", DialCountry.effective(null, "DE"))
        assertEquals("US", DialCountry.effective(null, null))
        assertEquals("FR", DialCountry.deviceRegion("fr", "US"))
        assertEquals("US", DialCountry.deviceRegion("", "US"))
        assertNull(DialCountry.deviceRegion(null, ""))
    }
}
