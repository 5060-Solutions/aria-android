package com.solutions5060.aria.call

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.solutions5060.aria.service.SipEngineHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.aria_mobile.AudioCodec

/** One codec in the user's order, and whether it is offered at all. */
data class CodecSetting(val codec: AudioCodec, val enabled: Boolean)

/** Pure ordering rules for the codec list; unit-tested. */
object CodecOrder {

    /** The core's default order (Opus, PCMU, PCMA); G.729 is available but off. */
    val DEFAULT: List<CodecSetting> = listOf(
        CodecSetting(AudioCodec.OPUS, true),
        CodecSetting(AudioCodec.PCMU, true),
        CodecSetting(AudioCodec.PCMA, true),
        CodecSetting(AudioCodec.G729, false),
    )

    /** Moves the codec at [index] by [delta] places, clamped to the list. */
    fun move(list: List<CodecSetting>, index: Int, delta: Int): List<CodecSetting> {
        if (index !in list.indices) return list
        val target = (index + delta).coerceIn(0, list.lastIndex)
        if (target == index) return list
        val m = list.toMutableList()
        val item = m.removeAt(index)
        m.add(target, item)
        return m
    }

    /** Turns [codec] on or off; the last enabled codec cannot be turned off (a call needs one). */
    fun setEnabled(list: List<CodecSetting>, codec: AudioCodec, enabled: Boolean): List<CodecSetting> {
        if (!enabled && list.count { it.enabled } <= 1 && list.any { it.codec == codec && it.enabled }) return list
        return list.map { if (it.codec == codec) it.copy(enabled = enabled) else it }
    }

    /** What `set_codec_preferences` gets: the enabled codecs in order. */
    fun offered(list: List<CodecSetting>): List<AudioCodec> = list.filter { it.enabled }.map { it.codec }

    fun encode(list: List<CodecSetting>): String = list.joinToString(",") { "${it.codec.name}:${if (it.enabled) 1 else 0}" }

    /**
     * Reads [encode]'s format, keeping only known codecs once each and
     * appending any codec it lacks (a newer core) disabled; the default when
     * unreadable or nothing would be enabled.
     */
    fun decode(value: String?): List<CodecSetting> {
        if (value.isNullOrBlank()) return DEFAULT
        val seen = mutableSetOf<AudioCodec>()
        val parsed = value.split(',').mapNotNull { part ->
            val codec = AudioCodec.entries.firstOrNull { it.name == part.substringBefore(':') } ?: return@mapNotNull null
            if (!seen.add(codec)) return@mapNotNull null
            CodecSetting(codec, part.substringAfter(':', "1") == "1")
        }
        val complete = parsed + AudioCodec.entries.filter { it !in seen }.map { CodecSetting(it, false) }
        return if (complete.none { it.enabled }) DEFAULT else complete
    }
}

/**
 * The user's codec order, saved in prefs and handed to the engine with
 * `set_codec_preferences` whenever it starts or the order changes.
 */
object CodecPreferences {

    private const val PREFS = "aria_prefs"
    private const val KEY = "codec_order"

    private val _state = MutableStateFlow(CodecOrder.DEFAULT)
    val state: StateFlow<List<CodecSetting>> = _state.asStateFlow()

    fun load(context: Context): List<CodecSetting> =
        CodecOrder.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
            .also { _state.value = it }

    fun save(context: Context, list: List<CodecSetting>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, CodecOrder.encode(list)) }
        _state.value = list
        applyTo(SipEngineHolder.engine, list)
    }

    /** Hands the order to [engine]; an empty list would mean "the core's default", which is never sent. */
    fun applyTo(engine: uniffi.aria_mobile.AriaMobileEngine?, list: List<CodecSetting>) {
        val offered = CodecOrder.offered(list)
        if (engine == null || offered.isEmpty()) return
        try {
            engine.setCodecPreferences(offered)
        } catch (e: Exception) {
            Log.w("CodecPreferences", "setCodecPreferences failed: ${e.javaClass.simpleName}")
        }
    }

    /** Human names, for Settings and diagnostics. */
    fun label(codec: AudioCodec): String = when (codec) {
        AudioCodec.OPUS -> "Opus"
        AudioCodec.PCMU -> "G.711 µ-law"
        AudioCodec.PCMA -> "G.711 A-law"
        AudioCodec.G729 -> "G.729"
    }
}
