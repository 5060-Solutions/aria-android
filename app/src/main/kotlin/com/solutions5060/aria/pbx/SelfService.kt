package com.solutions5060.aria.pbx

import android.content.Context
import com.solutions5060.aria.service.PbxApiClient
import org.json.JSONArray
import org.json.JSONObject

/** `GET /extensions/{id}/features` (aria-db ExtensionFeature), the parts the user sees. */
data class ExtensionFeatures(
    val doNotDisturb: Boolean,
    val maxConcurrentCalls: Int,
)

/** The forward types the PBX acts on (`call_forwards::FORWARD_TYPES`). */
enum class ForwardType(val wire: String) {
    UNCONDITIONAL("unconditional"),
    BUSY("busy"),
    NO_ANSWER("no_answer");

    companion object {
        fun fromWire(value: String): ForwardType? = entries.firstOrNull { it.wire == value }
    }
}

/** One call forward (aria-db CallForward). */
data class CallForward(
    val id: String,
    val type: ForwardType,
    val destination: String,
    /** 5..300; used by no-answer forwards. */
    val ringTimeoutSecs: Int,
    val enabled: Boolean,
)

/** One call from `GET /extensions/{id}/cdrs` (aria-api OwnCall). */
data class ServerCall(
    val id: String,
    /** "inbound", "outbound" or "internal". */
    val direction: String,
    val fromUri: String,
    val toUri: String,
    val fromNumber: String?,
    val fromName: String?,
    val toNumber: String?,
    val toName: String?,
    /** "answered", "no_answer", "busy", "failed", "refused", "cancelled", … */
    val disposition: String,
    /** RFC 3339. */
    val startedAt: String,
    val answeredAt: String?,
    val durationSecs: Int,
)

/** The PBX refused a value; [field] names it when the PBX said which. */
class SelfServiceException(message: String, val code: Int, val field: String? = null) : Exception(message)

/** Pure JSON parsing for the self-service endpoints. */
object SelfServiceJson {

    fun features(body: String): ExtensionFeatures {
        val j = JSONObject(body)
        return ExtensionFeatures(
            doNotDisturb = j.optBoolean("do_not_disturb", false),
            maxConcurrentCalls = j.optInt("max_concurrent_calls", 0),
        )
    }

    fun forward(j: JSONObject): CallForward? {
        val type = ForwardType.fromWire(j.optString("forward_type")) ?: return null
        return CallForward(
            id = j.getString("id"),
            type = type,
            destination = j.optString("destination"),
            ringTimeoutSecs = j.optInt("ring_timeout_secs", 20),
            enabled = j.optBoolean("enabled", true),
        )
    }

    fun forwards(body: String): List<CallForward> {
        val a = JSONArray(body)
        return (0 until a.length()).mapNotNull { forward(a.getJSONObject(it)) }
    }

    fun calls(body: String): List<ServerCall> {
        val a = JSONArray(body)
        return (0 until a.length()).map { i ->
            val j = a.getJSONObject(i)
            ServerCall(
                id = j.getString("id"),
                direction = j.optString("direction"),
                fromUri = j.optString("from_uri"),
                toUri = j.optString("to_uri"),
                fromNumber = j.optStringOrNull("from_number"),
                fromName = j.optStringOrNull("from_name"),
                toNumber = j.optStringOrNull("to_number"),
                toName = j.optStringOrNull("to_name"),
                disposition = j.optString("disposition"),
                startedAt = j.optString("started_at"),
                answeredAt = j.optStringOrNull("answered_at"),
                durationSecs = j.optInt("duration_secs", 0),
            )
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}

/**
 * The signed-in user's own PBX settings and call records: Do Not Disturb,
 * call forwarding and server call history. User-scoped endpoints only
 * (own extension, Bearer user JWT). Every method blocks on the network.
 */
class SelfServiceRepository(private val api: PbxApiClient, private val auth: PbxAuth) {

    companion object {
        fun from(context: Context): SelfServiceRepository {
            val auth = StoredPbxAuth(context)
            return SelfServiceRepository(PbxApiClient(auth.apiUrl()), auth)
        }
    }

    fun features(): ExtensionFeatures = call { SelfServiceJson.features(api.features(it, auth.extensionId())) }

    fun setDoNotDisturb(on: Boolean) = call { api.setDoNotDisturb(it, auth.extensionId(), on) }

    fun forwards(): List<CallForward> = call { SelfServiceJson.forwards(api.forwards(it, auth.extensionId())) }

    /**
     * Saves the forward of [type]: updates [existing] when there is one,
     * creates it otherwise. An empty [destination] deletes it.
     */
    fun saveForward(
        type: ForwardType,
        existing: CallForward?,
        destination: String,
        ringTimeoutSecs: Int,
        enabled: Boolean,
    ): CallForward? = call { jwt ->
        val dest = destination.trim()
        when {
            dest.isEmpty() -> {
                existing?.let { api.deleteForward(jwt, it.id) }
                null
            }
            existing != null -> SelfServiceJson.forward(
                JSONObject(api.updateForward(jwt, existing.id, dest, ringTimeoutSecs, enabled)),
            )
            else -> SelfServiceJson.forward(
                JSONObject(
                    api.createForward(jwt, auth.extensionId(), auth.tenantId(), type.wire, dest, ringTimeoutSecs, enabled),
                ),
            )
        }
    }

    fun serverCalls(limit: Int = 100): List<ServerCall> =
        call { SelfServiceJson.calls(api.extensionCalls(it, auth.extensionId(), limit)) }

    /** Runs with auth; a 4xx the PBX explains becomes a [SelfServiceException] with its message. */
    private fun <T> call(block: (String) -> T): T = try {
        auth.withAuth(block)
    } catch (e: PbxApiClient.HttpException) {
        if (e.code in 400..499 && e.code != 401) {
            throw SelfServiceException(e.message ?: "HTTP ${e.code}", e.code, e.field)
        }
        throw e
    }
}
