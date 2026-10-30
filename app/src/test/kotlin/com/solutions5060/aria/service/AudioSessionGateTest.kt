package com.solutions5060.aria.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSessionGateTest {

    @Test
    fun audioStartsOncePerCall() {
        val gate = AudioSessionGate()
        gate.open()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun nothingStartsBeforeACallBegins() {
        assertFalse(AudioSessionGate().tryStart())
    }

    @Test
    fun mediaAfterHangupDoesNotRestartAudio() {
        // Answer in flight, user hangs up, then the late accept starts media.
        val gate = AudioSessionGate()
        gate.open()
        assertTrue(gate.tryStart())
        assertTrue(gate.close())
        assertFalse(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun hangupBeforeAnyMediaKeepsAudioOff() {
        val gate = AudioSessionGate()
        gate.open()
        assertFalse(gate.close())
        assertFalse(gate.tryStart())
    }

    @Test
    fun theNextCallCanStartAudioAgain() {
        val gate = AudioSessionGate()
        gate.open()
        gate.tryStart()
        gate.close()
        gate.open()
        assertTrue(gate.tryStart())
    }
}
