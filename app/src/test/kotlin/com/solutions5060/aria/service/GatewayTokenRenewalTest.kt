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
        var deviceId: String? = "device-1",
        /** Device ids the PBX still knows; others get 404. */
        val knownDevices: MutableSet<String> = mutableSetOf("device-1"),
        var failWith: Exception? = null,
        val delayMs: Long = 0,
        /** Token a new registration carries, or null for none. */
        val registrationToken: String? = null,
    ) : GatewayTokenRenewal.Pbx {
        val tokenRequests = AtomicInteger()
        val logins = AtomicInteger()
        val registrations = AtomicInteger()
        val requestedFor = mutableListOf<String>()

        override fun sessionJwt(): String? = jwt
        override fun deviceId(): String? = deviceId

        override fun requestGatewayToken(jwt: String, deviceId: String): String {
            val n = tokenRequests.incrementAndGet()
            synchronized(requestedFor) { requestedFor.add(deviceId) }
            if (delayMs > 0) Thread.sleep(delayMs)
            failWith?.let { throw it }
            if (jwt != validJwt) throw PbxApiClient.HttpException(401, "expired")
            if (deviceId !in knownDevices) throw PbxApiClient.HttpException(404, "No such device.")
            return "gateway-$n"
        }

        override fun login(): String {
            logins.incrementAndGet()
            jwt = validJwt
            return validJwt
        }

        override fun registerDevice(jwt: String): GatewayTokenRenewal.Registration {
            val n = registrations.incrementAndGet()
            val id = "device-new-$n"
            knownDevices.add(id)
            deviceId = id
            return GatewayTokenRenewal.Registration(id, registrationToken)
        }
    }

    private class FakeClock(var now: Long = 1_000_000L) {
        fun read() = now
    }

    private class Installed {
        val tokens = mutableListOf<String>()
        fun install(token: String) = synchronized(tokens) { tokens.add(token) }
    }

    private fun renewal(pbx: FakePbx, installed: Installed = Installed(), clock: FakeClock = FakeClock()) =
        GatewayTokenRenewal(pbx, installed::install, log = {}, failureBackoffMs = 5_000, clock = clock::read)

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
    fun noRegisteredDeviceRegistersOne() {
        val pbx = FakePbx(deviceId = null, registrationToken = "from-registration")
        assertEquals("from-registration", runBlocking { renewal(pbx).renew(0) })
        assertEquals(1, pbx.registrations.get())
        assertEquals(0, pbx.tokenRequests.get())
    }

    @Test
    fun aGoneDeviceIsRegisteredAgainAfterAFreshLogin() {
        val pbx = FakePbx(knownDevices = mutableSetOf())
        val installed = Installed()

        val token = runBlocking { renewal(pbx, installed).renew(0) }

        assertEquals(1, pbx.logins.get())
        assertEquals(1, pbx.registrations.get())
        assertEquals("device-new-1", pbx.deviceId)
        // Asked for the old device (404), then for the new one.
        assertEquals(listOf("device-1", "device-new-1"), pbx.requestedFor)
        assertEquals("gateway-2", token)
        assertEquals(listOf("gateway-2"), installed.tokens)
    }

    @Test
    fun aRegistrationThatCarriesATokenNeedsNoFurtherRequest() {
        val pbx = FakePbx(knownDevices = mutableSetOf(), registrationToken = "from-registration")
        assertEquals("from-registration", runBlocking { renewal(pbx).renew(0) })
        assertEquals(1, pbx.tokenRequests.get())
    }

    @Test
    fun anExpiredSessionAndAGoneDeviceTogetherStillRecover() {
        val pbx = FakePbx(jwt = "session-old", validJwt = "session-new", knownDevices = mutableSetOf())
        val token = runBlocking { renewal(pbx).renew(0) }
        assertEquals(1, pbx.registrations.get())
        assertEquals("gateway-3", token)
    }

    @Test
    fun waitersShareOneFailedFetch() {
        val pbx = FakePbx(failWith = PbxApiClient.HttpException(502, "gateway down"), delayMs = 100)
        val renewal = renewal(pbx)
        val seen = renewal.generation

        val results = runBlocking(Dispatchers.Default) {
            (1..8).map { async { renewal.renew(seen) } }.awaitAll()
        }

        assertEquals(1, pbx.tokenRequests.get())
        results.forEach { assertNull(it) }
    }

    @Test
    fun aFailureIsRetriedOnlyAfterTheBackoff() {
        val pbx = FakePbx(failWith = PbxApiClient.HttpException(502, "gateway down"))
        val clock = FakeClock()
        val renewal = renewal(pbx, clock = clock)

        assertNull(runBlocking { renewal.renew(0) })
        clock.now += 4_999
        assertNull(runBlocking { renewal.renew(0) })
        assertEquals(1, pbx.tokenRequests.get())

        clock.now += 1
        pbx.failWith = null
        assertEquals("gateway-2", runBlocking { renewal.renew(0) })
        assertEquals(2, pbx.tokenRequests.get())
    }
}
