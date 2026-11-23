package com.solutions5060.aria.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class DiagLogTest {

    @After
    fun reset() {
        DiagLog.clear()
        DiagLog.clock = { LocalTime.now() }
    }

    @Test
    fun linesAreTimestampedAndBounded() {
        DiagLog.clock = { LocalTime.of(9, 5, 7, 123_000_000) }
        DiagLog.log("gateway", "connecting")
        assertEquals("09:05:07.123 gateway: connecting", DiagLog.lines.value.single())
        repeat(DiagLog.MAX_LINES + 5) { DiagLog.log("x", "$it") }
        assertEquals(DiagLog.MAX_LINES, DiagLog.lines.value.size)
        assertTrue(DiagLog.lines.value.last().endsWith("x: ${DiagLog.MAX_LINES + 4}"))
    }
}
