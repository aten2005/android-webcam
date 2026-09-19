package dev.aten.webcam.auth

class AuditEntry(val timeMillis: Long, val address: String, val event: String, val userAgent: String)

/** Bounded record of recent security-relevant events, shown in the app so the owner can spot strangers. */
class AuditLog(private val capacity: Int = 50, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = ArrayDeque<AuditEntry>()

    @Volatile
    var onChange: (() -> Unit)? = null

    fun record(address: String, event: String, userAgent: String?) {
        synchronized(this) {
            if (entries.size == capacity) entries.removeLast()
            entries.addFirst(AuditEntry(clock(), address, event, sanitize(userAgent)))
        }
        onChange?.invoke()
    }

    @Synchronized
    fun snapshot(): List<AuditEntry> = entries.toList()

    private fun sanitize(userAgent: String?): String =
        userAgent.orEmpty().filter { it in ' '..'~' }.take(120)

    companion object {
        const val LOGIN_OK = "login"
        const val LOGIN_FAILED = "login failed"
        const val VIEWER_CONNECTED = "viewer connected"
        const val VIEWER_DISCONNECTED = "viewer disconnected"
    }
}
