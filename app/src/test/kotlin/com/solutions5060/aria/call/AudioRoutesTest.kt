package com.solutions5060.aria.call

import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutesTest {

    @Test
    fun routeMaskListsOnlySupportedRoutes() {
        val mask = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER or CallAudioState.ROUTE_BLUETOOTH
        val state = AudioRoutes.fromRouteMask(mask, CallAudioState.ROUTE_BLUETOOTH)
        assertEquals(
            listOf(AudioRouteType.EARPIECE, AudioRouteType.BLUETOOTH, AudioRouteType.SPEAKER),
            state.options.map { it.type },
        )
        assertEquals(AudioRouteType.BLUETOOTH, state.current?.type)
        assertEquals(CallAudioState.ROUTE_SPEAKER, AudioRoutes.routeForId(state.options.last().id))
    }

    @Test
    fun unsupportedCurrentRouteIsNotSelected() {
        val state = AudioRoutes.fromRouteMask(CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_SPEAKER)
        assertNull(state.current)
        assertNull(AudioRoutes.routeForId("not-a-route"))
    }

    @Test
    fun endpointsKeepBluetoothDeviceNames() {
        val state = AudioRoutes.fromEndpoints(
            listOf(
                Triple("a", CallEndpoint.TYPE_EARPIECE, "Earpiece"),
                Triple("b", CallEndpoint.TYPE_BLUETOOTH, "Pixel Buds"),
                Triple("c", CallEndpoint.TYPE_SPEAKER, null),
                Triple("d", -1, "Unknown"),
            ),
            currentId = "c",
        )
        assertEquals(listOf("Phone", "Pixel Buds", "Speaker"), state.options.map { it.label })
        assertTrue(state.isSpeaker)
    }

    @Test
    fun speakerButtonTogglesToBestPrivateRoute() {
        val mask = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER or CallAudioState.ROUTE_WIRED_HEADSET
        val onEarpiece = AudioRoutes.fromRouteMask(mask, CallAudioState.ROUTE_EARPIECE)
        assertEquals(AudioRouteType.SPEAKER, onEarpiece.speakerToggleTarget()?.type)

        val onSpeaker = AudioRoutes.fromRouteMask(mask, CallAudioState.ROUTE_SPEAKER)
        assertEquals(AudioRouteType.WIRED_HEADSET, onSpeaker.speakerToggleTarget()?.type)

        assertNull(AudioRouteState().speakerToggleTarget())
    }
}
