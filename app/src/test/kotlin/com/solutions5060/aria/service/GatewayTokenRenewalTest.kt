package com.solutions5060.aria.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import uniffi.aria_mobile.MobileException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GatewayTokenRenewalTest {

    /** A PBX whose session JWT is [jwt] and which accepts only [validJwt]. */
    private class FakePbx(
        var jwt: String? = "session-1",
        var validJwt: String = "session-1",
        val deviceId: String? = "device-1",
        val failWith: Exception? = null,
        val delayMs: Long = 0,
    ) : GatewayTokenRenewal.Pbx {
        val tokenRequests = AtomicInteger()
        val logins = AtomicInteger()

        override fun sessionJwt(): String? = jwt
        override fun deviceId(): String? = deviceId

        override fun requestGatewayToken(jwt: String, deviceId: String): String {
            val n = tokenRequests.incrementAndGet()
            if (delayMs > 0) Thread.sleep(delayMs)
            failWith?.let { throw it }
            if (jwt != validJwt) throw PbxApiClient.HttpException(401, "expired")
            return "gateway-$n"
        }

        override fun login(): String {
            logins.incrementAndGet()
            jwt = validJwt
            return validJwt
        }
    }

    private class Installed {
        val tokens = mutableListOf<String>()
        fun install(token: String) = synchronized(tokens) { tokens.add(token) }
    }

    private fun renewal(pbx: FakePbx, installed: Installed = Installed()) =
        GatewayTokenRenewal(pbx, installed::install, log = {})

    @Test
    fun concurrentTriggersRenewOnce() {
        val pbx = FakePbx(delayMs = 100)
        val installed = Installed()
        val renewal = renewal(pbx, installed)
        val seen = renewal.generation

        val results = runBlocking(Dispatchers.Default) {
            (1..8).map { async { renewal.renew(seen) } }.awaitAll()
        }

        assertEquals(1, pbx.tokenRequests.get())
        assertEquals(listOf("gateway-1"), installed.tokens)
        results.forEach { assertEquals("gateway-1", it) }
        assertEquals(seen + 1, renewal.generation)
    }

    @Test
    fun concurrentFailedCallsRenewOnceAndEachRetries() {
        val pbx = FakePbx(delayMs = 50)
        val renewal = renewal(pbx)
        val callers = 6
        // Every caller fails with the old token before any renewal finishes.
        val allFailed = CountDownLatch(callers)
        val attempts = AtomicInteger()

        val threads = (1..callers).map {
            Thread {
                var first = true
                renewal.retryOnAuthFailure {
                    attempts.incrementAndGet()
                    if (first) {
                        first = false
                        allFailed.countDown()
                        allFailed.await(5, TimeUnit.SECONDS)
                        throw MobileException.AuthenticationException("401")
                    }
                }
            }.apply { start() }
        }
        threads.forEach { it.join(10_000) }

        assertEquals(1, pbx.tokenRequests.get())
        assertEquals(callers * 2, attempts.get())
    }

    @Test
    fun expiredSessionLogsInAgainThenRenews() {
        val pbx = FakePbx(jwt = "session-old", validJwt = "session-new")
        val renewal = renewal(pbx)

        val token = runBlocking { renewal.renew(renewal.generation) }

        assertEquals(1, pbx.logins.get())
        assertEquals(2, pbx.tokenRequests.get())
        assertEquals("gateway-2", token)
    }

    @Test
    fun missingSessionLogsInFirst() {
        val pbx = FakePbx(jwt = null)
        val token = runBlocking { renewal(pbx).renew(0) }

        assertEquals(1, pbx.logins.get())
        assertEquals("gateway-1", token)
    }

    @Test
    fun aRenewalAfterTheFailureIsReusedNotRepeated() {
        val pbx = FakePbx()
        val renewal = renewal(pbx)
        val seen = renewal.generation
        runBlocking { renewal.renew(seen) }

        // A second caller that saw the same, now replaced, token.
        val again = runBlocking { renewal.renew(seen) }

        assertEquals("gateway-1", again)
        assertEquals(1, pbx.tokenRequests.get())
    }

    @Test
    fun retriesTheCallOnceAfterRenewing() {
        val pbx = FakePbx()
        val installed = Installed()
        val renewal = renewal(pbx, installed)
        var calls = 0

        val result = renewal.retryOnAuthFailure {
            calls++
            if (calls == 1) throw MobileException.AuthenticationException("401")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(listOf("gateway-1"), installed.tokens)
    }

    @Test
    fun aSecondRejectionIsThrownWithoutAThirdAttempt() {
        val pbx = FakePbx()
        val renewal = renewal(pbx)
        var calls = 0

        assertThrows(MobileException.AuthenticationException::class.java) {
            renewal.retryOnAuthFailure<Unit> {
                calls++
                throw MobileException.AuthenticationException("401")
            }
        }
        assertEquals(2, calls)
        assertEquals(1, pbx.tokenRequests.get())
    }

    @Test
    fun aFailedRenewalRethrowsTheOriginalErrorWithoutRetrying() {
        val pbx = FakePbx(failWith = PbxApiClient.HttpException(502, "gateway down"))
        val installed = Installed()
        val renewal = renewal(pbx, installed)
        val original = MobileException.AuthenticationException("401")
        var calls = 0

        val thrown = assertThrows(MobileException.AuthenticationException::class.java) {
            renewal.retryOnAuthFailure<Unit> {
                calls++
                throw original
            }
        }
        assertSame(original, thrown)
        assertEquals(1, calls)
        assertEquals(emptyList<String>(), installed.tokens)
        assertEquals(0L, renewal.generation)
    }

    @Test
    fun otherErrorsAreNotRetried() {
        val pbx = FakePbx()
        val renewal = renewal(pbx)
        var calls = 0

        assertThrows(MobileException.CallFailed::class.java) {
            renewal.retryOnAuthFailure<Unit> {
                calls++
                throw MobileException.CallFailed("busy")
            }
        }
        assertEquals(1, calls)
        assertEquals(0, pbx.tokenRequests.get())
    }

    @Test
    fun noRegisteredDeviceMeansNoToken() {
        val pbx = FakePbx(deviceId = null)
        assertNull(runBlocking { renewal(pbx).renew(0) })
        assertEquals(0, pbx.tokenRequests.get())
    }
}
