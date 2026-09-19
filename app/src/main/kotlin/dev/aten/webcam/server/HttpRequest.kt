package dev.aten.webcam.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class HttpException(val status: Int, message: String) : IOException(message)

class HttpRequest(
    val method: String,
    val path: String,
    val query: String,
    private val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun cookie(name: String): String? =
        header("cookie")
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substring(name.length + 1)

    /** The raw (not percent-decoded) value of the first [name] parameter in the query string. */
    fun queryParam(name: String): String? =
        query.split('&').firstOrNull { it.startsWith("$name=") }?.substring(name.length + 1)

    fun headerHasToken(name: String, token: String): Boolean =
        header(name)?.split(',')?.any { it.trim().equals(token, ignoreCase = true) } == true

    companion object {
        const val MAX_HEAD_BYTES = 8 * 1024
        const val MAX_HEADERS = 64
        const val MAX_BODY_BYTES = 4 * 1024
        private val METHODS = setOf("GET", "HEAD", "POST")
        private val SINGLETON_HEADERS = setOf("host", "content-length", "origin", "sec-websocket-key")

        /** Returns null when the peer closed the connection before sending anything. */
        fun read(input: InputStream): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val requestLine = lines[0].split(' ')
            if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
                throw HttpException(400, "malformed request line")
            }
            val method = requestLine[0]
            if (method !in METHODS) throw HttpException(405, "method not allowed")
            val target = requestLine[1]
            if (!target.startsWith("/")) throw HttpException(400, "unsupported request target")

            val headers = parseHeaders(lines.drop(1))
            if (headers.containsKey("transfer-encoding")) throw HttpException(501, "transfer-encoding unsupported")
            val body = readBody(input, headers["content-length"])

            val queryStart = target.indexOf('?')
            return HttpRequest(
                method = method,
                path = if (queryStart < 0) target else target.substring(0, queryStart),
                query = if (queryStart < 0) "" else target.substring(queryStart + 1),
                headers = headers,
                body = body,
            )
        }

        private fun readHead(input: InputStream): String? {
            val buffer = ByteArrayOutputStream(512)
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) {
                    if (buffer.size() == 0) return null
                    throw HttpException(400, "truncated request")
                }
                buffer.write(b)
                if (buffer.size() > MAX_HEAD_BYTES) throw HttpException(431, "request head too large")
                val expected = if (matched % 2 == 0) '\r'.code else '\n'.code
                matched = when (b) {
                    expected -> matched + 1
                    '\r'.code -> 1
                    else -> 0
                }
            }
            return String(buffer.toByteArray(), 0, buffer.size() - 4, Charsets.ISO_8859_1)
        }

        private fun parseHeaders(lines: List<String>): Map<String, String> {
            if (lines.size > MAX_HEADERS) throw HttpException(431, "too many headers")
            val headers = HashMap<String, String>()
            for (line in lines) {
                val colon = line.indexOf(':')
                // A leading space would be an obsolete line fold, which we refuse to interpret.
                if (colon <= 0 || line[0] == ' ' || line[0] == '\t') throw HttpException(400, "malformed header")
                val name = line.substring(0, colon)
                if (name.any { it <= ' ' || it.code >= 127 }) throw HttpException(400, "malformed header name")
                val key = name.lowercase()
                val value = line.substring(colon + 1).trim()
                val previous = headers[key]
                if (previous != null && key in SINGLETON_HEADERS) throw HttpException(400, "duplicate $key header")
                val separator = if (key == "cookie") "; " else ", "
                headers[key] = if (previous == null) value else previous + separator + value
            }
            return headers
        }

        private fun readBody(input: InputStream, contentLength: String?): ByteArray {
            if (contentLength == null) return ByteArray(0)
            if (contentLength.isEmpty() || contentLength.length > 9 || !contentLength.all { it in '0'..'9' }) {
                throw HttpException(400, "invalid content-length")
            }
            val length = contentLength.toInt()
            if (length > MAX_BODY_BYTES) throw HttpException(413, "body too large")
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(body, offset, length - offset)
                if (n < 0) throw HttpException(400, "truncated body")
                offset += n
            }
            return body
        }
    }
}
