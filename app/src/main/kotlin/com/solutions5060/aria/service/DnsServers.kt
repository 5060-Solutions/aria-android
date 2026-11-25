package com.solutions5060.aria.service

import android.content.Context
import android.net.ConnectivityManager
import uniffi.aria_mobile.AriaMobileEngine

/**
 * The DNS servers of the active network, for the core's SRV lookups. The
 * core has no /etc/resolv.conf on Android and must not fall back to a public
 * resolver, so it is told, before each network change, what the network
 * hands out (`LinkProperties.dnsServers`).
 */
object DnsServers {
    /** IP address strings, scope ids dropped; empty when there is no network. */
    fun of(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val props = cm.getLinkProperties(network) ?: return emptyList()
        return format(props.dnsServers.mapNotNull { it.hostAddress })
    }

    /** Drop a scope id ("fe80::1%wlan0"). */
    fun format(addresses: List<String>): List<String> = addresses.map { it.substringBefore('%') }

    fun apply(context: Context, engine: AriaMobileEngine) {
        val servers = try {
            of(context)
        } catch (_: Exception) {
            emptyList()
        }
        DiagLog.log("dns", "servers: ${servers.size}")
        engine.setDnsServers(servers)
    }
}
