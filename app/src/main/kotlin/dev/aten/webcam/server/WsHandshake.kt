package dev.aten.webcam.server

import java.security.MessageDigest
import java.util.Base64

object WsHandshake {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun isUpgradeRequest(request: HttpRequest): Boolean =
        request.headerHasToken("upgrade", "websocket")

    /** Returns the 101 response, or an error response describing why the upgrade was refused. */
    fun respond(request: HttpRequest): HttpResponse {
        if (request.method != "GET" || !request.headerHasToken("connection", "upgrade")) {
            return HttpResponse.text(400, "bad websocket upgrade", close = true)
        }
        if (request.header("sec-websocket-version") != "13") {
            return HttpResponse(426, headers = listOf("Sec-WebSocket-Version" to "13"), close = true)
        }
        val key = request.header("sec-websocket-key")
        if (key == null || !isValidKey(key)) return HttpResponse.text(400, "bad websocket key", close = true)
        return HttpResponse(
            101,
            headers = listOf("Upgrade" to "websocket", "Sec-WebSocket-Accept" to acceptFor(key)),
        )
    }

    fun acceptFor(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1))
        return Base64.getEncoder().encodeToString(digest)
    }

    /**
     * Browsers always send Origin on WebSocket upgrades and cross-origin POSTs, so requiring it to
     * match Host blocks other sites from riding the viewer's session cookie. Comparing against
     * Host (rather than a configured name) keeps this working behind tunnels and port-forwards.
     */
    fun originMatchesHost(request: HttpRequest): Boolean {
        val origin = request.header("origin") ?: return false
        val host = request.header("host") ?: return false
        val authority = origin.substringAfter("://", missingDelimiterValue = "")
        return authority.isNotEmpty() && authority.equals(host, ignoreCase = true)
    }

    private fun isValidKey(key: String): Boolean = try {
        Base64.getDecoder().decode(key).size == 16
    } catch (_: IllegalArgumentException) {
        false
    }
}
