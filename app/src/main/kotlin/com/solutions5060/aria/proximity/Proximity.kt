package com.solutions5060.aria.proximity

import com.solutions5060.aria.move.ActiveCall
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** `/me/call-handoff`: what the user lets the PBX do with this device's whereabouts. */
data class HandoffSettings(
    /** Suggest this device when a call is on another one. */
    val enabled: Boolean = true,
    /** Tell the PBX which network this device is on (a hash). */
    val network: Boolean = true,
    /** Bluetooth proximity; off until the user turns it on. */
    val ble: Boolean = false,
)

/**
 * The proximity wire shapes, assumed: the contract's Proximity section was
 * not available. Kept in one place so a correction is one edit.
 */
object ProximityJson {

    fun settings(json: String): HandoffSettings {
        val o = JSONObject(json)
        return HandoffSettings(
            enabled = o.optBoolean("enabled", true),
            network = o.optBoolean("network", true),
            ble = o.optBoolean("ble", false),
        )
    }

    fun settingsBody(s: HandoffSettings): String =
        JSONObject().put("enabled", s.enabled).put("network", s.network).put("ble", s.ble).toString()

    fun networkBody(registrationId: String, hash: String?): String =
        JSONObject().put("registration_id", registrationId).put("network_hash", hash ?: JSONObject.NULL).toString()

    fun leftNetworkBody(registrationId: String, callId: String): String =
        JSONObject().put("type", "left_network").put("registration_id", registrationId).put("call_id", callId).toString()

    fun bleSeenBody(registrationId: String, tokens: Collection<String>): String =
        JSONObject().put("registration_id", registrationId).put("tokens", JSONArray(tokens.toList())).toString()

    data class BleToken(val hex: String, val expiresAtMillis: Long?)

    fun bleToken(json: String): BleToken {
        val o = JSONObject(json)
        val token = o.optString("token", "").ifEmpty { throw IllegalStateException("The PBX returned no token") }
        val expires = o.optString("expires_at", "").let {
            if (it.isEmpty()) null else runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }
        return BleToken(token, expires)
    }
}

/**
 * Which network a phone is on, as a SHA-256 hash.
 *
 * The identity is the default network's IPv4 subnet (address masked by the
 * prefix length) and its default gateway, not the SSID. Reading an SSID on
 * Android needs the location permission (and location services switched on;
 * NEARBY_WIFI_DEVICES on 13+), which a phone-calling app should not ask for.
 * LinkProperties is readable with ACCESS_NETWORK_STATE, which the app has.
 *
 * The cost: two different home networks that both use 192.168.1.0/24 behind a
 * .1 gateway hash alike. The hash is only compared between one user's own
 * devices, so it can wrongly say "same network" for a friend's house, never
 * for another user. Mobile data is never given an identity (carrier-grade NAT
 * puts unrelated phones on one range), and neither is a VPN.
 */
object NetworkIdentity {

    /** Hex SHA-256 over a versioned string, or null when there is no usable fixed network. */
    fun hash(subnetAddress: String?, prefixLength: Int, gateway: String?, wifiOrEthernet: Boolean): String? {
        if (!wifiOrEthernet || subnetAddress.isNullOrEmpty() || prefixLength !in 8..30) return null
        val net = maskedIpv4(subnetAddress, prefixLength) ?: return null
        val input = "aria-net-v1|$net/$prefixLength|${gateway.orEmpty()}"
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
        if (!settings.enabled || !settings.network) return emptyList()
        if (previousHash == null || previousHash == newHash) return emptyList()
        return othersCalls.filter { it.id !in alreadyReported }
    }
}
