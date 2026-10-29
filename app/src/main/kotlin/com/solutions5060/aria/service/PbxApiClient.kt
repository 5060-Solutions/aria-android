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

    /** A non-2xx response. [code] lets callers tell an expired token (401) apart. */
    class HttpException(val code: Int, message: String) : Exception(message)

    data class LoginResult(
        val jwt: String,
        val extensionId: String,
        val tenantId: String,
    )

    data class DeviceRegisterResult(
        val deviceId: String,
        val sipDomain: String,
        val gatewayUrl: String,
        val gatewayToken: String,
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
            throw Exception("Login failed (${response.code}): $errorMsg")
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
            val errorMsg = try {
                JSONObject(responseBody).optString("error", responseBody)
            } catch (_: Exception) {
                responseBody
            }
            throw Exception("Device registration failed (${response.code}): $errorMsg")
        }

        val json = JSONObject(responseBody)
        return DeviceRegisterResult(
            deviceId = json.getString("device_id"),
            sipDomain = json.optString("sip_domain", ""),
            gatewayUrl = json.optString("gateway_url", ""),
            gatewayToken = json.optString("gateway_token", ""),
        )
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

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun send(jwt: String, method: String, path: String, body: okhttp3.RequestBody? = null): String {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $jwt")
            .method(method, body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optString("error", "")
                } catch (_: Exception) {
                    ""
                }.ifEmpty { "HTTP ${response.code}" }
                throw HttpException(response.code, errorMsg)
            }
            return responseBody
        }
    }
}
