package com.solutions5060.aria.service

import android.media.*
import android.util.Log
import uniffi.aria_mobile.PlatformAudioBridge
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "AudioBridge"

/**
 * Platform audio bridge for Aria mobile calls.
 *
 * Routes decoded RTP audio to the Android speaker via AudioTrack,
 * and captures microphone audio via AudioRecord for sending.
 */
class AudioBridge : PlatformAudioBridge {

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var isStarted = false

    @Synchronized
    private fun ensureStarted(sampleRate: Int) {
        if (isStarted) return
        isStarted = true

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

            // Microphone input
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

    @Synchronized
    fun stop() {
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

        isStarted = false
        Log.i(TAG, "Audio bridge stopped")
    }
}
