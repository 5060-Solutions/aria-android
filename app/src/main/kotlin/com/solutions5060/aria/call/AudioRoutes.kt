package com.solutions5060.aria.call

import android.telecom.CallAudioState
import android.telecom.CallEndpoint

enum class AudioRouteType { EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET, STREAMING }

/**
 * One audio output the user can pick. [id] is what the connection resolves
 * back to a Telecom route: a CallEndpoint identifier on API 34+, the
 * CallAudioState route constant below it.
 */
data class AudioRouteOption(
    val id: String,
    val type: AudioRouteType,
    val label: String,
)

/** The routes on offer and the one in use, as published to the call screen. */
data class AudioRouteState(
    val options: List<AudioRouteOption> = emptyList(),
    val currentId: String? = null,
) {
    val current: AudioRouteOption? get() = options.firstOrNull { it.id == currentId }
    val isSpeaker: Boolean get() = current?.type == AudioRouteType.SPEAKER

    /**
     * The route the speaker button switches to: the speaker if it is not in
     * use, otherwise the best private route (headset, then Bluetooth, then
     * earpiece).
     */
    fun speakerToggleTarget(): AudioRouteOption? =
        if (!isSpeaker) {
            options.firstOrNull { it.type == AudioRouteType.SPEAKER }
        } else {
            listOf(AudioRouteType.WIRED_HEADSET, AudioRouteType.BLUETOOTH, AudioRouteType.EARPIECE)
                .firstNotNullOfOrNull { type -> options.firstOrNull { it.type == type } }
        }
}

/** Pure conversions from Telecom's two audio-route models. */
object AudioRoutes {

    // Only used below API 34, where ROUTE_STREAMING does not exist.
    private val ROUTE_ORDER = listOf(
        CallAudioState.ROUTE_EARPIECE to AudioRouteType.EARPIECE,
        CallAudioState.ROUTE_WIRED_HEADSET to AudioRouteType.WIRED_HEADSET,
        CallAudioState.ROUTE_BLUETOOTH to AudioRouteType.BLUETOOTH,
        CallAudioState.ROUTE_SPEAKER to AudioRouteType.SPEAKER,
    )

    fun label(type: AudioRouteType): String = when (type) {
        AudioRouteType.EARPIECE -> "Phone"
        AudioRouteType.SPEAKER -> "Speaker"
        AudioRouteType.BLUETOOTH -> "Bluetooth"
        AudioRouteType.WIRED_HEADSET -> "Headset"
        AudioRouteType.STREAMING -> "Streaming"
    }

    /** Below API 34: a CallAudioState route bitmask and the active route. */
    fun fromRouteMask(supportedRouteMask: Int, route: Int): AudioRouteState {
        val options = ROUTE_ORDER
            .filter { (bit, _) -> supportedRouteMask and bit != 0 }
            .map { (bit, type) -> AudioRouteOption(bit.toString(), type, label(type)) }
        return AudioRouteState(options, route.toString().takeIf { id -> options.any { it.id == id } })
    }

    /** The CallAudioState route for an option produced by [fromRouteMask]. */
    fun routeForId(id: String): Int? =
        id.toIntOrNull()?.takeIf { route -> ROUTE_ORDER.any { it.first == route } }

    /** API 34+: the route type of a CallEndpoint type constant. */
    fun typeForEndpoint(endpointType: Int): AudioRouteType? = when (endpointType) {
        CallEndpoint.TYPE_EARPIECE -> AudioRouteType.EARPIECE
        CallEndpoint.TYPE_SPEAKER -> AudioRouteType.SPEAKER
        CallEndpoint.TYPE_BLUETOOTH -> AudioRouteType.BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> AudioRouteType.WIRED_HEADSET
        CallEndpoint.TYPE_STREAMING -> AudioRouteType.STREAMING
        else -> null
    }

    /**
     * API 34+: options from endpoints, described as (id, type constant, name).
     * A Bluetooth device keeps its own name so two headsets can be told apart.
     */
    fun fromEndpoints(endpoints: List<Triple<String, Int, CharSequence?>>, currentId: String?): AudioRouteState {
        val options = endpoints.mapNotNull { (id, endpointType, name) ->
            val type = typeForEndpoint(endpointType) ?: return@mapNotNull null
            val label = if (type == AudioRouteType.BLUETOOTH && !name.isNullOrBlank()) name.toString() else label(type)
            AudioRouteOption(id, type, label)
        }
        return AudioRouteState(options, currentId?.takeIf { id -> options.any { it.id == id } })
    }
}
