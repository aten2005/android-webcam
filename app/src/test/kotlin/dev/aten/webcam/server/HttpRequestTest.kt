package dev.aten.webcam.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class HttpRequestTest {
    private fun parse(raw: String) = HttpRequest.read(raw.toByteArray(Charsets.ISO_8859_1).inputStream())

    private fun assertStatus(expected: Int, raw: String) {
        try {
            parse(raw)
            fail("expected HttpException $expected")
        } catch (e: HttpException) {
            assertEquals(expected, e.status)
        }
    }

    @Test
    fun parsesGetWithQueryAndHeaders() {
        val request = parse("GET /index.html?a=1 HTTP/1.1\r\nHost: cam.local:8443\r\nX-Thing:  padded \r\n\r\n")!!
        assertEquals("GET", request.method)
        assertEquals("/index.html", request.path)
        assertEquals("a=1", request.query)
        assertEquals("cam.local:8443", request.header("host"))
        assertEquals("padded", request.header("X-THING"))
        assertEquals(0, request.body.size)
    }

    @Test
    fun readsQueryParameters() {
        val request = parse("GET /ws?clientx=1&client=abc&client=def HTTP/1.1\r\n\r\n")!!
        assertEquals("abc", request.queryParam("client"))
        assertNull(request.queryParam("missing"))
        assertNull(parse("GET /ws HTTP/1.1\r\n\r\n")!!.queryParam("client"))
    }

    @Test
    fun readsBodyAndLeavesFollowingBytes() {
        val input = "POST /login HTTP/1.1\r\nContent-Length: 5\r\n\r\nhelloGET / HTTP/1.1\r\n\r\n"
            .toByteArray().inputStream()
        assertArrayEquals("hello".toByteArray(), HttpRequest.read(input)!!.body)
        assertEquals("/", HttpRequest.read(input)!!.path)
        assertNull(HttpRequest.read(input))
    }

    @Test
    fun extractsCookiesAndTokens() {
        val request = parse("GET / HTTP/1.1\r\nCookie: a=1; __Host-sid=abc\r\nConnection: keep-alive, Upgrade\r\n\r\n")!!
        assertEquals("abc", request.cookie("__Host-sid"))
        assertEquals("1", request.cookie("a"))
        assertNull(request.cookie("sid"))
        assertTrue(request.headerHasToken("connection", "upgrade"))
    }

    @Test
    fun returnsNullOnImmediateEof() = assertNull(parse(""))

    @Test
    fun rejectsMalformedInput() {
        assertStatus(400, "GET /\r\n\r\n")
        assertStatus(400, "GET / HTTP/1.1\r\nNoColon\r\n\r\n")
        assertStatus(400, "GET / HTTP/1.1\r\nA: 1\r\n folded\r\n\r\n")
        assertStatus(400, "GET / HTTP/1.1\r\nBad Name: 1\r\n\r\n")
        assertStatus(400, "GET http://evil/ HTTP/1.1\r\n\r\n")
        assertStatus(400, "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n")
        assertStatus(400, "GET / HTTP/1.1\r\nHost: a")
        assertStatus(400, "POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n")
        assertStatus(400, "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort")
    }

    @Test
    fun rejectsUnsupportedMethodsAndEncodings() {
        assertStatus(405, "DELETE / HTTP/1.1\r\n\r\n")
        assertStatus(501, "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")
    }

    @Test
    fun enforcesSizeLimits() {
        assertStatus(431, "GET / HTTP/1.1\r\nX: ${"a".repeat(HttpRequest.MAX_HEAD_BYTES)}\r\n\r\n")
        val manyHeaders = (0..HttpRequest.MAX_HEADERS).joinToString("") { "X-$it: v\r\n" }
        assertStatus(431, "GET / HTTP/1.1\r\n$manyHeaders\r\n")
        assertStatus(413, "POST / HTTP/1.1\r\nContent-Length: ${HttpRequest.MAX_BODY_BYTES + 1}\r\n\r\n")
    }

    @Test
    fun writesResponseWithSecurityHeaders() {
        val out = ByteArrayOutputStream()
        HttpResponse.text(200, "hi").write(out, host = "cam.local:8443")
        val text = out.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 2\r\n"))
        assertTrue(text.contains("connect-src 'self' wss://cam.local:8443;"))
        assertTrue(text.contains("X-Content-Type-Options: nosniff\r\n"))
        assertTrue(text.endsWith("\r\n\r\nhi"))
    }

    @Test
    fun omitsUntrustedHostFromCsp() {
        val out = ByteArrayOutputStream()
        HttpResponse.text(200, "hi").write(out, host = "evil; script-src *", omitBody = true)
        val text = out.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("connect-src 'self';"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }
}
