package com.solutions5060.aria.ui

import com.solutions5060.aria.history.CallHistoryEntry
import com.solutions5060.aria.ui.contacts.Contact
import com.solutions5060.aria.ui.contacts.ContactGrouping
import com.solutions5060.aria.ui.history.HistoryFilter
import com.solutions5060.aria.ui.history.HistoryGrouping
import com.solutions5060.aria.ui.voicemail.playerTime
import com.solutions5060.aria.ui.voicemail.speedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ListsLogicTest {

    private val zone = ZoneId.of("America/Chicago")

    private fun at(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun entry(id: String, time: Long, missed: Boolean = false, uri: String = "sip:1017@x") = CallHistoryEntry(
        id = id, remoteUri = uri, remoteName = null, direction = "inbound",
        timestamp = time, durationSeconds = if (missed) 0 else 30, missed = missed,
    )

    @Test
    fun historyGroupsByLocalDayNewestFirst() {
        val entries = listOf(
            entry("a", at(2026, 11, 22, 23)),
            entry("b", at(2026, 11, 23, 9)),
            entry("c", at(2026, 11, 23, 1)),
        )
        val days = HistoryGrouping.byDay(entries, zone)
        assertEquals(listOf(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 22)), days.map { it.date })
        assertEquals(listOf("b", "c"), days[0].entries.map { it.id })
    }

    @Test
    fun missedFilterKeepsOnlyMissed() {
        val entries = listOf(entry("a", 1, missed = true), entry("b", 2))
        assertEquals(listOf("a"), HistoryGrouping.filter(entries, HistoryFilter.Missed).map { it.id })
        assertEquals(2, HistoryGrouping.filter(entries, HistoryFilter.All).size)
    }

    @Test
    fun relativeDays() {
        val today = LocalDate.of(2026, 11, 23)
        assertEquals(HistoryGrouping.RelativeDay.Today, HistoryGrouping.relativeDay(today, today))
        assertEquals(HistoryGrouping.RelativeDay.Yesterday, HistoryGrouping.relativeDay(today.minusDays(1), today))
        assertNull(HistoryGrouping.relativeDay(today.minusDays(2), today))
    }

    @Test
    fun callsWithMatchesTheSameNumber() {
        val a = entry("a", 1, uri = "sip:1017@x")
        val b = entry("b", 3, uri = "sip:1017@y")
        val c = entry("c", 2, uri = "sip:1000@x")
        assertEquals(listOf("b", "a"), HistoryGrouping.callsWith(a, listOf(a, b, c)).map { it.id })
    }

    private val contacts = listOf(
        Contact("Zoë Müller", "+1 555 000 1111", "Mobile", contactId = 3),
        Contact("Amelia Chen", "+1 (555) 201-4410", "Mobile", starred = true, contactId = 1),
        Contact("Amelia Chen", "+1 (555) 201-4499", "Work", contactId = 1),
        Contact("123 Pizza", "+1 555 999 0000", contactId = 4),
    )

    @Test
    fun peopleGroupNumbersByContactAndSortByName() {
        val people = ContactGrouping.people(contacts)
        assertEquals(listOf("123 Pizza", "Amelia Chen", "Zoë Müller"), people.map { it.name })
        assertEquals(2, people.first { it.name == "Amelia Chen" }.numbers.size)
        assertTrue(people.first { it.name == "Amelia Chen" }.starred)
    }

    @Test
    fun searchIgnoresAccentsAndFormatting() {
        val people = ContactGrouping.people(contacts)
        assertEquals(listOf("Zoë Müller"), ContactGrouping.search(people, "zoe mull").map { it.name })
        assertEquals(listOf("Amelia Chen"), ContactGrouping.search(people, "2014499").map { it.name })
        assertEquals(people, ContactGrouping.search(people, "  "))
    }

    @Test
    fun lettersPutNonLettersLast() {
        val people = ContactGrouping.people(contacts)
        assertEquals(listOf("A", "Z", "#"), ContactGrouping.byLetter(people).map { it.first })
        assertEquals("Z", ContactGrouping.letterOf("Ženja").let { if (it == "Z") "Z" else it })
    }

    @Test
    fun playerLabels() {
        assertEquals("1×", speedLabel(1f))
        assertEquals("1.5×", speedLabel(1.5f))
        assertEquals("2×", speedLabel(2f))
        assertEquals("0:38", playerTime(38_400))
        assertEquals("1:12", playerTime(72_000))
    }
}
