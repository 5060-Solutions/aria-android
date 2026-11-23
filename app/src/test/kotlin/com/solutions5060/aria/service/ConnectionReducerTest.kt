package com.solutions5060.aria.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun coreEvents() {
        val s = ConnectionState(status = ConnectionStatus.CONNECTING)
        assertEquals(ConnectionStatus.REGISTERED, s.then(ConnectionEvent.CoreRegistration("registered", null)).status)
        assertEquals(ConnectionStatus.OFFLINE, s.then(ConnectionEvent.CoreRegistration("unregistered", null)).status)
        assertEquals(ConnectionStatus.ERROR, s.then(ConnectionEvent.CoreRegistration("failed", "503")).status)
        assertEquals(s, s.then(ConnectionEvent.CoreRegistration("something-new", null)))
    }

    @Test
    fun signedOutIgnoresConnectivityAndResets() {
        val s = ConnectionState(status = ConnectionStatus.REGISTERED, deviceId = "d").then(ConnectionEvent.SignedOut)
        assertEquals(ConnectionStatus.SIGNED_OUT, s.status)
        assertNull(s.deviceId)
        assertEquals(ConnectionStatus.SIGNED_OUT, s.then(ConnectionEvent.Online(false), ConnectionEvent.Online(true)).status)
    }
}
