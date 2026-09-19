package dev.aten.webcam.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** In-memory login sessions. Only token hashes are kept, so a memory dump cannot be replayed. */
class SessionStore(private val clock: () -> Long = System::currentTimeMillis) {
    private class Session(val createdAt: Long, var lastUsedAt: Long)

    private val sessions = HashMap<String, Session>()
    private val random = SecureRandom()

    @Synchronized
    fun create(): String {
        val now = clock()
        prune(now)
        if (sessions.size >= MAX_SESSIONS) {
            sessions.entries.minByOrNull { it.value.lastUsedAt }?.let { sessions.remove(it.key) }
        }
        val token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
        sessions[hash(token)] = Session(now, now)
        return token
    }

    @Synchronized
    fun isValid(token: String?): Boolean {
        if (token.isNullOrEmpty() || token.length > 128) return false
        val now = clock()
        prune(now)
        val session = sessions[hash(token)] ?: return false
        session.lastUsedAt = now
        return true
    }

    @Synchronized
    fun revoke(token: String?) {
        if (!token.isNullOrEmpty()) sessions.remove(hash(token))
    }

    @Synchronized
    fun revokeAll() = sessions.clear()

    private fun prune(now: Long) {
        sessions.values.removeIf { now - it.lastUsedAt > IDLE_TIMEOUT_MS || now - it.createdAt > MAX_AGE_MS }
    }

    private fun hash(token: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    companion object {
        const val COOKIE_NAME = "__Host-sid"
        const val MAX_SESSIONS = 8
        const val IDLE_TIMEOUT_MS = 12L * 60 * 60 * 1000
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        fun cookieFor(token: String) =
            "$COOKIE_NAME=$token; Path=/; Max-Age=${MAX_AGE_MS / 1000}; Secure; HttpOnly; SameSite=Strict"

        const val CLEARING_COOKIE = "$COOKIE_NAME=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict"
    }
}
