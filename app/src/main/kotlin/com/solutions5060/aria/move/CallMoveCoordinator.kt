package com.solutions5060.aria.move

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.pbx.StoredPbxAuth
import com.solutions5060.aria.proximity.ContextResult
import com.solutions5060.aria.proximity.HandoffNotifier
import com.solutions5060.aria.proximity.HandoffPrefs
import com.solutions5060.aria.pbx.withAuth
import com.solutions5060.aria.service.DiagLog
import com.solutions5060.aria.service.EngineManager
import com.solutions5060.aria.service.PbxApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicReference

/**
 * Moving a call between the user's devices (call-move contract). Holds what
 * the banner shows, this device's PBX registration, the move this device
 * asked for (so its INVITE is answered with no ring), and what is needed to
 * say "Moved to <device>" when the leg here ends.
 *
 * Network work runs in [AppScope]; the state is exposed as flows.
 */
object CallMoveCoordinator {

    private const val TAG = "CallMove"
    const val POLL_MS = 15_000L

    private lateinit var appContext: Context

    private val _calls = MutableStateFlow<List<ActiveCall>>(emptyList())

    /** Every active call the PBX reports, this device's own included. */
    val calls: StateFlow<List<ActiveCall>> = _calls.asStateFlow()

    private val _banner = MutableStateFlow<List<ActiveCall>>(emptyList())

    /** The calls on other devices, for the banner. */
    val banner: StateFlow<List<ActiveCall>> = _banner.asStateFlow()

    private val _suggested = MutableStateFlow<ActiveCall?>(null)

    /** The call the PBX suggested continuing here ([MoveEvent.HandoffSuggested]). */
    val suggested: StateFlow<ActiveCall?> = _suggested.asStateFlow()

    private val _message = MutableStateFlow<MoveMessage?>(null)

    /** The last thing to tell the user (a refused move), cleared with [clearMessage]. */
    val message: StateFlow<MoveMessage?> = _message.asStateFlow()

    private val _messageDetail = MutableStateFlow<String?>(null)

    /** The PBX's reason accompanying [message] when a started move failed, if it gave one. */
    val messageDetail: StateFlow<String?> = _messageDetail.asStateFlow()

    private val watcher = MoveWatcher(
        scope = AppScope.scope,
        fetch = { id ->
            withContext(Dispatchers.IO) {
                val auth = StoredPbxAuth(appContext)
                MoveWatcher.parse(auth.withAuth { PbxApiClient(auth.apiUrl()).moveStatus(it, id) })
            }
        },
        onFailed = { id, detail -> moveFailed(id, detail) },
        onMoved = { id -> Log.i(TAG, "Move completed") ; refresh() },
    )

    private val _devices = MutableStateFlow<List<MoveDevice>>(emptyList())
    val devices: StateFlow<List<MoveDevice>> = _devices.asStateFlow()

    private val pending = AtomicReference<PendingMove?>(null)
    private val dismissed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var ownRegistrationId: String? = null

    private var deviceNames = mapOf<String, String>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun clearMessage() {
        _message.value = null
        _messageDetail.value = null
    }

    /** A move that was accepted did not happen: stop waiting for its INVITE and say so. */
    private fun moveFailed(moveId: String, detail: String?) {
        val p = pending.get()
        if (p != null && p.moveId.equals(moveId, ignoreCase = true)) pending.compareAndSet(p, null)
        DiagLog.log("call", "move failed")
        _messageDetail.value = detail
        _message.value = MoveMessage.FAILED
        refresh()
    }

    /** The user closed the banner for [callId]. */
    fun dismiss(callId: String) {
        dismissed.add(callId)
        recompute()
    }

    // ── Reading the PBX ────────────────────────────────────────────────

    /** Fetch the active calls (on resume, every [POLL_MS], and on an event). */
    fun refresh() {
        if (!::appContext.isInitialized) return
        AppScope.scope.launch {
            try {
                val auth = StoredPbxAuth(appContext)
                val json = auth.withAuth { PbxApiClient(auth.apiUrl()).activeCalls(it) }
                _calls.value = MoveJson.activeCalls(json)
                awaitedSuggestion?.let { id -> _calls.value.firstOrNull { it.id == id }?.let(::showSuggestion) }
                recompute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 404 on a PBX that does not have the feature yet: no banner, no noise.
                Log.i(TAG, "Active calls not read: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Apply one WebSocket event. */
    fun onEvent(event: MoveEvent) {
        when (event) {
            is MoveEvent.CallActiveElsewhere -> {
                _calls.update { list -> list.filterNot { it.id == event.call.id } + event.call }
                recompute()
            }
            is MoveEvent.CallEnded -> {
                _calls.update { list -> list.filterNot { it.id == event.callId } }
                if (_suggested.value?.id == event.callId) _suggested.value = null
                dismissed.remove(event.callId)
                watcher.onCallEnded(event.callId)
                pending.get()?.takeIf { it.callId == event.callId }?.let { pending.compareAndSet(it, null) }
                recompute()
            }
            is MoveEvent.CallMoveFailed -> watcher.onFailedEvent(event.callId, event.moveId, event.detail)
            is MoveEvent.CallMoved -> {
                watcher.onMovedEvent(event.callId)
                // This device's leg was the one given up: remember where it went.
                if (event.fromRegistrationId.isNotEmpty() && event.fromRegistrationId == ownRegistrationId) {
                    val label = deviceNames[event.toRegistrationId]
                    if (label != null) CallManager.markMoved(event.callId, label) else refreshDevices()
                }
                refresh()
            }
            is MoveEvent.HandoffSuggested -> onSuggested(event)
        }
    }

    /** The call a suggestion named that is not in the list yet: shown once the list has it. */
    @Volatile
    private var awaitedSuggestion: String? = null

    /**
     * Only a suggestion to move to this device is shown ("Move here"); one
     * naming another device is that device's to show.
     */
    private fun onSuggested(event: MoveEvent.HandoffSuggested) {
        AppScope.scope.launch {
            val own = ownRegistrationId ?: resolveOwnRegistration() ?: return@launch
            if (event.toRegistrationId != own || !HandoffPrefs.load(appContext).enabled) return@launch
            val call = _calls.value.firstOrNull { it.id == event.callId }
            if (call != null) {
                showSuggestion(call)
            } else {
                awaitedSuggestion = event.callId
                refresh()
            }
        }
    }

    private fun showSuggestion(call: ActiveCall) {
        awaitedSuggestion = null
        _suggested.value = call
        recompute()
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            HandoffNotifier.show(appContext, call)
        }
    }

    /** The answer to a context report: a move this device started must be answered without ringing. */
    fun onContextResult(result: ContextResult) {
        if (result.action == "moved" && result.callId != null && result.moveId != null) {
            val call = _calls.value.firstOrNull { it.id == result.callId }
            pending.set(PendingMove(result.moveId, result.callId, call?.startedAtMillis, System.currentTimeMillis(), call?.remoteNumber))
            watcher.start(result.moveId, result.callId)
            DiagLog.log("call", "move started by a left_network report")
        }
    }

    fun clearSuggestion() {
        _suggested.value = null
    }

    private fun recompute() {
        val local = CallManager.calls.value
        _banner.value = CallMovePolicy.bannerCalls(
            calls = _calls.value,
            ownRegistrationId = ownRegistrationId,
            ownSipCallIds = emptySet(),
            localRemoteNumbers = local.filter { it.phase != com.solutions5060.aria.call.CallPhase.ENDED }
                .map { it.remoteUri }.toSet(),
            dismissedIds = dismissed.toSet(),
        )
    }

    private fun refreshDevices() {
        AppScope.scope.launch {
            runCatching { loadDevices() }
        }
    }

    private fun loadDevices(): List<MoveDevice> {
        val auth = StoredPbxAuth(appContext)
        val list = MoveJson.devices(auth.withAuth { PbxApiClient(auth.apiUrl()).devices(it, sipInstance(appContext)) })
        deviceNames = list.associate { it.registrationId to it.label }
        _devices.value = list
        return list
    }

    /**
     * The PBX's record of this app's own call to [localRemoteUri] for Hold for
     * me, as last read (no network): matched by number, so with two calls on
     * this phone the right one is picked, or none.
     */
    fun holdCallFor(localRemoteUri: String): ActiveCall? =
        CallMovePolicy.holdCall(_calls.value, ownRegistrationId, localRemoteUri)

    /**
     * Read this device's registration and the active calls now, and wait for
     * them (Hold for me reads them just before it starts). Failures leave the
     * last list as it was.
     */
    suspend fun refreshNow() {
        if (!::appContext.isInitialized) return
        withContext(Dispatchers.IO) {
            try {
                resolveOwnRegistration()
                val auth = StoredPbxAuth(appContext)
                _calls.value = MoveJson.activeCalls(auth.withAuth { PbxApiClient(auth.apiUrl()).activeCalls(it) })
                recompute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Active calls not read: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The label of the device named [registrationId], if devices were read. */
    fun deviceLabel(registrationId: String): String? = deviceNames[registrationId]

    // ── Moving here ────────────────────────────────────────────────────

    /** True while a move this device asked for is waiting for its INVITE. */
    fun hasPending(nowMillis: Long = System.currentTimeMillis()): Boolean =
        pending.get()?.let { nowMillis - it.requestedAtMillis <= CallMovePolicy.PENDING_TTL_MS } == true

    /** The move to match an INVITE against; it stays until [claim]ed or expired. */
    fun pendingMove(): PendingMove? = pending.get()

    /** Consume the pending move once its INVITE has been answered. */
    fun claim(moveId: String) {
        val p = pending.get()
        if (p != null && p.moveId.equals(moveId, ignoreCase = true)) pending.compareAndSet(p, null)
    }

    /**
     * Whether an incoming call from [remoteUri] may wait, silent, for its
     * offer: only the far party of the call being moved. Any other call rings.
     */
    fun mayWaitForMove(remoteUri: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val p = pending.get() ?: return false
        return nowMillis - p.requestedAtMillis <= CallMovePolicy.PENDING_TTL_MS &&
            p.remoteNumber != null && CallMovePolicy.sameNumber(remoteUri, p.remoteNumber)
    }

    /** The label of the device a non-matching move comes from, as best the banner knows. */
    fun movingFromLabel(remoteUri: String): String? =
        _calls.value.firstOrNull { CallMovePolicy.sameNumber(remoteUri, it.remoteNumber) }?.deviceLabel

    /**
     * "Move here": find this device, POST the move, keep the move id. The
     * PBX's INVITE arrives like any call and [CallManager] answers it.
     */
    fun moveHere(call: ActiveCall) {
        AppScope.scope.launch {
            try {
                val auth = StoredPbxAuth(appContext)
                val own = ownRegistration(auth)
                val json = auth.withAuth { PbxApiClient(auth.apiUrl()).moveCall(it, call.id, own) }
                val moveId = MoveJson.moveId(json)
                pending.set(PendingMove(moveId, call.id, call.startedAtMillis, System.currentTimeMillis(), call.remoteNumber))
                watcher.start(moveId, call.id)
                DiagLog.log("call", "move requested to this device")
                _suggested.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Move here failed: ${e.javaClass.simpleName}")
                _message.value = messageFor(e)
            }
        }
    }

    /** "Move to…": send this device's own call to [target]; the leg here then ends as "Moved to …". */
    fun moveTo(localRemoteNumber: String?, target: MoveDevice) {
        AppScope.scope.launch {
            try {
                val auth = StoredPbxAuth(appContext)
                val own = runCatching { ownRegistration(auth) }.getOrNull()
                val calls = MoveJson.activeCalls(auth.withAuth { PbxApiClient(auth.apiUrl()).activeCalls(it) })
                _calls.value = calls
                val call = CallMovePolicy.ownCall(calls, own, emptySet(), localRemoteNumber)
                    ?: throw PbxApiClient.HttpException(404, "No such call")
                val moveId = MoveJson.moveId(
                    auth.withAuth { PbxApiClient(auth.apiUrl()).moveCall(it, call.id, target.registrationId) },
                )
                watcher.start(moveId, call.id)
                withContext(Dispatchers.Main) { CallManager.markMovedForNumber(localRemoteNumber, target.label) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Move to another device failed: ${e.javaClass.simpleName}")
                _message.value = messageFor(e)
            }
        }
    }

    /** The user's calls on devices other than this one, read fresh (for the proximity trigger). */
    fun fetchOtherDeviceCalls(auth: StoredPbxAuth): List<ActiveCall> {
        val calls = MoveJson.activeCalls(auth.withAuth { PbxApiClient(auth.apiUrl()).activeCalls(it) })
        _calls.value = calls
        val own = ownRegistrationId
        return calls.filter { !CallMovePolicy.isOwn(it, own, emptySet(), emptySet()) && it.deviceRegistrationId != own }
    }

    /** "Move here" for a call by id (the notification's action). */
    fun moveHereById(callId: String) {
        val call = _calls.value.firstOrNull { it.id == callId } ?: return
        moveHere(call)
    }

    /** Load the devices a call can move to, for the sheet. */
    suspend fun targets(localRemoteNumber: String?): List<MoveDevice> = withContext(Dispatchers.IO) {
        val list = loadDevices()
        val auth = StoredPbxAuth(appContext)
        val own = runCatching { ownRegistration(auth) }.getOrNull()
        val current = _calls.value.let { CallMovePolicy.ownCall(it, own, emptySet(), localRemoteNumber) }?.deviceRegistrationId
        CallMovePolicy.targets(list, current, own)
    }

    /** This device's registration id: `/me/devices/current` by its `+sip.instance` urn. */
    fun ownRegistration(auth: StoredPbxAuth): String {
        ownRegistrationId?.let { return it }
        val api = PbxApiClient(auth.apiUrl())
        val urn = sipInstance(appContext)
        val device = auth.withAuth { api.currentDevice(it, urn) }
        val id = MoveJson.device(device)?.registrationId ?: throw IllegalStateException("This device is not registered")
        ownRegistrationId = id
        return id
    }

    /** Resolve this device's registration (for proximity reports) without throwing. */
    fun resolveOwnRegistration(): String? = try {
        ownRegistration(StoredPbxAuth(appContext))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * The `+sip.instance` urn this install registers with: `urn:uuid:` and
     * the PBX device id the registration returned (a stable per-install id).
     */
    fun sipInstance(context: Context): String {
        val id = context.getSharedPreferences(EngineManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("device_id", null)
            ?: throw IllegalStateException("This device is not registered")
        return "urn:uuid:$id"
    }

    /** Clears what belongs to one account (sign-out). */
    /** This device registered again or the network changed: read its registration id afresh next time. */
    fun forgetOwnRegistration() {
        ownRegistrationId = null
    }

    fun reset() {
        watcher.cancel()
        pending.set(null)
        ownRegistrationId = null
        _calls.value = emptyList()
        _banner.value = emptyList()
        _suggested.value = null
        dismissed.clear()
    }

    private fun messageFor(e: Exception): MoveMessage = MoveMessage.forHttp((e as? PbxApiClient.HttpException)?.code)

    /** Polls while [active]: used from a lifecycle-aware effect. */
    suspend fun pollWhile() {
        while (true) {
            refresh()
            delay(POLL_MS)
        }
    }
}

/** Why a move did not start; the UI maps each to a string. */
enum class MoveMessage {
    NOT_FOUND, NOT_MOVABLE, DEVICE_UNAVAILABLE, FAILED;

    companion object {
        /** POST /me/calls/{id}/move: 404 call ended, 409 not movable now, 422 target offline or unknown. */
        fun forHttp(code: Int?): MoveMessage = when (code) {
            404 -> NOT_FOUND
            409 -> NOT_MOVABLE
            422 -> DEVICE_UNAVAILABLE
            else -> FAILED
        }
    }
}
