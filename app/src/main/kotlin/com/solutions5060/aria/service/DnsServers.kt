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

    /**
     * Keep a link-local server's scope ("fe80::1%wlan0"): the core resolves
     * the interface to a scope id, and drops link-local servers when others
     * exist. Not covered: Private DNS strict mode (DNS over TLS), whose
     * hostname the network's servers are used in spite of.
     */
    fun format(addresses: List<String>): List<String> = addresses

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
