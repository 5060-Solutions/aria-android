package com.solutions5060.aria.hold

import android.content.Context
import android.util.Log
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.call.CallUiState
import com.solutions5060.aria.move.CallMoveCoordinator
import com.solutions5060.aria.pbx.SelfServiceJson
import com.solutions5060.aria.pbx.StoredPbxAuth
import com.solutions5060.aria.pbx.withAuth
import com.solutions5060.aria.service.PbxApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hold for me: the wait the PBX keeps for a call, its transcript, and the
 * flag that switches the feature on. Everything is keyed by the PBX call id
 * (the id every route takes). Hidden until `GET /me` lists `hold_for_me`.
 */
object HoldForMeCoordinator {

    private const val TAG = "HoldForMe"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var api: HoldApi? = null

    private val _available = MutableStateFlow(false)

    /** True when the PBX offers the feature to this account. Off until `/me` says so. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _waits = MutableStateFlow<Map<String, HoldWait>>(emptyMap())

    /** The latest wait per PBX call id; a wait that has ended stays until [reconcile] or [reset] drops it. */
    val waits: StateFlow<Map<String, HoldWait>> = _waits.asStateFlow()

    private val _transcripts = MutableStateFlow<Map<String, List<TranscriptLine>>>(emptyMap())

    /** The assistant's transcript per PBX call id; each event replaces the last. Never stored. */
    val transcripts: StateFlow<Map<String, List<TranscriptLine>>> = _transcripts.asStateFlow()

    private val _refusal = MutableStateFlow<HoldRefusal?>(null)

    /** Why the last start was refused, until [clearRefusal]. */
    val refusal: StateFlow<HoldRefusal?> = _refusal.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun clearRefusal() {
        _refusal.value = null
    }

    /** The service to use: the fixture's while one is installed, else the PBX. */
    fun service(): HoldApi? = api ?: appContext?.let { LiveHoldApi(StoredPbxAuth(it)) }

    /** Debug fixtures only: use [fake] and set the flag without asking the PBX. */
    fun installFixture(fake: HoldApi, available: Boolean) {
        api = fake
        _available.value = available
        _waits.value = emptyMap()
        _transcripts.value = emptyMap()
        _refusal.value = null
    }

    fun clearFixture() {
        api = null
        reset()
    }

    /** Sign-out: forget the account's flag, waits and transcripts. */
    fun reset() {
        _available.value = false
        _waits.value = emptyMap()
        _transcripts.value = emptyMap()
        _refusal.value = null
    }

    /** The PBX's id for this app's own call, or null while unknown. */
    fun pbxCallId(call: CallUiState): String? = CallMoveCoordinator.ownCallFor(call.remoteUri)?.id

    fun canHoldForMe(call: CallUiState, inConference: Boolean): Boolean =
        HoldForMePolicy.canHold(_available.value, call, inConference, pbxCallId(call))

    // ── Reading the PBX ────────────────────────────────────────────────

    /** On foreground: read the flag from `GET /me`, then the open waits when it is on. */
    suspend fun refresh() {
        if (api == null) {
            val context = appContext ?: return
            try {
                val auth = StoredPbxAuth(context)
                val body = withContext(Dispatchers.IO) { auth.withAuth { PbxApiClient(auth.apiUrl()).me(it) } }
                _available.value = SelfServiceJson.meFeatures(body).holdForMe
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An older PBX or no network: the flag keeps its last value; an absent one is off.
                Log.i(TAG, "Features not read: ${e.javaClass.simpleName}")
                return
            }
        }
        if (_available.value) reconcile()
    }

    /** Replace what is known with the PBX's open waits: a wait that is not listed any more has ended. */
    suspend fun reconcile() {
        val service = service() ?: return
        try {
            val open = service.openWaits().filter { it.isOpen }.associateBy { it.callId }
            _waits.value = open
            _transcripts.update { t -> t.filterKeys { it in open } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Open waits not read: ${e.javaClass.simpleName}")
        }
    }

    /** Apply one `hold_wait_state` event. */
    fun onEvent(e: HoldEvent) {
        when (e) {
            is HoldEvent.State -> {
                _waits.update { it + (e.wait.callId to e.wait) }
                _transcripts.update { it + (e.wait.callId to e.transcript) }
            }
        }
    }

    // ── Acting ─────────────────────────────────────────────────────────

    /** Start waiting on [call]. A refusal leaves the call as it was and sets [refusal]. */
    suspend fun start(call: CallUiState, greeting: HoldGreeting) {
        val service = service()
        val id = pbxCallId(call)
        if (service == null || id == null) {
            _refusal.value = HoldRefusal.OTHER
            return
        }
        try {
            val wait = service.start(id, greeting, HoldForMePolicy.displayName(call))
            _waits.update { it + (id to wait) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val http = e as? PbxApiClient.HttpException
            Log.w(TAG, "Start refused: ${e.javaClass.simpleName} ${http?.code}")
            _refusal.value = HoldJson.refusal(http?.code ?: 0, http?.body)
        }
    }

    /** Stop waiting: [rejoin] rings this person now, else the call is hung up. */
    suspend fun cancel(id: String, rejoin: Boolean) {
        val service = service() ?: return
        try {
            _waits.update { it + (id to service.cancel(id, if (rejoin) "rejoin" else "hang_up")) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cancel failed: ${e.javaClass.simpleName}")
        }
    }

    private val stillInFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * "Still on hold": the far party is still waiting after we joined. A second
     * tap while the first is in flight is ignored; a 409 (the 10 s window has
     * passed) is not an error, the call simply continues.
     */
    suspend fun stillOnHold(id: String) {
        val service = service() ?: return
        if (!stillInFlight.add(id)) return
        try {
            _waits.update { it + (id to service.stillOnHold(id)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Still on hold not accepted: ${e.javaClass.simpleName}")
        } finally {
            stillInFlight.remove(id)
        }
    }

    /** Fire-and-forget [start] for the call screen. */
    fun startAsync(call: CallUiState, greeting: HoldGreeting) {
        AppScope.scope.launch { start(call, greeting) }
    }
}
