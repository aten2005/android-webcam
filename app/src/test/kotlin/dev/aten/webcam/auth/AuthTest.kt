package dev.aten.webcam.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    private var now = 1_000_000L
    private val clock = { now }

    @Test
    fun generatesDistinctPasswordsFromSafeAlphabet() {
        val password = Passwords.generate()
        assertEquals(16, password.length)
        assertTrue(password.none { it in "0O1lI" })
        assertNotEquals(password, Passwords.generate())
    }

    @Test
    fun matchesPasswordsExactly() {
        assertTrue(Passwords.matches("correct horse", "correct horse"))
        assertFalse(Passwords.matches("correct horse", "correct hors"))
        assertFalse(Passwords.matches("correct horse", ""))
    }

    @Test
    fun sessionsValidateAndRevoke() {
        val store = SessionStore(clock)
        val token = store.create()
        assertTrue(store.isValid(token))
        assertFalse(store.isValid(null))
        assertFalse(store.isValid(""))
        assertFalse(store.isValid(token + "x"))
        assertFalse(store.isValid("a".repeat(500)))
        store.revoke(token)
        assertFalse(store.isValid(token))
    }

    @Test
    fun sessionsExpireWhenIdleOrTooOld() {
        val store = SessionStore(clock)
        val idle = store.create()
        now += SessionStore.IDLE_TIMEOUT_MS + 1
        assertFalse(store.isValid(idle))

        val active = store.create()
        val created = now
        while (now - created <= SessionStore.MAX_AGE_MS - SessionStore.IDLE_TIMEOUT_MS) {
            now += SessionStore.IDLE_TIMEOUT_MS - 1
            assertTrue(store.isValid(active))
        }
        now = created + SessionStore.MAX_AGE_MS + 1
        assertFalse(store.isValid(active))
    }

    @Test
    fun sessionCountIsCappedByEvictingLeastRecentlyUsed() {
        val store = SessionStore(clock)
        val tokens = (1..SessionStore.MAX_SESSIONS).map { now += 10; store.create() }
        now += 10
        assertTrue(store.isValid(tokens[0]))
        now += 10
        val extra = store.create()
        assertTrue(store.isValid(extra))
        assertTrue(store.isValid(tokens[0]))
        assertFalse(store.isValid(tokens[1]))
    }

    @Test
    fun revokeAllInvalidatesEverySession() {
        val store = SessionStore(clock)
        val tokens = listOf(store.create(), store.create())
        store.revokeAll()
        assertTrue(tokens.none(store::isValid))
    }

    @Test
    fun cookieIsHostBoundAndHttpOnly() {
        val cookie = SessionStore.cookieFor("tok")
        assertTrue(cookie.startsWith("__Host-sid=tok; Path=/;"))
        assertTrue(cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"))
    }

    @Test
    fun limiterPacesAttemptsGlobally() {
        val limiter = LoginRateLimiter(clock)
        assertEquals(0, limiter.retryAfterSeconds("a"))
        assertEquals(1, limiter.retryAfterSeconds("b"))
        now += LoginRateLimiter.GLOBAL_INTERVAL_MS
        assertEquals(0, limiter.retryAfterSeconds("b"))
    }

    @Test
    fun limiterBacksOffPerAddressUpToCap() {
        val limiter = LoginRateLimiter(clock)
        val expectedWaits = listOf(1, 2, 4, 8, 16, 30, 30)
        for (expected in expectedWaits) {
            assertEquals(0, limiter.retryAfterSeconds("attacker"))
            limiter.recordFailure("attacker")
            assertEquals(expected, limiter.retryAfterSeconds("attacker"))
            now += expected * 1000L
        }
        now += 1000
        assertEquals(0, limiter.retryAfterSeconds("owner"))
    }

    @Test
    fun limiterForgetsFailuresAfterSuccess() {
        val limiter = LoginRateLimiter(clock)
        repeat(4) { limiter.recordFailure("a") }
        limiter.recordSuccess("a")
        now += LoginRateLimiter.GLOBAL_INTERVAL_MS
        assertEquals(0, limiter.retryAfterSeconds("a"))
    }

    @Test
    fun auditLogKeepsNewestEntriesAndSanitizesUserAgent() {
        val log = AuditLog(capacity = 2, clock = clock)
        var changes = 0
        log.onChange = { changes++ }
        log.record("1.1.1.1", AuditLog.LOGIN_FAILED, "curl\r\nX: injected")
        log.record("2.2.2.2", AuditLog.LOGIN_OK, null)
        log.record("3.3.3.3", AuditLog.VIEWER_CONNECTED, "x".repeat(500))
        val entries = log.snapshot()
        assertEquals(listOf("3.3.3.3", "2.2.2.2"), entries.map { it.address })
        assertEquals(120, entries[0].userAgent.length)
        assertEquals(3, changes)
    }
}
