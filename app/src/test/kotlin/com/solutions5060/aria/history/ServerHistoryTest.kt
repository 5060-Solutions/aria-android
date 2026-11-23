package com.solutions5060.aria.history

import com.solutions5060.aria.pbx.ServerCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ServerHistoryTest {

    private val t0 = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli()

    private fun server(
        id: String, direction: String, from: String, to: String, disposition: String,
        started: String = "2026-11-23T10:00:00Z", duration: Int = 0, fromName: String? = null,
    ) = ServerCall(
        id = id, direction = direction, fromUri = "sip:$from@pbx", toUri = "sip:$to@pbx",
        fromNumber = from, fromName = fromName, toNumber = to, toName = null, disposition = disposition,
        startedAt = started, answeredAt = if (disposition == "answered") started else null, durationSecs = duration,
    )

    private fun local(id: String, remote: String, direction: String, at: Long, name: String? = null, missed: Boolean = false) =
        CallHistoryEntry(id, "sip:$remote@pbx", name, direction, at, 0, missed)

    @Test
    fun inboundMissedAndOutboundAnswered() {
        val missed = ServerHistory.entryFor(server("1", "inbound", "+15550100", "101", "no_answer"), "101")!!
        assertEquals("inbound", missed.direction)
        assertTrue(missed.missed)
        assertEquals("sip:+15550100", missed.remoteUri)
        val out = ServerHistory.entryFor(server("2", "outbound", "101", "+15550199", "answered", duration = 42), "101")!!
        assertEquals("outbound", out.direction)
        assertFalse(out.missed)
        assertEquals(42, out.durationSeconds)
        assertEquals("pbx-2", out.id)
    }

    @Test
    fun internalCallsTakeTheirDirectionFromThisExtension() {
        assertEquals("outbound", ServerHistory.entryFor(server("1", "internal", "101", "102", "answered"), "101")!!.direction)
        val inbound = ServerHistory.entryFor(server("2", "internal", "102", "101", "answered"), "101")!!
        assertEquals("inbound", inbound.direction)
        assertEquals("sip:102", inbound.remoteUri)
    }

    @Test
    fun aCallBothSidesRecordedAppearsOnceKeepingTheLocalEntry() {
        val l = local("L1", "+15550100", "inbound", t0 + 2_000).copy(rejected = true)
        val merged = ServerHistory.merge(
            listOf(l),
            listOf(server("1", "inbound", "+15550100", "101", "no_answer", fromName = "Ada")),
            "101",
        )
        assertEquals(1, merged.size)
        assertEquals("L1", merged.single().id)
        assertTrue(merged.single().rejected)
        assertEquals("Ada", merged.single().remoteName)
    }

    @Test
    fun e164AndNationalFormsMatch() {
        val merged = ServerHistory.merge(
            listOf(local("L1", "5550100123", "outbound", t0)),
            listOf(server("1", "outbound", "101", "+15550100123", "answered")),
            "101",
        )
        assertEquals(listOf("L1"), merged.map { it.id })
    }

    @Test
    fun callsElsewhereAndFarApartAreKeptAndSorted() {
        val merged = ServerHistory.merge(
            listOf(local("L1", "200", "outbound", t0 - 3_600_000)),
            listOf(
                server("1", "inbound", "300", "101", "answered", started = "2026-11-23T10:00:00Z", duration = 5),
                server("2", "outbound", "101", "200", "answered", started = "2026-11-23T10:00:00Z"),
            ),
            "101",
        )
        assertEquals(listOf("pbx-1", "pbx-2", "L1"), merged.map { it.id })
    }

    @Test
    fun oneServerCallCoversOnlyOneLocalEntry() {
        val merged = ServerHistory.merge(
            listOf(local("L1", "200", "outbound", t0), local("L2", "200", "outbound", t0 + 30_000)),
            listOf(server("1", "outbound", "101", "200", "answered")),
            "101",
        )
        assertEquals(setOf("L1", "L2"), merged.map { it.id }.toSet())
    }

    @Test
    fun recordsWithoutATimeAreSkipped() {
        assertEquals(null, ServerHistory.entryFor(server("1", "inbound", "1", "2", "answered", started = "garbage"), "2"))
    }
}
