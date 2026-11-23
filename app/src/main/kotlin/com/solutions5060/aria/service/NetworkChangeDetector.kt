package com.solutions5060.aria.service

/** The kinds of link a default network can run over. */
enum class NetTransport { WIFI, CELLULAR, ETHERNET, VPN, OTHER }

/**
 * Default-network events, free of android.net types so the rules in
 * [NetworkChangeDetector] are unit-tested. [ConnectivityNetworkSource]
 * translates ConnectivityManager's default-network callback into these.
 */
interface DefaultNetworkCallback {
    /** [network] became the default network (first connect, or a switch to it). */
    fun onAvailable(network: String)

    /** [network] went away; if it was the default, the device may be offline. */
    fun onLost(network: String)

    fun onCapabilitiesChanged(network: String, transports: Set<NetTransport>, validated: Boolean)

    /** [addresses] are the routable (non-link-local) addresses on [network]. */
    fun onLinkPropertiesChanged(network: String, addresses: Set<String>)
}

/** Delivers default-network events. One registration at a time. */
interface NetworkSource {
    fun register(callback: DefaultNetworkCallback)
    fun unregister()
}

/** Runs work later; returns a handle that cancels it. Injected so tests control time. */
fun interface Scheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
}

fun interface Cancellable {
    fun cancel()
}

/**
 * Decides when the core must hear `notify_network_change`: on every change of
 * the default network that can break the gateway's view of this device —
 *
 * - a new default network (first connect, Wi-Fi to cellular or back, a VPN
 *   coming up), whatever the order Android reports the old one lost in;
 * - a change of address on the same network (DHCP renew, IPv6 renumbering,
 *   a roam between access points that changes the IP);
 * - a change of transport on the same network object, or the network
 *   becoming validated (a captive portal signed in to).
 *
 * Changes are debounced: a burst (Android often reports available,
 * capabilities and link properties within a few hundred ms) produces one
 * notification [debounceMillis] after the last event. Losing the default
 * network notifies nothing (there is nothing to reach), but reports
 * [onConnectivity] false at once; the next default network notifies.
 */
class NetworkChangeDetector(
    private val scheduler: Scheduler,
    private val onChange: () -> Unit,
    private val onConnectivity: (online: Boolean) -> Unit = {},
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
) : DefaultNetworkCallback {

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 1_500L
    }

    private var current: String? = null
    private var transports: Set<NetTransport>? = null
    private var validated: Boolean? = null
    private var addresses: Set<String>? = null
    private var pending: Cancellable? = null

    /** The default network now, or null when offline. */
    val currentNetwork: String? get() = current

    @Synchronized
    override fun onAvailable(network: String) {
        if (network == current) return
        current = network
        transports = null
        validated = null
        addresses = null
        onConnectivity(true)
        changed()
    }

    @Synchronized
    override fun onLost(network: String) {
        // With a default-network callback the replacement may already have
        // been reported; only the loss of the current default matters.
        if (network != current) return
        current = null
        transports = null
        validated = null
        addresses = null
        pending?.cancel()
        pending = null
        onConnectivity(false)
    }

    @Synchronized
    override fun onCapabilitiesChanged(network: String, transports: Set<NetTransport>, validated: Boolean) {
        if (network != current) return
        val before = this.transports
        val wasValidated = this.validated
        this.transports = transports
        this.validated = validated
        val transportChanged = before != null && before != transports
        val becameValidated = wasValidated == false && validated
        if (transportChanged || becameValidated) changed()
    }

    @Synchronized
    override fun onLinkPropertiesChanged(network: String, addresses: Set<String>) {
        if (network != current) return
        val before = this.addresses
        this.addresses = addresses
        if (before != null && before != addresses) changed()
    }

    private fun changed() {
        pending?.cancel()
        pending = scheduler.schedule(debounceMillis) {
            synchronized(this) { pending = null }
            onChange()
        }
    }
}
