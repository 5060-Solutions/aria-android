package com.solutions5060.aria.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import uniffi.aria_mobile.PlatformAudioBridge

private const val TAG = "AudioBridge"

/**
 * Platform audio bridge for Aria mobile calls.
 *
 * Routes decoded RTP audio to the Android speaker via AudioTrack,
 * and captures microphone audio via AudioRecord for sending.
 *
 * Output routing (earpiece, speaker, Bluetooth) belongs to Telecom, which also
 * puts the device in MODE_IN_COMMUNICATION for a VoIP connection. The mode is
 * still set here if nothing else has, so a call Telecom refused to manage
 * gets the voice-call audio path (and its hardware echo cancellation) too.
 */
class AudioBridge(context: Context) : PlatformAudioBridge {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null

    /** Keeps audio off outside a call, including after [stop] for a call that is over. */
    private val session = AudioSessionGate()

    /** Capture-side voice processing attached to the AudioRecord session. */
    private val effects = mutableListOf<AudioEffect>()

    /** The mode before we set MODE_IN_COMMUNICATION, restored on stop; null if we did not. */
    private var previousMode: Int? = null

    /**
     * A call is beginning: the next media callback may open the microphone and
     * speaker. Until this is called again after [stop], media callbacks are
     * ignored, so a call that ended while it was being set up cannot reopen them.
     */
    fun beginCall() {
        session.open()
    }

    @Synchronized
    private fun ensureStarted(sampleRate: Int) {
        if (!session.tryStart()) return

        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        try {
            // Speaker output
            val minPlayBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minPlayBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.i(TAG, "AudioTrack started at ${sampleRate}Hz, buffer=$minPlayBuf")

            // Microphone input. RECORD_AUDIO can be revoked at any time; without
            // it the call still plays audio and simply sends silence.
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "RECORD_AUDIO not granted; call will send silence")
                return
            }
            val minRecBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minRecBuf * 4)
                .build()

            audioRecord?.let { attachVoiceProcessing(it.audioSessionId) }
            audioRecord?.startRecording()
            Log.i(TAG, "AudioRecord started at ${sampleRate}Hz, buffer=$minRecBuf")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio: ${e.message}", e)
        }
    }

    override fun onPlaybackAudio(samples: List<Short>, sampleRate: UInt) {
        ensureStarted(sampleRate.toInt())

        val track = audioTrack ?: return
        val shortArray = ShortArray(samples.size) { samples[it] }
        track.write(shortArray, 0, shortArray.size)
    }

    override fun onCaptureAudio(sampleRate: UInt, frameSize: UInt): List<Short> {
        ensureStarted(sampleRate.toInt())

        val record = audioRecord ?: return emptyList()
        val buf = ShortArray(frameSize.toInt())
        val read = record.read(buf, 0, buf.size)
        return if (read > 0) {
            buf.take(read).toList()
        } else {
            emptyList()
        }
    }

    /**
     * VOICE_COMMUNICATION usually brings the platform's echo canceller, noise
     * suppressor and gain control with it, but not on every device; attach
     * each one explicitly where the device offers it.
     */
    private fun attachVoiceProcessing(sessionId: Int) {
        fun add(name: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) return
            try {
                create()?.let {
                    it.enabled = true
                    effects.add(it)
                    Log.i(TAG, "$name enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$name unavailable: ${e.message}")
            }
        }
        add("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
        add("NoiseSuppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
        add("AutomaticGainControl", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
    }

    @Synchronized
    fun stop() {
        // Closed first, so a media callback racing this cannot start audio again.
        session.close()
        effects.forEach {
            try {
                it.release()
            } catch (_: Exception) {}
        }
        effects.clear()

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (_: Exception) {}

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}

        previousMode?.let {
            try {
                audioManager.mode = it
            } catch (_: Exception) {}
        }
        previousMode = null

        Log.i(TAG, "Audio bridge stopped")
    }
}
