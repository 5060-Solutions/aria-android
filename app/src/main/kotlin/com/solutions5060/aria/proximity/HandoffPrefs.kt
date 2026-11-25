package com.solutions5060.aria.proximity

import android.content.Context
import androidx.core.content.edit
import com.solutions5060.aria.service.EngineManager

/**
 * This phone's copy of the `/me/call-handoff` settings, so the network
 * monitor and the Bluetooth switch can act without a network round trip.
 * Bluetooth is off until the user turns it on, here and on the PBX.
 */
object HandoffPrefs {
    private const val ENABLED = "handoff_enabled"
    private const val NETWORK = "handoff_network"
    private const val BLE = "handoff_ble"

    fun load(context: Context): HandoffSettings {
        val p = context.applicationContext.getSharedPreferences(EngineManager.PREFS_NAME, Context.MODE_PRIVATE)
        return HandoffSettings(
            enabled = p.getBoolean(ENABLED, true),
            network = p.getBoolean(NETWORK, true),
            ble = p.getBoolean(BLE, false),
        )
    }

    fun save(context: Context, s: HandoffSettings) {
        context.applicationContext.getSharedPreferences(EngineManager.PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(ENABLED, s.enabled)
            putBoolean(NETWORK, s.network)
            putBoolean(BLE, s.ble)
        }
    }
}
