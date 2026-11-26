package com.solutions5060.aria.proximity

import com.solutions5060.aria.move.ActiveCall
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** `/me/call-handoff`: what the user lets the PBX do with this device's whereabouts. */
data class HandoffSettings(
    /** `suggest_nearby` (default on): suggest this device when a call is on another one. */
    val enabled: Boolean = true,
    /** `auto_move_on_leave` (default off): move the call here when this phone leaves its network. */
    val autoMoveOnLeave: Boolean = false,
    /** `ble_opt_in` (default off): Bluetooth proximity; off until the user turns it on. */
    val ble: Boolean = false,
)

/** One token heard over Bluetooth, with its signal strength. */
data class BleSeen(val token: String, val rssi: Int)

/** What `POST .../context` answered. */
data class ContextResult(val action: String, val callId: String?, val moveId: String?)

/**
 * The proximity wire shapes of the call-move contract ("Proximity"). Every
 * `devices/current` route names this device by `?sip_instance=<urn>` (see
 * PbxApiClient).
 */
object ProximityJson {

    /** At most this many tokens in one report. */
    const val MAX_BLE_SEEN = 32

    fun settings(json: String): HandoffSettings {
        val o = JSONObject(json)
        return HandoffSettings(
            enabled = o.optBoolean("suggest_nearby", true),
            autoMoveOnLeave = o.optBoolean("auto_move_on_leave", false),
            ble = o.optBoolean("ble_opt_in", false),
        )
    }

    /** `auto_move_on_desktop_lock` is the desktop's; PUT keeps the fields not given. */
    fun settingsBody(s: HandoffSettings): String =
        JSONObject().put("suggest_nearby", s.enabled).put("auto_move_on_leave", s.autoMoveOnLeave).put("ble_opt_in", s.ble).toString()

    /** `{"network_hash"?, "ble_seen"?}`: both optional, the hash lowercase sha256 hex, at most 32 tokens. */
    fun proximityBody(networkHash: String?, bleSeen: Collection<BleSeen> = emptyList()): String {
        val o = JSONObject()
        if (networkHash != null) o.put("network_hash", networkHash)
        if (bleSeen.isNotEmpty()) {
            val array = JSONArray()
            bleSeen.take(MAX_BLE_SEEN).forEach { array.put(JSONObject().put("token", it.token).put("rssi", it.rssi)) }
            o.put("ble_seen", array)
        }
        return o.toString()
    }

    /** `{"event":"left_network"}`. */
    fun leftNetworkBody(): String = JSONObject().put("event", "left_network").toString()

    fun contextResult(json: String): ContextResult {
        val o = JSONObject(json)
        return ContextResult(
            action = o.optString("action", "none"),
            callId = o.takeUnless { it.isNull("call_id") }?.optString("call_id", "")?.ifEmpty { null },
            moveId = o.takeUnless { it.isNull("move_id") }?.optString("move_id", "")?.ifEmpty { null },
        )
    }

    /** [hex] is 16 bytes (32 hex digits); [expiresAtMillis] is `rotates_at`. */
    data class BleToken(val hex: String, val expiresAtMillis: Long?)

    fun bleToken(json: String): BleToken {
        val o = JSONObject(json)
        val token = o.optString("token", "").ifEmpty { throw IllegalStateException("The PBX returned no token") }
        val expires = o.optString("rotates_at", "").let {
            if (it.isEmpty()) null else runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }
        return BleToken(token, expires)
    }
}

/**
 * Which network a phone is on, as the call-move contract's `network_hash`:
 * `lowercase_hex(sha256("aria-net-v1|" + extension_id + "|" + gateway + "|" + network/prefix))`
 * on the default network's IPv4 gateway and subnet, the same on every
 * platform so a phone and a desktop on one LAN match. Omitted (null) when
 * there is no IPv4 default route.
 *
 * Neither SSID nor BSSID is read (that needs the location permission, which
 * a phone-calling app should not ask for); LinkProperties needs only
 * ACCESS_NETWORK_STATE. Mobile data and VPNs are never given an identity.
 * The extension id salts the hash per user.
 */
object NetworkIdentity {

    /** Hex SHA-256 over the contract's string, or null when there is no usable fixed network. */
    fun hash(extensionId: String?, subnetAddress: String?, prefixLength: Int, gateway: String?, wifiOrEthernet: Boolean): String? {
        if (!wifiOrEthernet || extensionId.isNullOrEmpty() || gateway.isNullOrEmpty()) return null
        if (subnetAddress.isNullOrEmpty() || prefixLength !in 8..30) return null
        val net = maskedIpv4(subnetAddress, prefixLength) ?: return null
        val input = "aria-net-v1|${extensionId.lowercase()}|$gateway|$net/$prefixLength"
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** The network address of [address]/[prefix], or null for anything but dotted IPv4. */
    fun maskedIpv4(address: String, prefix: Int): String? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
        var value = 0L
        octets.forEach { value = (value shl 8) or it.toLong() }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val net = value and mask
        return listOf(24, 16, 8, 0).joinToString(".") { ((net shr it) and 0xFF).toString() }
    }
}

/** When this device leaving its network should be reported. */
object LeftNetworkPolicy {

    /**
     * The calls to post `left_network` for: the network changed from a known
     * one to a different one (or to none), the user allows it, and another
     * device has calls not yet reported.
     */
    fun callsToReport(
        previousHash: String?,
        newHash: String?,
        settings: HandoffSettings,
        othersCalls: List<ActiveCall>,
        alreadyReported: Set<String>,
    ): List<ActiveCall> {
        if (!settings.enabled) return emptyList()
        if (previousHash == null || previousHash == newHash) return emptyList()
        return othersCalls.filter { it.id !in alreadyReported }
    }
}
