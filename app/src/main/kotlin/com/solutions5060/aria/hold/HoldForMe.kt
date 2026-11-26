package com.solutions5060.aria.hold

import androidx.annotation.StringRes
import com.solutions5060.aria.R
import com.solutions5060.aria.call.CallPhase
import com.solutions5060.aria.call.CallUiState
import com.solutions5060.aria.pbx.StoredPbxAuth
import com.solutions5060.aria.pbx.withAuth
import com.solutions5060.aria.service.PbxApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uniffi.aria_mobile.CallDirection
import java.time.Instant

/** What the far party hears while we wait (`greeting` on the wire). */
enum class HoldGreeting(val wire: String) {
    SILENCE("silence"),
    LINE("line"),
    ASSISTANT("assistant");

    companion object {
        /** Unknown or absent values read as the plain message. */
        fun fromWire(s: String?): HoldGreeting = entries.firstOrNull { it.wire == s } ?: LINE
    }
}

/** One Hold for me wait (the PBX's `HoldWait`). */
data class HoldWait(
    val holdWaitId: String,
    val callId: String,
    /** `requested`, `waiting`, `human_detected`, `ringing`, `joined` or `ended`. `requested` shows as waiting. */
    val state: String,
    val greeting: HoldGreeting,
    val startedAtMillis: Long?,
    val stateSinceMillis: Long?,
    val maxWaitSecs: Int,
    val falseAlarms: Int,
    val remoteNumber: String,
    val remoteName: String?,
    /** `human`, `rejoin` or `max_wait`; only while ringing or joined. */
    val ringReason: String?,
    /** `rejoined`, `hung_up`, `far_hung_up`, `unanswered`, `max_wait` or `lost`; only once ended. */
    val endedReason: String?,
) {
    val isOpen: Boolean get() = state != "ended"
}

/** `GET /me/hold-for-me/settings`. */
data class HoldSettings(val greeting: HoldGreeting, val assistantAvailable: Boolean, val maxWaitSecs: Int) {
    companion object {
        /** What the sheet offers when the settings could not be read. */
        val Fallback = HoldSettings(HoldGreeting.LINE, assistantAvailable = false, maxWaitSecs = 10_800)

        /** What the sheet shows while the settings are loading: the plain message, the assistant not ruled out. */
        val Opening = HoldSettings(HoldGreeting.LINE, assistantAvailable = true, maxWaitSecs = 10_800)
    }
}

data class TranscriptLine(
    /** `assistant` or `caller`. */
    val speaker: String,
    val text: String,
)

/** What the PBX pushes over `/api/v1/ws` that this feature reads. */
sealed interface HoldEvent {
    /** The latest state of a wait, and its full transcript so far (it replaces any earlier one). */
    data class State(val wait: HoldWait, val transcript: List<TranscriptLine>) : HoldEvent
}

/** Why the PBX refused to start a wait; the sheet closes and the banner shows [message]. */
enum class HoldRefusal(@StringRes val message: Int) {
    EMERGENCY(R.string.ui_hold_error_emergency),
    BUSY(R.string.ui_hold_error_busy),
    ALREADY(R.string.ui_hold_error_already),
    OTHER(R.string.ui_hold_error_start),
}

object HoldJson {

    /** A `hold_wait_state` event; null for any other event or text that is not JSON. */
    fun event(text: String): HoldEvent? = try {
        val o = JSONObject(text)
        if (o.optString("type") != "hold_wait_state") {
            null
        } else {
            val wait = wait(o.getJSONObject("hold_wait"))
            val lines = o.optJSONArray("transcript") ?: JSONArray()
            val transcript = (0 until lines.length()).mapNotNull { i ->
                val l = lines.optJSONObject(i) ?: return@mapNotNull null
                val text = l.optString("text", "")
                if (text.isEmpty()) null else TranscriptLine(l.optString("speaker", ""), text)
            }
            HoldEvent.State(wait, transcript)
        }
    } catch (_: Exception) {
        null
    }

    fun wait(body: String): HoldWait = wait(JSONObject(body))

    fun waits(body: String): List<HoldWait> {
        val a = JSONArray(body)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::wait) }
    }

    fun settings(body: String): HoldSettings {
        val o = JSONObject(body)
        return HoldSettings(
            greeting = HoldGreeting.fromWire(o.optString("greeting")),
            assistantAvailable = o.optBoolean("assistant_available", false),
            maxWaitSecs = o.optInt("max_wait_secs", HoldSettings.Fallback.maxWaitSecs),
        )
    }

    /** The A0 mapping: only a 409 with a known reason is specific; everything else (404, 403, 422, a network error) is OTHER. */
    fun refusal(code: Int, body: String?): HoldRefusal {
        if (code != 409 || body == null) return HoldRefusal.OTHER
        val reason = try {
            JSONObject(body).optString("reason", "")
        } catch (_: Exception) {
            ""
        }
        return when (reason) {
            "emergency" -> HoldRefusal.EMERGENCY
            "conference", "transfer" -> HoldRefusal.BUSY
            "already_holding" -> HoldRefusal.ALREADY
            else -> HoldRefusal.OTHER
        }
    }

    private fun wait(o: JSONObject): HoldWait {
        val remote = o.optJSONObject("remote")
        return HoldWait(
            holdWaitId = o.getString("hold_wait_id"),
            callId = o.optString("call_id", ""),
            state = o.optString("state", "waiting"),
            greeting = HoldGreeting.fromWire(o.optString("greeting")),
            startedAtMillis = instant(o.optString("started_at", "")),
            stateSinceMillis = instant(o.optString("state_since", "")),
            maxWaitSecs = o.optInt("max_wait_secs", HoldSettings.Fallback.maxWaitSecs),
            falseAlarms = o.optInt("false_alarms", 0),
            remoteNumber = remote?.optString("number", "").orEmpty(),
            remoteName = remote?.takeUnless { it.isNull("name") }?.optString("name", "")?.ifEmpty { null },
            ringReason = o.takeUnless { it.isNull("ring_reason") }?.optString("ring_reason", "")?.ifEmpty { null },
            endedReason = o.takeUnless { it.isNull("ended_reason") }?.optString("ended_reason", "")?.ifEmpty { null },
        )
    }

    private fun instant(s: String): Long? =
        if (s.isEmpty()) null else try {
            Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            null
        }
}

/** When the call screen may offer Hold for me. */
object HoldForMePolicy {

    /** The PBX's limit on the name the app sends. */
    const val MAX_NAME = 64

    /**
     * Needs the feature flag, an outgoing call that is connected and not in a
     * conference, and the PBX's id for it ([pbxCallId], from `CallMovePolicy.ownCall`).
     */
    fun canHold(available: Boolean, call: CallUiState, inConference: Boolean, pbxCallId: String?): Boolean =
        available &&
            call.direction == CallDirection.OUTBOUND &&
            call.phase == CallPhase.ACTIVE &&
            call.connectedAtElapsed != null &&
            !inConference &&
            !pbxCallId.isNullOrEmpty()

    /** The business name to show later: the contact's name if known, else the network's, else none. */
    fun displayName(call: CallUiState): String? =
        (call.contactName?.takeIf { it.isNotBlank() } ?: call.remoteName?.takeIf { it.isNotBlank() })
            ?.trim()?.take(MAX_NAME)
}

/** The Hold for me service. */
interface HoldApi {
    suspend fun start(callId: String, greeting: HoldGreeting?, displayName: String?): HoldWait
    suspend fun cancel(callId: String, action: String): HoldWait
    suspend fun stillOnHold(callId: String): HoldWait
    suspend fun openWaits(): List<HoldWait>
    suspend fun settings(): HoldSettings
    suspend fun setGreeting(g: HoldGreeting): HoldSettings
}

/** The PBX, as the signed-in user. */
class LiveHoldApi(private val auth: StoredPbxAuth) : HoldApi {

    private suspend fun <T> call(block: (PbxApiClient, String) -> T): T = withContext(Dispatchers.IO) {
        auth.withAuth { jwt -> block(PbxApiClient(auth.apiUrl()), jwt) }
    }

    override suspend fun start(callId: String, greeting: HoldGreeting?, displayName: String?) =
        HoldJson.wait(call { api, jwt -> api.holdStart(jwt, callId, greeting?.wire, displayName) })

    override suspend fun cancel(callId: String, action: String) =
        HoldJson.wait(call { api, jwt -> api.holdCancel(jwt, callId, action) })

    override suspend fun stillOnHold(callId: String) =
        HoldJson.wait(call { api, jwt -> api.holdStillOnHold(jwt, callId) })

    override suspend fun openWaits() = HoldJson.waits(call { api, jwt -> api.holdWaits(jwt) })

    override suspend fun settings() = HoldJson.settings(call { api, jwt -> api.holdSettings(jwt) })

    override suspend fun setGreeting(g: HoldGreeting) =
        HoldJson.settings(call { api, jwt -> api.setHoldGreeting(jwt, g.wire) })
}
