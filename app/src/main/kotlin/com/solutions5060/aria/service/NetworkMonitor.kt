package com.solutions5060.aria.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Monitors network connectivity changes and notifies the Rust core.
 *
 * When the device switches between WiFi and cellular, or reconnects after
 * being offline, this triggers re-registration with the push gateway and
 * media socket recovery for active calls.
 */
class NetworkMonitor(context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var isConnected = false
    private var lastNotifyTime = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available: $network")
            if (!isConnected) {
                isConnected = true
                notifyNetworkChange()
            }
        }

        override fun onLost(network: Network) {
            Log.w(TAG, "Network lost: $network")
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork == null) {
                isConnected = false
                Log.w(TAG, "All networks lost")
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val now = System.currentTimeMillis()
            if (now - lastNotifyTime > 3000) {
                Log.i(TAG, "Network capabilities changed, notifying core")
                notifyNetworkChange()
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        isConnected = connectivityManager.activeNetwork != null
        Log.i(TAG, "NetworkMonitor started, connected=$isConnected")
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback", e)
        }
    }

    private fun notifyNetworkChange() {
        lastNotifyTime = System.currentTimeMillis()
        try {
            // Access the engine via the global singleton
            SipEngineHolder.engine?.notifyNetworkChange()
            Log.i(TAG, "Notified Rust core of network change")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify core of network change", e)
        }
    }
}
