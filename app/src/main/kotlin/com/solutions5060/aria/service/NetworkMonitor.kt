package com.solutions5060.aria.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Tells the Rust core about every default-network change, so the gateway
 * re-registers the device from its new address and media sockets rebind.
 *
 * The rules (what counts as a change, debouncing) live in
 * [NetworkChangeDetector]; this class only connects it to
 * ConnectivityManager's default-network callback and to [GatewayConnection],
 * which also builds the engine if the app started offline.
 */
class NetworkMonitor(
    private val context: Context,
    private val source: NetworkSource = ConnectivityNetworkSource(context),
    scheduler: Scheduler = HandlerScheduler(Handler(Looper.getMainLooper())),
) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val detector = NetworkChangeDetector(
        scheduler = scheduler,
        onChange = {
            Log.i(TAG, "Default network changed; notifying core")
            GatewayConnection.onNetworkChanged(context.applicationContext)
        },
        onConnectivity = { online ->
            Log.i(TAG, if (online) "Network available" else "No default network")
            GatewayConnection.onConnectivity(context.applicationContext, online)
        },
    )

    fun start() = source.register(detector)

    fun stop() = source.unregister()
}

/** Posts to a [Handler]; cancelling removes the post. */
class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMillis)
        return Cancellable { handler.removeCallbacks(r) }
    }
}

/** ConnectivityManager's default-network callback as [DefaultNetworkCallback] events, on the main thread. */
class ConnectivityNetworkSource(context: Context) : NetworkSource {

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered: ConnectivityManager.NetworkCallback? = null

    override fun register(callback: DefaultNetworkCallback) {
        unregister()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = callback.onAvailable(network.toString())

            override fun onLost(network: Network) = callback.onLost(network.toString())

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                callback.onCapabilitiesChanged(
                    network.toString(),
                    transportsOf(caps),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                callback.onLinkPropertiesChanged(
                    network.toString(),
                    lp.linkAddresses
                        .map { it.address }
                        .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }
                        .mapNotNull { it.hostAddress }
                        .toSet(),
                )
        }
        try {
            cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
            registered = cb
        } catch (e: Exception) {
            Log.w("NetworkMonitor", "Could not watch the network: ${e.message}")
        }
    }

    override fun unregister() {
        registered?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        registered = null
    }

    private fun transportsOf(caps: NetworkCapabilities): Set<NetTransport> = buildSet {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetTransport.WIFI)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetTransport.CELLULAR)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetTransport.ETHERNET)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetTransport.VPN)
        if (isEmpty()) add(NetTransport.OTHER)
    }
}
