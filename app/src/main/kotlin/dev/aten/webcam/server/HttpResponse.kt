package dev.aten.webcam.server

import java.io.OutputStream

class HttpResponse(
    val status: Int,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
    val headers: List<Pair<String, String>> = emptyList(),
    val close: Boolean = false,
) {
    /** [host] is the request's Host header, needed because older Safari does not map 'self' to wss. */
    fun write(out: OutputStream, host: String?, omitBody: Boolean = false) {
        val head = StringBuilder(512)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        val switchingProtocols = status == 101
        if (switchingProtocols) {
            head.append("Connection: Upgrade\r\n")
        } else {
            contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
            head.append("Content-Length: ").append(body.size).append("\r\n")
            head.append("Connection: ").append(if (close) "close" else "keep-alive").append("\r\n")
        }
        for ((name, value) in (if (switchingProtocols) emptyList() else securityHeaders(host)) + headers) {
            require(value.none { it == '\r' || it == '\n' }) { "header value contains a line break" }
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (!omitBody) out.write(body)
        out.flush()
    }

    companion object {
        fun text(status: Int, message: String, close: Boolean = false) =
            HttpResponse(status, "text/plain; charset=utf-8", message.toByteArray(), close = close)

        fun redirect(location: String) = HttpResponse(303, headers = listOf("Location" to location))

        private val HOST_PATTERN = Regex("^[A-Za-z0-9.\\-\\[\\]:]+$")

        private fun securityHeaders(host: String?): List<Pair<String, String>> {
            val wsSource = host?.takeIf { HOST_PATTERN.matches(it) }?.let { " wss://$it" } ?: ""
            return listOf(
                "Content-Security-Policy" to
                    "default-src 'self'; connect-src 'self'$wsSource; frame-ancestors 'none'; base-uri 'none'",
                "X-Content-Type-Options" to "nosniff",
                // "no-referrer" would make browsers send "Origin: null" on same-origin POSTs, defeating the Origin check.
                "Referrer-Policy" to "same-origin",
                "Permissions-Policy" to "camera=(), microphone=(self)",
                "Cache-Control" to "no-store",
            )
        }

        private fun reason(status: Int) = when (status) {
            101 -> "Switching Protocols"
            200 -> "OK"
            303 -> "See Other"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Content Too Large"
            426 -> "Upgrade Required"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "Error"
        }
    }
}
