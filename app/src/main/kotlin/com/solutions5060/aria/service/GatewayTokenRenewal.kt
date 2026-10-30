package com.solutions5060.aria.service

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.aria_mobile.MobileException

/**
 * Fetches a new gateway token from the PBX when the gateway rejects the
 * current one, and hands it to the engine.
 *
 * Several things notice an expired token at about the same time: the core's
 * `on_auth_required` callback and every engine call that throws
 * `AuthenticationError`. They all come through [renew], which fetches one
 * token for all of them: a caller that saw the token fail before someone else
 * replaced it gets the replacement without another request. A failure is
 * shared the same way: callers that were waiting on a fetch that failed, or
 * that arrive within [failureBackoffMs] of it, fail at once instead of each
 * asking the PBX again.
 *
 * When the PBX no longer knows the device (404), the device is registered
 * again after a fresh login, and its new id is used from then on.
 *
 * The network side is behind [Pbx] so this can be tested without a server.
 */
class GatewayTokenRenewal(
    private val pbx: Pbx,
    /** Saves the new token and gives it to the running engine. */
    private val install: (String) -> Unit,
    /** Where progress is logged; replaced in JVM tests, where android.util.Log is a stub. */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    /** How long a failed fetch is reported to later callers before trying again. */
    private val failureBackoffMs: Long = DEFAULT_FAILURE_BACKOFF_MS,
    /** Monotonic milliseconds; replaced in tests. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** The PBX calls renewal needs. Implementations block on the network. */
    interface Pbx {
        /** The saved PBX session JWT, or null when there is none. */
        fun sessionJwt(): String?

        /** The device id from registration, or null when the device never registered. */
        fun deviceId(): String?

        /**
         * `POST /api/v1/devices/{id}/gateway-token`. Throws
         * [PbxApiClient.HttpException] with code 401 when [jwt] has expired.
         */
        fun requestGatewayToken(jwt: String, deviceId: String): String

        /** Logs in to the PBX again, saves the new session JWT and returns it. */
        fun login(): String

        /**
         * Registers this device with the PBX again (`POST /api/v1/devices/register`),
         * saves the new device id and returns it with the gateway token the
         * registration carried, if any.
         */
        fun registerDevice(jwt: String): Registration
    }

    /** A new device registration. [gatewayToken] is null when the PBX sent none. */
    data class Registration(val deviceId: String, val gatewayToken: String?)

    companion object {
        private const val TAG = "GatewayTokenRenewal"
        const val DEFAULT_FAILURE_BACKOFF_MS = 5_000L
    }

    private val mutex = Mutex()

    /** Bumped each time a new token is installed. */
    @Volatile
    var generation: Long = 0L
        private set

    @Volatile
    private var latestToken: String? = null

    /** The generation whose renewal last failed, and when; guarded by [mutex]. */
    private var failedGeneration = -1L
    private var failedAt = 0L

    /**
     * Makes sure a token newer than the one in use at [seenGeneration] is
     * installed. Returns that token, or null when none could be obtained.
     *
     * Read [generation] *before* the operation that failed, and pass it here:
     * if another caller has installed a token since, that token is returned
     * without asking the PBX again.
     */
    suspend fun renew(seenGeneration: Long): String? = mutex.withLock {
        if (generation != seenGeneration) return@withLock latestToken
        if (failedGeneration == generation && clock() - failedAt < failureBackoffMs) {
            // Someone just tried for this same token and failed: share that.
            return@withLock null
        }
        val token = try {
            fetch()
        } catch (e: Exception) {
            failedGeneration = generation
            failedAt = clock()
            log("Gateway token renewal failed: ${e.javaClass.simpleName}: ${e.message}")
            return@withLock null
        }
        install(token)
        latestToken = token
        generation++
        // Never log the token.
        log("Gateway token renewed")
        token
    }

    /** [renew] for a thread that may block (never the main thread). */
    fun renewBlocking(seenGeneration: Long): String? = runBlocking { renew(seenGeneration) }

    /**
     * Runs [call], and if the gateway rejects the token, renews it and runs
     * [call] once more. A second rejection is thrown to the caller.
     */
    fun <T> retryOnAuthFailure(call: () -> T): T {
        val seen = generation
        return try {
            call()
        } catch (e: MobileException.AuthenticationException) {
            renewBlocking(seen) ?: throw e
            call()
        }
    }

    private fun fetch(): String {
        val deviceId = pbx.deviceId()?.takeIf { it.isNotEmpty() }
            // Never registered (or the id was lost): register now.
            ?: return reregister()
        val jwt = pbx.sessionJwt()?.takeIf { it.isNotEmpty() } ?: pbx.login()
        return try {
            pbx.requestGatewayToken(jwt, deviceId)
        } catch (e: PbxApiClient.HttpException) {
            when (e.code) {
                // The PBX session expired too; log in again for a new one.
                401 -> try {
                    pbx.requestGatewayToken(pbx.login(), deviceId)
                } catch (again: PbxApiClient.HttpException) {
                    if (again.code != 404) throw again
                    reregister()
                }
                // The device is gone (unregistered, or removed by an admin).
                404 -> reregister()
                else -> throw e
            }
        }
    }

    /** Fresh login and device registration; a token for the new device. */
    private fun reregister(): String {
        log("Registering the device with the PBX again")
        val jwt = pbx.login()
        val registration = pbx.registerDevice(jwt)
        return registration.gatewayToken?.takeIf { it.isNotEmpty() }
            ?: pbx.requestGatewayToken(jwt, registration.deviceId)
    }
}
