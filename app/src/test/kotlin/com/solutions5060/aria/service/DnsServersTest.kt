package com.solutions5060.aria.service

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsServersTest {
    @Test
    fun scopeIdsAreKept() {
        assertEquals(listOf("192.168.1.1", "fe80::1%wlan0"), DnsServers.format(listOf("192.168.1.1", "fe80::1%wlan0")))
    }
}
