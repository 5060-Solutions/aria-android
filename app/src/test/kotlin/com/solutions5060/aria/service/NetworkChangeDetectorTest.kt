package com.solutions5060.aria.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkChangeDetectorTest {

    /** A clock the test advances; tasks run when their time comes. */
    private class FakeScheduler : Scheduler {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
            val cancelled = booleanArrayOf(false)
            tasks += Triple(now + delayMillis, task, cancelled)
            return Cancellable { cancelled[0] = true }
        }
        fun advance(ms: Long) {
            now += ms
            val due = tasks.filter { it.first <= now }
            tasks.removeAll(due)
            due.filterNot { it.third[0] }.forEach { it.second() }
        }
    }

    private val scheduler = FakeScheduler()
    private var notified = 0
    private val connectivity = mutableListOf<Boolean>()
    private val detector = NetworkChangeDetector(scheduler, { notified++ }, { connectivity += it }, debounceMillis = 1_000)

    private val wifi = setOf(NetTransport.WIFI)
    private val cell = setOf(NetTransport.CELLULAR)

    /** What Android reports when [net] becomes the default network. */
    private fun connect(net: String, transports: Set<NetTransport>, addresses: Set<String>) {
        detector.onAvailable(net)
        detector.onCapabilitiesChanged(net, transports, validated = true)
        detector.onLinkPropertiesChanged(net, addresses)
    }

    @Test
    fun aBurstOnConnectNotifiesOnce() {
        connect("100", wifi, setOf("192.168.1.20"))
        assertEquals(0, notified)
        scheduler.advance(999)
        assertEquals(0, notified)
        scheduler.advance(1)
        assertEquals(1, notified)
        assertEquals(listOf(true), connectivity)
    }

    @Test
    fun wifiToCellularNotifiesWhicheverOrderTheLossArrives() {
        connect("100", wifi, setOf("192.168.1.20"))
        scheduler.advance(1_000)
        // Android hands over to the new default first, then reports the old one lost.
        connect("101", cell, setOf("10.20.30.40"))
        detector.onLost("100")
        scheduler.advance(1_000)
        assertEquals(2, notified)
        assertEquals("101", detector.currentNetwork)
        // And back to Wi-Fi, this time lost first.
        detector.onLost("101")
        connect("102", wifi, setOf("192.168.1.21"))
        scheduler.advance(1_000)
        assertEquals(3, notified)
        assertEquals(listOf(true, true, false, true), connectivity)
    }

    @Test
    fun anAddressChangeOnTheSameNetworkNotifies() {
        connect("100", wifi, setOf("192.168.1.20"))
        scheduler.advance(1_000)
        detector.onLinkPropertiesChanged("100", setOf("192.168.1.20"))
        scheduler.advance(1_000)
        assertEquals("same addresses: nothing", 1, notified)
        detector.onLinkPropertiesChanged("100", setOf("192.168.7.9"))
        scheduler.advance(1_000)
        assertEquals(2, notified)
    }

    @Test
    fun capabilityNoiseDoesNotNotifyButValidationAndTransportDo() {
        detector.onAvailable("100")
        detector.onCapabilitiesChanged("100", wifi, validated = false)
        scheduler.advance(1_000)
        assertEquals(1, notified)
        // Signal strength and bandwidth updates repeat the same capabilities.
        repeat(5) { detector.onCapabilitiesChanged("100", wifi, validated = false) }
        scheduler.advance(1_000)
        assertEquals(1, notified)
        // A captive portal was signed in to.
        detector.onCapabilitiesChanged("100", wifi, validated = true)
        scheduler.advance(1_000)
        assertEquals(2, notified)
        // A VPN on the same network object.
        detector.onCapabilitiesChanged("100", wifi + NetTransport.VPN, validated = true)
        scheduler.advance(1_000)
        assertEquals(3, notified)
    }

    @Test
    fun losingTheNetworkIsAChangeToo() {
        connect("100", wifi, setOf("192.168.1.20"))
        scheduler.advance(1_000)
        detector.onLost("100")
        assertEquals(listOf(true, false), connectivity)
        scheduler.advance(1_000)
        assertEquals(2, notified)
        assertNull(detector.currentNetwork)
    }

    @Test
    fun aFlapIsOneNotification() {
        connect("100", wifi, setOf("192.168.1.20"))
        detector.onLost("100")
        connect("101", wifi, setOf("192.168.1.20"))
        scheduler.advance(1_000)
        assertEquals(1, notified)
    }

    @Test
    fun eventsForANetworkThatIsNotTheDefaultAreIgnored() {
        connect("100", wifi, setOf("192.168.1.20"))
        scheduler.advance(1_000)
        detector.onLinkPropertiesChanged("999", setOf("1.2.3.4"))
        detector.onCapabilitiesChanged("999", cell, true)
        detector.onLost("999")
        scheduler.advance(1_000)
        assertEquals(1, notified)
        assertEquals("100", detector.currentNetwork)
    }

    @Test
    fun theSameDefaultReportedAgainIsNotAChange() {
        connect("100", wifi, setOf("192.168.1.20"))
        scheduler.advance(1_000)
        detector.onAvailable("100")
        scheduler.advance(1_000)
        assertEquals(1, notified)
    }
}
