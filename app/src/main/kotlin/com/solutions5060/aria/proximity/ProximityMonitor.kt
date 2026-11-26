package com.solutions5060.aria.proximity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.move.CallMoveCoordinator
import com.solutions5060.aria.move.CallMovePolicy
import com.solutions5060.aria.pbx.StoredPbxAuth
import com.solutions5060.aria.pbx.withAuth
import com.solutions5060.aria.service.PbxApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.Inet4Address

/**
 * Watches the default network (a [ConnectivityManager] callback). Each change
 * is reported to the PBX as a hash ([NetworkIdentity]); when this phone left a
 * network it was on while another of the user's devices has a call, it posts
 * `left_network` context (once per call), and the PBX answers with a move, a `handoff_suggested`, or nothing.
 */
object ProximityMonitor {

    private const val TAG = "Proximity"

    private var registered: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastHash: String? = null
    private val reported = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** The hash of the network this phone is on now, or null. */
    fun currentHash(): String? = lastHash

    /** Report the current hash again (foreground, and about every minute while visible). */
    fun reportNow(context: Context) {
        val app = context.applicationContext
        val settings = HandoffPrefs.load(app)
        if (!settings.enabled) return
        AppScope.scope.launch {
            try {
                sendHash(app, lastHash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Network report not sent: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun sendHash(app: Context, hash: String?) {
        if (hash == null) return
        val auth = StoredPbxAuth(app)
        val urn = CallMoveCoordinator.sipInstance(app)
        auth.withAuth { PbxApiClient(auth.apiUrl()).reportProximity(it, urn, ProximityJson.proximityBody(hash)) }
    }

    fun start(context: Context) {
        if (registered != null) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = changed(app, cm, network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed(app, cm, network)
            override fun onLost(network: Network) = update(app, null)
        }
        try {
            cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper()))
            registered = cb
        } catch (e: Exception) {
            Log.w(TAG, "Could not watch the network: ${e.message}")
        }
    }

    private fun changed(context: Context, cm: ConnectivityManager, network: Network) {
        update(context, identityOf(cm, network))
    }

    /** The hash of [network], or null for mobile data, a VPN or no usable address. */
    fun identityOf(cm: ConnectivityManager, network: Network): String? {
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        val fixed = (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val addr = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLinkLocalAddress && !it.address.isLoopbackAddress }
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        return NetworkIdentity.hash(addr?.address?.hostAddress, addr?.prefixLength ?: 0, gateway, fixed)
    }

    @Synchronized
    private fun update(context: Context, hash: String?) {
        val previous = lastHash
        if (previous == hash) return
        lastHash = hash
        val settings = HandoffPrefs.load(context)
        if (!settings.enabled) return
        AppScope.scope.launch {
            try {
                val auth = StoredPbxAuth(context)
                sendHash(context, hash)
                val others = CallMoveCoordinator.fetchOtherDeviceCalls(auth)
                val toReport = LeftNetworkPolicy.callsToReport(previous, hash, settings, others, reported.toSet())
                if (toReport.isNotEmpty()) {
                    // The PBX picks the call itself: one event covers them all.
                    reported.addAll(toReport.map { it.id })
                    val urn = CallMoveCoordinator.sipInstance(context)
                    val answer = auth.withAuth { PbxApiClient(auth.apiUrl()).postContext(it, urn, ProximityJson.leftNetworkBody()) }
                    CallMoveCoordinator.onContextResult(ProximityJson.contextResult(answer))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Network report not sent: ${e.javaClass.simpleName}")
            }
        }
    }
}
