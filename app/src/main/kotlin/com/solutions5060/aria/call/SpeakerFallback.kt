package com.solutions5060.aria.call

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Speaker routing for a call Telecom refused to manage. With a connection,
 * routing always goes through Telecom; this exists so such a call is not
 * stuck on the earpiece.
 */
internal object SpeakerFallback {

    private val EARPIECE = AudioRouteOption("fallback-earpiece", AudioRouteType.EARPIECE, AudioRoutes.label(AudioRouteType.EARPIECE))
    private val SPEAKER = AudioRouteOption("fallback-speaker", AudioRouteType.SPEAKER, AudioRoutes.label(AudioRouteType.SPEAKER))

    fun routes(speaker: Boolean) = AudioRouteState(listOf(EARPIECE, SPEAKER), if (speaker) SPEAKER.id else EARPIECE.id)

    fun setSpeaker(context: Context, on: Boolean) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }
}
