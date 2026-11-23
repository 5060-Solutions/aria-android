package com.solutions5060.aria.ui

import com.solutions5060.aria.service.ConnectionState
import com.solutions5060.aria.service.ConnectionStatus
import com.solutions5060.aria.ui.call.CallQuality
import com.solutions5060.aria.ui.call.CallQualityGrader
import com.solutions5060.aria.ui.call.MediaSample
import com.solutions5060.aria.ui.call.codecDisplayName
import com.solutions5060.aria.ui.components.RegistrationStatus
import com.solutions5060.aria.ui.connection.nodeHost
import com.solutions5060.aria.ui.connection.toRegistrationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallQualityTest {

    private fun s(rx: Long, lost: Long, jitter: Double = 5.0, rtt: Double = 80.0) = MediaSample(rx, lost, jitter, rtt)

    @Test
    fun needsTwoSamplesWithTraffic() {
        assertNull(CallQualityGrader.grade(null, s(100, 0)))
        assertNull(CallQualityGrader.grade(s(100, 0), null))
        assertNull(CallQualityGrader.grade(s(100, 0), s(100, 0)))
    }

    @Test
    fun gradesLossJitterAndDelay() {
        assertEquals(CallQuality.Good, CallQualityGrader.grade(s(0, 0), s(100, 1)))
        assertEquals(CallQuality.Fair, CallQualityGrader.grade(s(0, 0), s(97, 3)))
        assertEquals(CallQuality.Poor, CallQualityGrader.grade(s(0, 0), s(90, 10)))
        assertEquals(CallQuality.Fair, CallQualityGrader.grade(s(0, 0), s(100, 0, jitter = 45.0)))
        assertEquals(CallQuality.Poor, CallQualityGrader.grade(s(0, 0), s(100, 0, rtt = 700.0)))
    }

    @Test
    fun lossCountersThatResetDoNotGoNegative() {
        assertEquals(CallQuality.Good, CallQualityGrader.grade(s(0, 50), s(100, 0)))
    }

    @Test
    fun recoveringConnectingReadsReconnecting() {
        assertEquals(
            RegistrationStatus.Reconnecting,
            ConnectionState(status = ConnectionStatus.CONNECTING, recovering = true).toRegistrationStatus(),
        )
        assertEquals(RegistrationStatus.Connecting, ConnectionState(status = ConnectionStatus.CONNECTING).toRegistrationStatus())
        assertEquals(
            RegistrationStatus.Registered,
            ConnectionState(status = ConnectionStatus.REGISTERED, recovering = true).toRegistrationStatus(),
        )
    }

    @Test
    fun gatewayNodeShowsTheHostOnly() {
        assertEquals("gw2.ariaroute.com", nodeHost("gw2.ariaroute.com:443"))
        assertEquals("10.0.0.7", nodeHost("10.0.0.7:8443"))
        assertEquals("[2001:db8::1]", nodeHost("[2001:db8::1]:443"))
        assertEquals("gw1", nodeHost("gw1"))
        assertNull(nodeHost(" "))
        assertNull(nodeHost(null))
    }

    @Test
    fun codecNames() {
        assertEquals("Opus", codecDisplayName("opus"))
        assertEquals("G.711 µ-law", codecDisplayName("PCMU"))
        assertEquals("G.711 A-law", codecDisplayName("PCMA"))
        assertEquals("X-Foo", codecDisplayName("X-Foo"))
    }
}
