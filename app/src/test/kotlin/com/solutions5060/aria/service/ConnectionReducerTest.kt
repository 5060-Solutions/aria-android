package com.solutions5060.aria.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReducerTest {

    private fun ConnectionState.then(vararg events: ConnectionEvent) =
        events.fold(this) { s, e -> ConnectionReducer.reduce(s, e) }

    @Test
    fun connectingThenRegistered() {
        val s = ConnectionState().then(
            ConnectionEvent.Connecting,
            ConnectionEvent.Registered("https://push.ariaroute.com", "dev-1", 42),
        )
        assertEquals(ConnectionStatus.REGISTERED, s.status)
        assertEquals("dev-1", s.deviceId)
        assertEquals(42L, s.registeredAtElapsed)
        assertNull(s.error)
    }

    @Test
    fun aFailureWhileOnlineIsAnError() {
        val s = ConnectionState().then(ConnectionEvent.Connecting, ConnectionEvent.Failed("Login failed (401)"))
        assertEquals(ConnectionStatus.ERROR, s.status)
        assertEquals("Login failed (401)", s.error)
    }

    @Test
    fun offlineWinsOverTheFailureItCaused() {
        val s = ConnectionState().then(ConnectionEvent.Online(false), ConnectionEvent.Connecting, ConnectionEvent.Failed("timeout"))
        assertEquals(ConnectionStatus.OFFLINE, s.status)
    }

    @Test
    fun comingBackOnlineShowsConnectingUntilTheOutcome() {
        val s = ConnectionState(status = ConnectionStatus.REGISTERED).then(ConnectionEvent.Online(false))
        assertEquals(ConnectionStatus.OFFLINE, s.status)
        val back = s.then(ConnectionEvent.Online(true))
        assertEquals(ConnectionStatus.CONNECTING, back.status)
        assertEquals(ConnectionStatus.REGISTERED, back.then(ConnectionEvent.Registered(null, null, 1)).status)
    }

    @Test
    fun coreStatusesMapToTheChip() {
        val up = ConnectionState(status = ConnectionStatus.REGISTERED, deviceId = "gw-a", gatewayNode = "gw1.push.ariaroute.com:443")

        val recovering = up.then(ConnectionEvent.CoreRegistration("recovering", null, "gw-a"))
        assertEquals(ConnectionStatus.CONNECTING, recovering.status)
        assertTrue(recovering.recovering)

        val moved = recovering.then(ConnectionEvent.CoreRegistration("registered", null, "gw-b", "gw2.ariaroute.com:443"))
        assertEquals(ConnectionStatus.REGISTERED, moved.status)
        assertFalse(moved.recovering)
        assertEquals("gw-b", moved.deviceId)
        assertEquals("gw2.ariaroute.com:443", moved.gatewayNode)

        val failed = up.then(ConnectionEvent.CoreRegistration("failed", "gateway unreachable", "gw-a"))
        assertEquals(ConnectionStatus.ERROR, failed.status)
        assertEquals("gateway unreachable", failed.error)
        assertEquals("Registration failed", up.then(ConnectionEvent.CoreRegistration("failed", null)).error)

        val auth = up.then(ConnectionEvent.CoreRegistration("auth_required", null, "gw-a"))
        assertEquals(ConnectionStatus.CONNECTING, auth.status)
        assertTrue(auth.recovering)

        assertEquals(ConnectionStatus.OFFLINE, up.then(ConnectionEvent.CoreRegistration("unregistered", null)).status)
        assertEquals(up, up.then(ConnectionEvent.CoreRegistration("something-new", null)))
    }

    @Test
    fun aFailureWhileOfflineShowsOffline() {
        val s = ConnectionState(status = ConnectionStatus.REGISTERED)
            .then(ConnectionEvent.Online(false), ConnectionEvent.CoreRegistration("failed", "no route"))
        assertEquals(ConnectionStatus.OFFLINE, s.status)
    }

    @Test
    fun signedOutIgnoresConnectivityAndResets() {
        val s = ConnectionState(status = ConnectionStatus.REGISTERED, deviceId = "d").then(ConnectionEvent.SignedOut)
        assertEquals(ConnectionStatus.SIGNED_OUT, s.status)
        assertNull(s.deviceId)
        assertEquals(ConnectionStatus.SIGNED_OUT, s.then(ConnectionEvent.Online(false), ConnectionEvent.Online(true)).status)
    }
}

class ConnectRetryTest {
    @org.junit.Test
    fun backoffDoublesAndIsCapped() {
        val waits = (1..10).map { ConnectRetry.delayMs(it) }
        org.junit.Assert.assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L), waits.take(6))
        org.junit.Assert.assertTrue(waits.drop(6).all { it == ConnectRetry.MAX_MS })
        org.junit.Assert.assertEquals(ConnectRetry.MAX_MS, ConnectRetry.delayMs(1000))
    }

    @org.junit.Test
    fun aFailedCoreRecoveryShowsReconnectingNotAnError() {
        val failed = ConnectionReducer.reduce(
            ConnectionState(status = ConnectionStatus.REGISTERED),
            ConnectionEvent.CoreRegistration("failed", "timeout"),
        )
        org.junit.Assert.assertEquals(ConnectionStatus.ERROR, failed.status)
        val retry = ConnectionReducer.reduce(failed, ConnectionEvent.CoreRegistration("recovering", null))
        org.junit.Assert.assertEquals(ConnectionStatus.CONNECTING, retry.status)
        org.junit.Assert.assertTrue(retry.recovering)
        val back = ConnectionReducer.reduce(retry, ConnectionEvent.CoreRegistration("registered", null, "d1"))
        org.junit.Assert.assertEquals(ConnectionStatus.REGISTERED, back.status)
    }
}
