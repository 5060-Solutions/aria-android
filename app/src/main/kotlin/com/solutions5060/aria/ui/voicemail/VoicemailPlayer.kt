package com.solutions5060.aria.ui.voicemail

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Playback speeds offered by the inline player (DESIGN.md §5). */
val VoicemailSpeeds = listOf(1f, 1.5f, 2f)

/** "1×", "1.5×", "2×". */
fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"

/**
 * One MediaPlayer for the voicemail list, observable from Compose: which
 * message is loaded, whether it plays, position, speed, and speaker vs
 * earpiece. Speaker off plays through the earpiece as a voice call would.
 */
class VoicemailPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var url: String? = null

    var messageId by mutableStateOf<String?>(null)
        private set
    var preparing by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var speaker by mutableStateOf(true)
        private set

    var onError: (String) -> Unit = {}
    var onStarted: (String) -> Unit = {}

    /** Loads and plays [id] from [streamUrl] (a short-lived grant URL), from [startMs]. */
    fun play(id: String, streamUrl: String, startMs: Int = 0) {
        release()
        messageId = id
        url = streamUrl
        preparing = true
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (speaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            if (!speaker) audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            mp.setDataSource(streamUrl)
            mp.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                preparing = false
                durationMs = it.duration.coerceAtLeast(0)
                if (startMs > 0) it.seekTo(startMs)
                applySpeed(it)
                it.start()
                playing = true
                onStarted(id)
            }
            mp.setOnCompletionListener {
                playing = false
                positionMs = durationMs
            }
            mp.setOnErrorListener { _, what, _ ->
                Log.w(TAG, "Playback error $what")
                onError("Could not play this message.")
                stop()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start playback: ${e.javaClass.simpleName}")
            onError("Could not play this message.")
            stop()
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playing = false
    }

    fun resume() {
        val mp = player ?: return
        if (preparing) return
        if (positionMs >= durationMs && durationMs > 0) mp.seekTo(0)
        mp.start()
        applySpeed(mp)
        playing = true
    }

    fun seekTo(ms: Int) {
        positionMs = ms
        if (!preparing) player?.seekTo(ms)
    }

    fun changeSpeed(value: Float) {
        speed = value
        player?.let { if (playing) applySpeed(it) }
    }

    /** Switches between loudspeaker and earpiece, carrying on from where it was. */
    fun changeSpeaker(on: Boolean) {
        if (speaker == on) return
        speaker = on
        val id = messageId
        val u = url
        val at = positionMs
        val wasPlaying = playing || preparing
        if (id != null && u != null && wasPlaying) play(id, u, at)
    }

    /** Called about every 250 ms while playing. */
    fun tick() {
        val mp = player ?: return
        if (playing) positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
    }

    fun stop() {
        release()
        messageId = null
        url = null
        positionMs = 0
        durationMs = 0
    }

    private fun release() {
        player?.release()
        player = null
        preparing = false
        playing = false
        if (audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun applySpeed(mp: MediaPlayer) {
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
    }

    /** Debug fixture: shows [id] as playing at [atMs] of [totalMs] without audio. */
    fun showFixture(id: String, atMs: Int, totalMs: Int) {
        messageId = id
        positionMs = atMs
        durationMs = totalMs
        playing = true
    }

    /** Fixture play/pause without audio. */
    fun toggleFixture(id: String, totalMs: Int) {
        if (messageId != id) showFixture(id, 0, totalMs) else playing = !playing
    }

    private companion object {
        const val TAG = "VoicemailPlayer"
    }
}
