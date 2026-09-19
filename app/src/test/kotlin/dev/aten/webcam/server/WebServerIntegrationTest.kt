package dev.aten.webcam.server

import dev.aten.webcam.auth.AuditLog
import dev.aten.webcam.auth.LoginRateLimiter
import dev.aten.webcam.auth.SessionStore
import dev.aten.webcam.tls.CertGenerator
import dev.aten.webcam.tls.TlsContextFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import java.net.http.HttpRequest as ClientRequest

class WebServerIntegrationTest {
    private val identity = CertGenerator.generate(listOf("127.0.0.1"))
    private val sessions = SessionStore()
    private val audit = AuditLog()
    private var limiterTime = 0L
    private var viewerLimitReached = false
    private var trustProxy = false
    private val accepted = CopyOnWriteArrayList<Unit>()
    private lateinit var server: WebServer
    private lateinit var clientContext: SSLContext
    private lateinit var client: HttpClient

    /** Echoes binary frames back with a marker byte, and text frames upper-cased. */
    private val echoEndpoint = WsEndpoint { connection ->
        if (viewerLimitReached) {
            null
        } else {
            object : WsConnection.Listener {
                override fun onText(text: String) = connection.sendText(text.uppercase())
                override fun onBinary(payload: ByteArray) {
                    connection.offer(WsFrameCodec.encode(WsOpcode.BINARY, byteArrayOf(0x7F), payload))
                }
            }
        }
    }

    @Before
    fun startServer() {
        val assets = StaticAssets { name -> "asset:$name".toByteArray() }
        val config = WebServerConfig(
            port = 0,
            sslContext = { TlsContextFactory.serverContext(identity) },
            certificateDer = { identity.certificate.encoded },
            password = { PASSWORD },
            trustProxyHeaders = { trustProxy },
            onConnectionAccepted = { accepted.add(Unit) },
        )
        // Every limiter query sees a later time, so pacing never interferes unless a test wants it to.
        val limiter = LoginRateLimiter { limiterTime.also { limiterTime += 60_000 } }
        server = WebServer(config, assets, sessions, limiter, audit, echoEndpoint).also { it.start() }

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("webcam", identity.certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        clientContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        client = HttpClient.newBuilder().sslContext(clientContext).build()
    }

    @After
    fun stopServer() = server.stop()

    private val origin get() = "https://127.0.0.1:${server.port}"

    private fun get(path: String, cookie: String? = null) = client.send(
        ClientRequest.newBuilder(URI("$origin$path")).apply { cookie?.let { header("Cookie", it) } }.build(),
        BodyHandlers.ofByteArray(),
    )

    private fun postLogin(password: String, originHeader: String? = origin, vararg extraHeaders: String) = client.send(
        ClientRequest.newBuilder(URI("$origin/login"))
            .POST(BodyPublishers.ofString("password=" + java.net.URLEncoder.encode(password, "UTF-8")))
            .apply {
                originHeader?.let { header("Origin", it) }
                if (extraHeaders.isNotEmpty()) headers(*extraHeaders)
            }
            .build(),
        BodyHandlers.ofString(),
    )

    private fun loginCookie(): String {
        val response = postLogin(PASSWORD)
        assertEquals(200, response.statusCode())
        return response.headers().firstValue("set-cookie").get().substringBefore(';')
    }

    private class Collector : WebSocket.Listener {
        val messages = LinkedBlockingQueue<Any>()
        val closed = CompletableFuture<Int>()
        private val binary = java.io.ByteArrayOutputStream()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            messages.add(data.toString())
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val chunk = ByteArray(data.remaining()).also { data.get(it) }
            binary.write(chunk)
            if (last) {
                messages.add(binary.toByteArray())
                binary.reset()
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closed.complete(statusCode)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closed.completeExceptionally(error)
        }
    }

    private fun openWebSocket(cookie: String?, originHeader: String? = origin, listener: Collector = Collector()): WebSocket =
        client.newWebSocketBuilder()
            .apply {
                cookie?.let { header("Cookie", it) }
                originHeader?.let { header("Origin", it) }
            }
            .buildAsync(URI("wss://127.0.0.1:${server.port}/ws"), listener)
            .get(10, TimeUnit.SECONDS)

    /** Returns 101 only when the client accepted the upgrade, otherwise the refusal's HTTP status. */
    private fun handshakeStatus(cookie: String?, originHeader: String?): Int = try {
        openWebSocket(cookie, originHeader).abort()
        101
    } catch (e: Exception) {
        val refusal = generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<WebSocketHandshakeException>().firstOrNull() ?: throw e
        val status = refusal.response.statusCode()
        if (status == 101) throw AssertionError("client rejected the server's 101 response", e)
        status
    }

    @Test
    fun redirectsAnonymousVisitorsToLogin() {
        val response = get("/")
        assertEquals(303, response.statusCode())
        assertEquals("/login", response.headers().firstValue("location").get())
        assertEquals(401, get("/app.js").statusCode())
        assertEquals(404, get("/../etc/passwd").statusCode())
        assertEquals(404, get("/unknown").statusCode())
    }

    @Test
    fun servesPublicAssetsWithSecurityHeaders() {
        val response = get("/login")
        assertEquals(200, response.statusCode())
        assertEquals("asset:login.html", String(response.body()))
        val headers = response.headers()
        assertTrue(headers.firstValue("content-security-policy").get().contains("wss://127.0.0.1:${server.port}"))
        assertEquals("nosniff", headers.firstValue("x-content-type-options").get())
        assertEquals("no-store", headers.firstValue("cache-control").get())
        assertEquals("asset:style.css", String(get("/style.css").body()))
    }

    @Test
    fun servesCertificateForManualTrust() {
        val response = get("/cert.crt")
        assertEquals("application/x-x509-ca-cert", response.headers().firstValue("content-type").get())
        assertArrayEquals(identity.certificate.encoded, response.body())
    }

    @Test
    fun loginIssuesSessionCookieThatUnlocksThePlayer() {
        assertEquals(401, postLogin("wrong").statusCode())
        assertEquals(403, postLogin(PASSWORD, originHeader = "https://evil.example").statusCode())
        assertEquals(403, postLogin(PASSWORD, originHeader = null).statusCode())

        val response = postLogin(PASSWORD)
        val setCookie = response.headers().firstValue("set-cookie").get()
        assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("Secure") && setCookie.contains("SameSite=Strict"))
        val cookie = setCookie.substringBefore(';')
        assertEquals("asset:index.html", String(get("/", cookie).body()))
        assertEquals("asset:app.js", String(get("/app.js", cookie).body()))
        assertEquals(303, get("/login", cookie).statusCode())

        val events = audit.snapshot().map { it.event }
        assertEquals(listOf(AuditLog.LOGIN_OK, AuditLog.LOGIN_FAILED), events)
    }

    @Test
    fun logoutRevokesTheSession() {
        val cookie = loginCookie()
        val response = client.send(
            ClientRequest.newBuilder(URI("$origin/logout")).POST(BodyPublishers.noBody())
                .header("Origin", origin).header("Cookie", cookie).build(),
            BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertEquals(303, get("/", cookie).statusCode())
    }

    @Test
    fun throttledLoginReportsRetryAfter() {
        limiterTime = 0
        val paced = LoginRateLimiter { 0L }
        val pacedServer = WebServer(
            WebServerConfig(0, { TlsContextFactory.serverContext(identity) }, { ByteArray(0) }, { PASSWORD }),
            StaticAssets { null }, SessionStore(), paced, AuditLog(), echoEndpoint,
        ).also { it.start() }
        try {
            val uri = URI("https://127.0.0.1:${pacedServer.port}/login")
            val send = {
                client.send(
                    ClientRequest.newBuilder(uri).POST(BodyPublishers.ofString("password=nope"))
                        .header("Origin", "https://127.0.0.1:${pacedServer.port}").build(),
                    BodyHandlers.ofString(),
                )
            }
            assertEquals(401, send().statusCode())
            val throttled = send()
            assertEquals(429, throttled.statusCode())
            assertEquals("1", throttled.headers().firstValue("retry-after").get())
        } finally {
            pacedServer.stop()
        }
    }

    @Test
    fun recordsForwardedAddressOnlyWhenProxyIsTrusted() {
        postLogin("wrong", origin, "X-Forwarded-For", "6.6.6.6, 203.0.113.7")
        assertEquals("127.0.0.1", audit.snapshot().first().address)
        trustProxy = true
        postLogin("wrong", origin, "X-Forwarded-For", "6.6.6.6, 203.0.113.7")
        assertEquals("203.0.113.7", audit.snapshot().first().address)
        postLogin("wrong", origin, "X-Forwarded-For", "<script>")
        assertEquals("127.0.0.1", audit.snapshot().first().address)
    }

    @Test
    fun webSocketRequiresSessionAndMatchingOrigin() {
        assertEquals(401, handshakeStatus(cookie = null, originHeader = origin))
        assertEquals(401, handshakeStatus(cookie = "__Host-sid=forged", originHeader = origin))
        val cookie = loginCookie()
        assertEquals(403, handshakeStatus(cookie, originHeader = "https://evil.example"))
        assertEquals(403, handshakeStatus(cookie, originHeader = null))
        assertEquals(101, handshakeStatus(cookie, originHeader = origin))
    }

    @Test
    fun webSocketExchangesTextAndLargeBinaryMessages() {
        val listener = Collector()
        val socket = openWebSocket(loginCookie(), listener = listener)
        socket.sendText("hello", true).get(5, TimeUnit.SECONDS)
        assertEquals("HELLO", listener.messages.poll(5, TimeUnit.SECONDS))

        val payload = ByteArray(40_000) { (it % 251).toByte() }
        socket.sendBinary(ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS)
        val echoed = listener.messages.poll(5, TimeUnit.SECONDS) as ByteArray
        assertEquals(0x7F.toByte(), echoed[0])
        assertArrayEquals(payload, echoed.copyOfRange(1, echoed.size))

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS)
        assertEquals(WebSocket.NORMAL_CLOSURE, listener.closed.get(5, TimeUnit.SECONDS))
        // The server records the disconnect on its own thread, shortly after the client sees the close.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (audit.snapshot().first().event != AuditLog.VIEWER_DISCONNECTED && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        val events = audit.snapshot().map { it.event }
        assertEquals(AuditLog.VIEWER_DISCONNECTED, events[0])
        assertEquals(AuditLog.VIEWER_CONNECTED, events[1])
    }

    @Test
    fun webSocketClosesOversizedMessagesAndExtraViewers() {
        val cookie = loginCookie()
        val tooBig = Collector()
        openWebSocket(cookie, listener = tooBig).sendBinary(ByteBuffer.wrap(ByteArray(70_000)), true)
        assertEquals(1009, tooBig.closed.get(5, TimeUnit.SECONDS))

        viewerLimitReached = true
        val refused = Collector()
        openWebSocket(cookie, listener = refused)
        assertEquals(1013, refused.closed.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun passwordChangeCanDropLiveViewers() {
        val listener = Collector()
        openWebSocket(loginCookie(), listener = listener)
        server.closeWebSockets("password changed")
        assertEquals(1008, listener.closed.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun rejectsOversizedRequestHeadAndClosesConnection() {
        (clientContext.socketFactory.createSocket("127.0.0.1", server.port) as SSLSocket).use { socket ->
            socket.soTimeout = 5000
            val out = socket.outputStream
            out.write("GET / HTTP/1.1\r\nHost: x\r\nX-Pad: ${"a".repeat(9000)}\r\n\r\n".toByteArray())
            out.flush()
            val reply = String(socket.inputStream.readBytes())
            assertTrue(reply.startsWith("HTTP/1.1 431 "))
            assertTrue(reply.contains("Connection: close"))
        }
    }

    @Test
    fun idleConnectionsDoNotStarveRealRequests() {
        val idle = (1..20).map { clientContext.socketFactory.createSocket("127.0.0.1", server.port) }
        try {
            assertEquals(200, get("/login").statusCode())
            assertNotNull(loginCookie())
            assertTrue(accepted.size >= 21)
        } finally {
            idle.forEach { it.close() }
        }
    }

    private companion object {
        const val PASSWORD = "p@ss word&=+%"
    }
}
