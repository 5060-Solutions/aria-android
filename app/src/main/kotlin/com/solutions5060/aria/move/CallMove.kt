package com.solutions5060.aria.move

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A call the PBX says the user has on one of their devices (`GET /me/calls/active`). */
data class ActiveCall(
    val id: String,
    /** `active` or `held`. */
    val state: String,
    val direction: String,
    val remoteNumber: String,
    val remoteName: String?,
    /** When the call started, in epoch milliseconds; null if the PBX sent no readable time. */
    val startedAtMillis: Long?,
    val deviceRegistrationId: String,
    val deviceLabel: String,
    /** The Call-ID of that device's leg. */
    val sipCallId: String?,
) {
    /** The name to show: the PBX's name for the far party, else the number. */
    val displayName: String get() = remoteName?.takeIf { it.isNotBlank() } ?: remoteNumber
}

/** One of the user's registered devices (`GET /me/devices`). */
data class MoveDevice(
    val registrationId: String,
    val label: String,
    val userAgent: String?,
    val transport: String?,
    val online: Boolean,
)

/** A move this device asked for and is waiting to receive. */
data class PendingMove(
    val moveId: String,
    val callId: String,
    /** When the call began on the other device, in epoch milliseconds, if known. */
    val callStartedAtMillis: Long?,
    val requestedAtMillis: Long,
)

/** What the PBX pushes over `/api/v1/ws` that this feature reads. */
sealed interface MoveEvent {
    data class CallActiveElsewhere(val call: ActiveCall) : MoveEvent
    data class CallMoved(val callId: String, val fromRegistrationId: String, val toRegistrationId: String) : MoveEvent
    data class CallEnded(val callId: String) : MoveEvent

    /** The PBX thinks this device is the better place for [call] (it left the network, or is near). */
    data class HandoffSuggested(val call: ActiveCall) : MoveEvent
}

/** What to do with an incoming call that may be a move. */
sealed interface MoveDecision {
    /** An ordinary call. */
    data object Ring : MoveDecision

    /** The move this device asked for: answer with no ring. */
    data class AutoAnswer(val callStartedAtMillis: Long?) : MoveDecision

    /** Another device's move (or one that timed out here): ring, labelled. */
    data object RingMoving : MoveDecision
}

object CallMovePolicy {

    /** The PBX gives a move 20 s to be answered; keep the request a little longer for the INVITE's push. */
    const val PENDING_TTL_MS = 30_000L

    /**
     * An INVITE is answered with no ring only when it carries the id this
     * device was given for a move it asked for, and that request is still
     * live. Any other `X-Aria-Move` rings.
     */
    fun decide(offerMoveId: String?, pending: PendingMove?, nowMillis: Long): MoveDecision = when {
        offerMoveId.isNullOrEmpty() -> MoveDecision.Ring
        pending != null &&
            pending.moveId.equals(offerMoveId, ignoreCase = true) &&
            nowMillis - pending.requestedAtMillis <= PENDING_TTL_MS ->
            MoveDecision.AutoAnswer(pending.callStartedAtMillis)
        else -> MoveDecision.RingMoving
    }

    /**
     * The calls the banner offers: those on another device. A call is this
     * app's own when its leg is on this registration, or its Call-ID is one
     * this app holds, or (when neither is known) it matches a live local call
     * by the far party's number.
     */
    fun bannerCalls(
        calls: List<ActiveCall>,
        ownRegistrationId: String?,
        ownSipCallIds: Set<String>,
        localRemoteNumbers: Set<String>,
        dismissedIds: Set<String> = emptySet(),
    ): List<ActiveCall> = calls.filter { call ->
        call.id !in dismissedIds &&
            !isOwn(call, ownRegistrationId, ownSipCallIds, localRemoteNumbers)
    }

    fun isOwn(
        call: ActiveCall,
        ownRegistrationId: String?,
        ownSipCallIds: Set<String>,
        localRemoteNumbers: Set<String>,
    ): Boolean = when {
        ownRegistrationId != null && call.deviceRegistrationId == ownRegistrationId -> true
        call.sipCallId != null && call.sipCallId in ownSipCallIds -> true
        ownRegistrationId == null && localRemoteNumbers.any { sameNumber(it, call.remoteNumber) } -> true
        else -> false
    }

    /** The server-side call behind this app's own call, for "Move to…". */
    fun ownCall(
        calls: List<ActiveCall>,
        ownRegistrationId: String?,
        ownSipCallIds: Set<String>,
        localRemoteNumber: String?,
    ): ActiveCall? =
        calls.firstOrNull { ownRegistrationId != null && it.deviceRegistrationId == ownRegistrationId }
            ?: calls.firstOrNull { it.sipCallId != null && it.sipCallId in ownSipCallIds }
            ?: localRemoteNumber?.let { n -> calls.singleOrNull { sameNumber(n, it.remoteNumber) } }

    /** Devices a call can move to: online, and not the one it is on. */
    fun targets(devices: List<MoveDevice>, currentRegistrationId: String?, ownRegistrationId: String?): List<MoveDevice> =
        devices.filter { it.online && it.registrationId != currentRegistrationId && it.registrationId != ownRegistrationId }

    /** Digits only, compared on the last ten, so `+13035550100` and `sip:3035550100@pbx` match. */
    fun sameNumber(a: String, b: String): Boolean {
        val x = digits(a)
        val y = digits(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x.takeLast(10) == y.takeLast(10)
    }

    private fun digits(s: String) = s.substringAfter("sip:").substringBefore("@").filter { it.isDigit() }
}

object MoveJson {

    fun activeCalls(json: String): List<ActiveCall> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { activeCall(array.optJSONObject(it)) }
    }

    fun activeCall(o: JSONObject?): ActiveCall? {
        o ?: return null
        val id = o.optString("id", "").ifEmpty { return null }
        val remote = o.optJSONObject("remote")
        val device = o.optJSONObject("device")
        return ActiveCall(
            id = id,
            state = o.optString("state", "active"),
            direction = o.optString("direction", ""),
            remoteNumber = remote?.optString("number", "").orEmpty(),
            remoteName = remote?.takeUnless { it.isNull("name") }?.optString("name", "")?.ifEmpty { null },
            startedAtMillis = instantMillis(o.optString("started_at", "")),
            deviceRegistrationId = device?.optString("registration_id", "").orEmpty(),
            deviceLabel = device?.optString("label", "")?.ifEmpty { null }
                ?: device?.optString("user_agent", "")?.ifEmpty { null }.orEmpty(),
            sipCallId = o.optString("sip_call_id", "").ifEmpty { null },
        )
    }

    fun devices(json: String): List<MoveDevice> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { device(array.optJSONObject(it)) }
    }

    fun device(o: JSONObject?): MoveDevice? {
        o ?: return null
        val id = o.optString("registration_id", "").ifEmpty { return null }
        return MoveDevice(
            registrationId = id,
            label = o.optString("label", "").ifEmpty { o.optString("user_agent", "") },
            userAgent = o.optString("user_agent", "").ifEmpty { null },
            transport = o.optString("transport", "").ifEmpty { null },
            online = o.optBoolean("online", false),
        )
    }

    fun device(json: String): MoveDevice? = device(JSONObject(json))

    /** `{"move_id": "<uuid>"}` from the 202. */
    fun moveId(json: String): String =
        JSONObject(json).optString("move_id", "").ifEmpty { throw IllegalStateException("The PBX returned no move id") }

    /** One WebSocket text frame, or null when it is not a call-move event. */
    fun event(text: String): MoveEvent? {
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        return when (o.optString("type")) {
            "call_active_elsewhere" -> activeCall(o.optJSONObject("call"))?.let { MoveEvent.CallActiveElsewhere(it) }
            "handoff_suggested" -> activeCall(o.optJSONObject("call"))?.let { MoveEvent.HandoffSuggested(it) }
            "call_moved" -> MoveEvent.CallMoved(
                callId = o.optString("call_id", "").ifEmpty { return null },
                fromRegistrationId = o.optString("from_registration_id", ""),
                toRegistrationId = o.optString("to_registration_id", ""),
            )
            "call_ended" -> MoveEvent.CallEnded(o.optString("call_id", "").ifEmpty { return null })
            else -> null
        }
    }

    private fun instantMillis(s: String): Long? =
        if (s.isEmpty()) null else try {
            Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            null
        }
}
