package dev.aten.webcam.data

import dev.aten.webcam.auth.AuditEntry
import kotlinx.coroutines.flow.MutableStateFlow

data class ServiceStatus(
    val running: Boolean = false,
    val streaming: Boolean = false,
    val viewers: List<String> = emptyList(),
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
    val fingerprint: String = "",
    val error: String? = null,
)

/** Process-wide state the service publishes and the UI observes. */
object AppState {
    val status = MutableStateFlow(ServiceStatus())
    val audit = MutableStateFlow<List<AuditEntry>>(emptyList())
}
