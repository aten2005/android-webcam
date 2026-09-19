package dev.aten.webcam.auth

/**
 * Throttles password guesses without ever locking the owner out: behind a tunnel every client
 * shares one address, so a hard lockout would let an attacker deny access to the owner. Instead
 * attempts are globally paced and each address backs off up to a short cap.
 */
class LoginRateLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    private class Backoff(var failures: Int, var blockedUntil: Long)

    private val backoffs = HashMap<String, Backoff>()
    private var nextGlobalAttemptAt = 0L

    /** Returns 0 when the attempt may proceed, otherwise the number of seconds to wait. */
    @Synchronized
    fun retryAfterSeconds(address: String): Int {
        val now = clock()
        val blockedUntil = maxOf(nextGlobalAttemptAt, backoffs[address]?.blockedUntil ?: 0L)
        if (blockedUntil > now) return ((blockedUntil - now + 999) / 1000).toInt()
        nextGlobalAttemptAt = now + GLOBAL_INTERVAL_MS
        return 0
    }

    @Synchronized
    fun recordFailure(address: String) {
        val now = clock()
        if (backoffs.size >= MAX_TRACKED) backoffs.values.removeIf { it.blockedUntil <= now }
        if (backoffs.size >= MAX_TRACKED) backoffs.clear()
        val backoff = backoffs.getOrPut(address) { Backoff(0, 0L) }
        backoff.failures++
        val delay = (1000L shl (backoff.failures - 1).coerceAtMost(10)).coerceAtMost(MAX_BACKOFF_MS)
        backoff.blockedUntil = now + delay
    }

    @Synchronized
    fun recordSuccess(address: String) {
        backoffs.remove(address)
    }

    companion object {
        const val GLOBAL_INTERVAL_MS = 1000L
        const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_TRACKED = 256
    }
}
