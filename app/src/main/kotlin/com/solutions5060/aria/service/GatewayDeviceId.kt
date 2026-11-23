package com.solutions5060.aria.service

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import uniffi.aria_mobile.AriaMobileEngineInterface

/**
 * Two different device ids, kept apart:
 *
 * - the **PBX device id** (`POST /api/v1/devices/register` -> `device_id`,
 *   pref `device_id`): for the PBX's own per-device routes, i.e. the push
 *   token copy (`PUT /devices/{id}/push-token`), gateway-token renewal
 *   (`POST /devices/{id}/gateway-token`) and PBX unregister;
 * - the **gateway device id** (`register_device` -> `device_id`, and every
 *   `registered` event, pref `gateway_device_id`): for the engine's gateway
 *   calls (`update_push_token`, `unregister_device`). It can change after a
 *   gateway failover or failback, so the latest `registered` event wins.
 */
class GatewayDeviceIds(private val store: Store) {

    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    companion object {
        const val KEY_GATEWAY = "gateway_device_id"
        const val KEY_PBX = "device_id"

        fun of(context: Context): GatewayDeviceIds {
            val prefs = context.applicationContext.getSharedPreferences(EngineManager.PREFS_NAME, Context.MODE_PRIVATE)
            return GatewayDeviceIds(object : Store {
                override fun get(key: String) = prefs.getString(key, null)?.ifEmpty { null }
                override fun put(key: String, value: String?) = prefs.edit {
                    if (value == null) remove(key) else putString(key, value)
                }
            })
        }
    }

    /** The id the gateway knows this device by; before any gateway registration, the PBX's (often the same). */
    fun gateway(): String? = store.get(KEY_GATEWAY) ?: store.get(KEY_PBX)

    fun pbx(): String? = store.get(KEY_PBX)

    /** A `registered` event or a `register_device` response: keep the latest id. Returns whether it changed. */
    fun onRegistered(deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        val changed = store.get(KEY_GATEWAY) != deviceId
        if (changed) store.put(KEY_GATEWAY, deviceId)
        return changed
    }

    fun clear() = store.put(KEY_GATEWAY, null)

    /**
     * Sends a rotated push token to the gateway for the current gateway id.
     * Returns false when there is no id yet (the next registration carries
     * the token instead).
     */
    fun updatePushToken(engine: AriaMobileEngineInterface, token: String): Boolean {
        val id = gateway() ?: return false
        engine.updatePushToken(id, token)
        return true
    }

    /** Unregisters this device from the gateway, by the gateway's id for it. Blocks. */
    fun unregister(engine: AriaMobileEngineInterface) {
        val id = gateway() ?: return
        try {
            engine.unregisterDevice(id)
        } catch (e: Exception) {
            Log.w("GatewayDeviceIds", "Gateway unregister failed: ${e.javaClass.simpleName}")
        }
        clear()
    }
}
