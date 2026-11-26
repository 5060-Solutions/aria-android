package com.solutions5060.aria.service

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the Aria Cloud PBX REST API.
 *
 * Handles extension login + device registration, replacing the old
 * direct-to-gateway auth flow.
 */
class PbxApiClient(private val baseUrl: String) {

    companion object {
        private const val TAG = "PbxApiClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * A non-2xx response. [code] lets callers tell an expired token (401) apart.
     * [deviceId] is set when a failed registration still stored the device, so
     * a gateway token can be renewed for it.
     */
    class HttpException(
        val code: Int,
        message: String,
        val deviceId: String? = null,
        /** The request field the PBX refused (`"field"` in its error body), if it named one. */
        val field: String? = null,
    ) : Exception(message)

    data class LoginResult(
        val jwt: String,
        val extensionId: String,
        val tenantId: String,
    )

    data class DeviceRegisterResult(
        val deviceId: String,
        val sipDomain: String,
        val gatewayUrl: String,
        /** Empty when the PBX sent none; renew one with [renewGatewayToken]. */
        val gatewayToken: String,
        /** `udp`, `tcp` or `tls`, or null when the PBX does not say. */
        val sipTransport: String?,
        /** The port [sipTransport] belongs to, or null when the PBX does not say. */
        val sipPort: Int?,
    )

    /**
     * POST /api/v1/auth/extension-login
     */
    fun extensionLogin(
        extensionNumber: String,
        password: String,
        tenantDomain: String,
    ): LoginResult {
        val body = JSONObject().apply {
            put("extension_number", extensionNumber)
            put("password", password)
            put("tenant_domain", tenantDomain)
        }

        val request = Request.Builder()
            .url("$baseUrl/api/v1/auth/extension-login")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw Exception("Empty response from server")

        if (!response.isSuccessful) {
            val errorMsg = try {
                JSONObject(responseBody).optString("error", responseBody)
            } catch (_: Exception) {
                responseBody
            }
            throw HttpException(response.code, "Login failed (${response.code}): $errorMsg")
        }

        val json = JSONObject(responseBody)
        return LoginResult(
            jwt = json.getString("token"),
            extensionId = json.getString("extension_id"),
            tenantId = json.getString("tenant_id"),
        )
    }

    /**
     * POST /api/v1/devices/register
     */
    fun registerDevice(
        jwt: String,
        pushToken: String,
        platform: String,
    ): DeviceRegisterResult {
        val body = JSONObject().apply {
            put("push_token", pushToken)
            put("platform", platform)
        }

        val request = Request.Builder()
            .url("$baseUrl/api/v1/devices/register")
            .addHeader("Authorization", "Bearer $jwt")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw Exception("Empty response from server")

        if (!response.isSuccessful) {
            val error = try {
                JSONObject(responseBody)
            } catch (_: Exception) {
                null
            }
            val errorMsg = error?.optString("error", "")?.ifEmpty { null } ?: responseBody
            throw HttpException(
                response.code,
                "Device registration failed (${response.code}): $errorMsg",
                deviceId = error?.optString("device_id", "")?.ifEmpty { null },
            )
        }

        val json = JSONObject(responseBody)
        return DeviceRegisterResult(
            deviceId = json.getString("device_id"),
            sipDomain = json.optString("sip_domain", ""),
            gatewayUrl = json.optString("gateway_url", ""),
            gatewayToken = json.optString("gateway_token", ""),
            sipTransport = json.optString("sip_transport", "").lowercase().ifEmpty { null },
            sipPort = json.optInt("sip_port", 0).takeIf { it in 1..65535 },
        )
    }

    /**
     * POST /api/v1/devices/{id}/gateway-token — a fresh gateway token for this
     * device, for when the one from registration has expired or was never issued.
     */
    fun renewGatewayToken(jwt: String, deviceId: String): String {
        val body = send(jwt, "POST", "/api/v1/devices/${encode(deviceId)}/gateway-token", ByteArray(0).toRequestBody(null))
        val token = JSONObject(body).optString("gateway_token", "")
        if (token.isEmpty()) throw Exception("The PBX returned no gateway token")
        return token
    }

    /**
     * PUT /api/v1/devices/{id}/push-token — replaces the push token the PBX
     * holds for this device. 409 means another device already has the token.
     */
    fun updatePushToken(jwt: String, deviceId: String, pushToken: String) {
        val body = JSONObject().apply { put("push_token", pushToken) }
        send(jwt, "PUT", "/api/v1/devices/${encode(deviceId)}/push-token", body.toString().toRequestBody(JSON_MEDIA))
    }

    /**
     * POST /api/v1/devices/unregister
     */
    fun unregisterDevice(jwt: String, deviceId: String) {
        val body = JSONObject().apply {
            put("device_id", deviceId)
        }

        val request = Request.Builder()
            .url("$baseUrl/api/v1/devices/unregister")
            .addHeader("Authorization", "Bearer $jwt")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Unregister returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister device: $e")
        }
    }

    // ── Self-service (voicemail) ────────────────────────────────────────

    /** GET /api/v1/me — who this credential is. */
    fun me(jwt: String): String = send(jwt, "GET", "/api/v1/me")

    /** GET /api/v1/tenants/{tenant_id}/calling/settings — the company's calling country among them. */
    fun callingSettings(jwt: String, tenantId: String): String =
        send(jwt, "GET", "/api/v1/tenants/${encode(tenantId)}/calling/settings")

    /** GET /api/v1/extensions/{ext_id}/voicemail — the extension's box (without messages). */
    fun voicemailBox(jwt: String, extensionId: String): String =
        send(jwt, "GET", "/api/v1/extensions/${encode(extensionId)}/voicemail")

    /** GET /api/v1/voicemail/boxes/{box_id}/messages — newest first. */
    fun voicemailMessages(jwt: String, boxId: String, limit: Int = 100): String =
        send(jwt, "GET", "/api/v1/voicemail/boxes/${encode(boxId)}/messages?limit=$limit")

    /** PATCH /api/v1/voicemail/messages/{id}/read */
    fun markVoicemailRead(jwt: String, messageId: String) {
        send(jwt, "PATCH", "/api/v1/voicemail/messages/${encode(messageId)}/read", ByteArray(0).toRequestBody(null))
    }

    /** DELETE /api/v1/voicemail/messages/{id} */
    fun deleteVoicemail(jwt: String, messageId: String) {
        send(jwt, "DELETE", "/api/v1/voicemail/messages/${encode(messageId)}")
    }

    /**
     * POST /api/v1/voicemail/messages/{id}/audio-grant — a short-lived token
     * that fetches this one message's audio and nothing else, for a player
     * that cannot send an Authorization header.
     */
    fun voicemailAudioGrant(jwt: String, messageId: String): String =
        send(jwt, "POST", "/api/v1/voicemail/messages/${encode(messageId)}/audio-grant", ByteArray(0).toRequestBody(null))

    /** GET /api/v1/voicemail/messages/{id}/audio?token= — streamable with the grant alone. */
    fun voicemailAudioUrl(messageId: String, grantToken: String): String =
        "$baseUrl/api/v1/voicemail/messages/${encode(messageId)}/audio?token=${encode(grantToken)}"

    // ── Self-service (features, forwards, call history) ─────────────────

    /** GET /api/v1/extensions/{ext_id}/features — DND and limits (created with defaults on first read). */
    fun features(jwt: String, extensionId: String): String =
        send(jwt, "GET", "/api/v1/extensions/${encode(extensionId)}/features")

    /** PUT /api/v1/extensions/{ext_id}/features `{"do_not_disturb": on}` — 204. Only DND is the user's to change. */
    fun setDoNotDisturb(jwt: String, extensionId: String, on: Boolean) {
        val body = JSONObject().put("do_not_disturb", on)
        send(jwt, "PUT", "/api/v1/extensions/${encode(extensionId)}/features", body.toString().toRequestBody(JSON_MEDIA))
    }

    /** GET /api/v1/extensions/{ext_id}/forwards — at most one per forward type. */
    fun forwards(jwt: String, extensionId: String): String =
        send(jwt, "GET", "/api/v1/extensions/${encode(extensionId)}/forwards")

    /**
     * POST /api/v1/extensions/{ext_id}/forwards — 201 with the forward. 409
     * when one of [forwardType] exists; 422 names a bad field.
     */
    fun createForward(
        jwt: String,
        extensionId: String,
        tenantId: String?,
        forwardType: String,
        destination: String,
        ringTimeoutSecs: Int,
        enabled: Boolean,
    ): String {
        val body = JSONObject()
            .put("forward_type", forwardType)
            .put("destination", destination)
            .put("ring_timeout_secs", ringTimeoutSecs)
            .put("enabled", enabled)
        // Ignored by current PBX releases (the tenant is the extension's), required by older ones.
        if (!tenantId.isNullOrEmpty()) body.put("tenant_id", tenantId)
        return send(jwt, "POST", "/api/v1/extensions/${encode(extensionId)}/forwards", body.toString().toRequestBody(JSON_MEDIA))
    }

    /** PUT /api/v1/forwards/{id} — partial update; returns the forward. */
    fun updateForward(jwt: String, forwardId: String, destination: String, ringTimeoutSecs: Int, enabled: Boolean): String {
        val body = JSONObject()
            .put("destination", destination)
            .put("ring_timeout_secs", ringTimeoutSecs)
            .put("enabled", enabled)
        return send(jwt, "PUT", "/api/v1/forwards/${encode(forwardId)}", body.toString().toRequestBody(JSON_MEDIA))
    }

    /** DELETE /api/v1/forwards/{id} */
    fun deleteForward(jwt: String, forwardId: String) {
        send(jwt, "DELETE", "/api/v1/forwards/${encode(forwardId)}")
    }

    /** GET /api/v1/extensions/{ext_id}/cdrs?limit= — this extension's calls, newest first (max 200). */
    fun extensionCalls(jwt: String, extensionId: String, limit: Int = 100): String =
        send(jwt, "GET", "/api/v1/extensions/${encode(extensionId)}/cdrs?limit=${limit.coerceIn(1, 200)}")

    // ── Messaging (SMS/MMS inboxes) ─────────────────────────────────────

    /** GET .../messaging/inboxes — the numbers this seat may read and reply on. */
    fun messagingInboxes(jwt: String, tenantId: String): String =
        send(jwt, "GET", "/api/v1/tenants/${encode(tenantId)}/messaging/inboxes")

    /** GET .../inboxes/{did}/conversations — most recent first. */
    fun messagingConversations(jwt: String, tenantId: String, didId: String, limit: Int = 100): String =
        send(jwt, "GET", "${messagingPath(tenantId, didId)}/conversations?limit=${limit.coerceIn(1, 500)}")

    /** GET .../inboxes/{did}/conversations/{number} — newest first. */
    fun messagingThread(jwt: String, tenantId: String, didId: String, number: String, limit: Int = 100): String =
        send(jwt, "GET", "${messagingPath(tenantId, didId)}/conversations/${encode(number)}?limit=${limit.coerceIn(1, 500)}")

    /**
     * POST .../inboxes/{did}/messages — 201 with the message, 200 for a retry
     * of [idempotencyKey]. 422 names `to` or `body`; 502 is a carrier refusal
     * (the message stays in the thread as failed).
     */
    fun sendMessage(
        jwt: String,
        tenantId: String,
        didId: String,
        to: String,
        body: String,
        attachmentIds: List<String>,
        idempotencyKey: String,
    ): String {
        val json = JSONObject().put("to", to).put("body", body)
        if (attachmentIds.isNotEmpty()) json.put("attachment_ids", org.json.JSONArray(attachmentIds))
        return send(
            jwt,
            "POST",
            "${messagingPath(tenantId, didId)}/messages",
            json.toString().toRequestBody(JSON_MEDIA),
            headers = mapOf("Idempotency-Key" to idempotencyKey),
        )
    }

    /** POST .../messaging/attachments (multipart `file`) — an unsent upload to name in `attachment_ids`. */
    fun uploadMessageAttachment(jwt: String, tenantId: String, bytes: ByteArray, contentType: String, fileName: String): String {
        val form = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(contentType.toMediaType()))
            .build()
        return send(jwt, "POST", "/api/v1/tenants/${encode(tenantId)}/messaging/attachments", form)
    }

    /** GET .../messaging/attachments/{id} — the file's bytes. */
    fun messageAttachmentBytes(jwt: String, tenantId: String, attachmentId: String): ByteArray {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/tenants/${encode(tenantId)}/messaging/attachments/${encode(attachmentId)}")
            .addHeader("Authorization", "Bearer $jwt")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpException(response.code, response.body?.string().orEmpty())
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    /** The event socket's URL (`wss://…/api/v1/ws`); the session goes in the Authorization header. */
    fun eventSocketRequest(jwt: String): Request {
        val http = baseUrl.trimEnd('/')
        val ws = when {
            http.startsWith("https://") -> "wss://" + http.removePrefix("https://")
            http.startsWith("http://") -> "ws://" + http.removePrefix("http://")
            else -> http
        }
        return Request.Builder().url("$ws/api/v1/ws").addHeader("Authorization", "Bearer $jwt").build()
    }

    /** The shared client, for the event socket (no read timeout, pings to keep it open). */
    fun socketClient(): OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private fun messagingPath(tenantId: String, didId: String) =
        "/api/v1/tenants/${encode(tenantId)}/messaging/inboxes/${encode(didId)}"

    private fun httpException(code: Int, responseBody: String): HttpException {
        val error = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            null
        }
        val errorMsg = error?.optString("error", "")?.ifEmpty { null } ?: "HTTP $code"
        return HttpException(code, errorMsg, field = error?.optString("field", "")?.ifEmpty { null })
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun send(
        jwt: String,
        method: String,
        path: String,
        body: okhttp3.RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $jwt")
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .method(method, body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw httpException(response.code, responseBody)
            return responseBody
        }
    }
}
