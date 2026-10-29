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
 * replaced it gets the replacement without another request.
 *
 * The network side is behind [Pbx] so this can be tested without a server.
 */
class GatewayTokenRenewal(
    private val pbx: Pbx,
    /** Saves the new token and gives it to the running engine. */
    private val install: (String) -> Unit,
    /** Where progress is logged; replaced in JVM tests, where android.util.Log is a stub. */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
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
    }

    companion object {
        private const val TAG = "GatewayTokenRenewal"
    }

    private val mutex = Mutex()

    /** Bumped each time a new token is installed. */
    @Volatile
    var generation: Long = 0L
        private set

    @Volatile
    private var latestToken: String? = null

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
        val token = try {
            fetch()
        } catch (e: Exception) {
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
            ?: throw IllegalStateException("No registered device to renew a token for")
        val jwt = pbx.sessionJwt()?.takeIf { it.isNotEmpty() } ?: pbx.login()
        return try {
            pbx.requestGatewayToken(jwt, deviceId)
        } catch (e: PbxApiClient.HttpException) {
            if (e.code != 401) throw e
            // The PBX session expired too; log in again for a new one.
            pbx.requestGatewayToken(pbx.login(), deviceId)
        }
    }
}
