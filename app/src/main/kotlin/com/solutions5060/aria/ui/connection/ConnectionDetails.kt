package com.solutions5060.aria.ui.connection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solutions5060.aria.BuildConfig
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallUiState
import com.solutions5060.aria.call.CallWaiting
import com.solutions5060.aria.call.CodecOrder
import com.solutions5060.aria.call.CodecPreferences
import com.solutions5060.aria.call.CodecSetting
import com.solutions5060.aria.fixtures.Fixtures
import com.solutions5060.aria.service.ConnectionState
import com.solutions5060.aria.service.ConnectionStatus
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.GatewayConnection
import com.solutions5060.aria.service.NetTransport
import com.solutions5060.aria.service.NetworkSnapshot
import com.solutions5060.aria.service.NetworkStatus
import com.solutions5060.aria.service.SipEngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import uniffi.aria_mobile.MediaStats

/** The live call, as diagnostics shows it. */
data class CallDetails(
    /** The negotiated codec's SDP name ("opus", "PCMU", …); null before media. */
    val codec: String?,
    /** [codec]'s display name ("Opus", "G.711 µ-law"). */
    val codecLabel: String?,
    /** SRTP as negotiated; null until known. */
    val encrypted: Boolean?,
    val stats: MediaStats?,
    val inConference: Boolean,
    val recording: Boolean,
)

/**
 * Everything the connection sheet and diagnostics show, in one value
 * (the iOS ConnectionDetails model): state, account, gateway URL and node,
 * transport, last error, network, the live call's codec, SRTP and media
 * stats, and the codec order. Live while collected.
 */
data class ConnectionDetails(
    val connection: ConnectionState,
    val displayName: String,
    val extension: String,
    val domain: String,
    val transport: String,
    val network: NetworkSnapshot,
    /** `current_gateway()`: the gateway instance holding the registration, "host:port". */
    val gatewayNode: String?,
    val call: CallDetails?,
    val codecOrder: List<CodecSetting>,
    val appVersion: String,
) {
    val status: ConnectionStatus get() = connection.status
    val error: String? get() = connection.error

    /** A plain-text report for "Copy": no secrets, tokens or passwords. */
    fun report(log: String): String = buildString {
        appendLine("Aria ${appVersion} (Android ${android.os.Build.VERSION.RELEASE})")
        appendLine("Status: ${status.name.lowercase()}${if (connection.recovering) " (recovering)" else ""}")
        error?.let { appendLine("Last error: $it") }
        appendLine("Account: ext $extension @ $domain")
        appendLine("Transport: ${transport.uppercase()}")
        appendLine("Gateway: ${connection.gatewayUrl ?: "-"}")
        appendLine("Gateway node: ${gatewayNode ?: "-"}")
        appendLine("Device: ${connection.deviceId ?: "-"}")
        appendLine(
            "Network: ${if (network.online) network.transports.joinToString("+") { it.name.lowercase() }.ifEmpty { "online" } else "offline"}" +
                (if (network.validated == false) " (not validated)" else ""),
        )
        appendLine("Codec order: ${CodecOrder.offered(codecOrder).joinToString(", ") { CodecPreferences.label(it) }}")
        call?.let { c ->
            appendLine("Call codec: ${c.codecLabel ?: "-"}; SRTP: ${when (c.encrypted) { true -> "yes"; false -> "no"; null -> "-" }}")
            c.stats?.let { s ->
                appendLine(
                    "Media: sent ${s.packetsSent} pkts, received ${s.packetsReceived} pkts, loss ${s.packetLoss}, " +
                        "jitter ${"%.1f".format(java.util.Locale.ROOT, s.jitterMs)} ms, rtt ${"%.0f".format(java.util.Locale.ROOT, s.rttMs)} ms",
                )
            }
        }
        appendLine()
        append(log)
    }

    companion object {
        /** Sample values in the real formats, for fixture mode and previews. */
        fun fixture(): ConnectionDetails = ConnectionDetails(
            connection = ConnectionState(
                status = ConnectionStatus.REGISTERED,
                gatewayUrl = "https://push.ariaroute.com",
                deviceId = "c0ffee42-5060-4a1e-9b7d-2f8e61d0a9b3",
                gatewayNode = "gw1.ariaroute.com:443",
            ),
            displayName = Fixtures.current?.account?.displayName ?: "Alex Rivera",
            extension = Fixtures.current?.account?.extension ?: "1042",
            domain = Fixtures.current?.account?.domain ?: "acme.ariaroute.com",
            transport = "tls",
            network = NetworkSnapshot(online = true, transports = setOf(NetTransport.WIFI), validated = true),
            gatewayNode = "gw1.ariaroute.com:443",
            call = if (Fixtures.current?.calls.isNullOrEmpty()) null else CallDetails(
                codec = "opus",
                codecLabel = CodecPreferences.labelForSdp("opus"),
                encrypted = true,
                stats = MediaStats(
                    packetsSent = 6_120u, packetsReceived = 6_098u, bytesSent = 489_600u, bytesReceived = 487_840u,
                    jitterMs = 3.2, packetLoss = 4u, rttMs = 48.0,
                ),
                inConference = false,
                recording = false,
            ),
            codecOrder = CodecOrder.DEFAULT,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}

/** The call diagnostics describe: the one on screen, if it has media. */
internal fun diagnosedCall(calls: List<CallUiState>): CallUiState? =
    CallWaiting.foreground(calls)?.takeIf { it.phase == CallPhase.ACTIVE || it.phase == CallPhase.HELD }

/**
 * [ConnectionDetails] for the connection sheet and diagnostics. Media stats
 * and the gateway node are polled once a second only while someone collects
 * [details] (and for 5 s after), so a closed sheet costs nothing.
 */
class ConnectionDetailsViewModel(application: Application) : AndroidViewModel(application) {

    private data class Live(val gatewayNode: String?, val stats: MediaStats?)

    private val live: Flow<Live> = flow {
        while (true) {
            val engine = SipEngineHolder.engine
            val callId = diagnosedCall(CallManager.calls.value)?.callId
            emit(
                Live(
                    gatewayNode = runCatching { engine?.currentGateway() }.getOrNull(),
                    stats = callId?.let { id -> runCatching { engine?.getMediaStats(id) }.getOrNull() },
                ),
            )
            delay(1_000)
        }
    }.flowOn(Dispatchers.IO)

    val details: StateFlow<ConnectionDetails> = if (Fixtures.active) {
        kotlinx.coroutines.flow.MutableStateFlow(ConnectionDetails.fixture())
    } else {
        combine(
            GatewayConnection.state,
            NetworkStatus.state,
            CallManager.calls,
            CodecPreferences.state,
            live,
        ) { connection, network, calls, codecs, l ->
            val prefs = application.getSharedPreferences(EngineManager.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val call = diagnosedCall(calls)
            ConnectionDetails(
                connection = connection,
                displayName = prefs.getString("sip_display_name", "") ?: "",
                extension = prefs.getString("sip_username", "") ?: "",
                domain = prefs.getString("sip_domain", "") ?: "",
                transport = prefs.getString("sip_transport", "") ?: "",
                network = network,
                gatewayNode = l.gatewayNode ?: connection.gatewayNode,
                call = call?.let {
                    CallDetails(
                        codec = it.codec,
                        codecLabel = CodecPreferences.labelForSdp(it.codec),
                        encrypted = it.encrypted,
                        stats = l.stats,
                        inConference = it.inConference,
                        recording = it.recording,
                    )
                },
                codecOrder = codecs,
                appVersion = BuildConfig.VERSION_NAME,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ConnectionDetails(
                connection = GatewayConnection.state.value,
                displayName = "",
                extension = "",
                domain = "",
                transport = "",
                network = NetworkStatus.state.value,
                gatewayNode = GatewayConnection.state.value.gatewayNode,
                call = null,
                codecOrder = CodecPreferences.state.value,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
    }

    fun reconnect() {
        if (!Fixtures.active) GatewayConnection.reconnect(getApplication())
    }
}
