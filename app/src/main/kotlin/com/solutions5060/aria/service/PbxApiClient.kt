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
}
