package dev.aten.webcam.server

import dev.aten.webcam.auth.AuditLog
import dev.aten.webcam.auth.LoginRateLimiter
import dev.aten.webcam.auth.Passwords
import dev.aten.webcam.auth.SessionStore
import dev.aten.webcam.tls.TlsContextFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

fun interface WsEndpoint {
    /** Returns the listener for a newly authenticated viewer, or null when no more viewers fit. */
    fun open(connection: WsConnection): WsConnection.Listener?
}

class WebServerConfig(
    val port: Int,
    val sslContext: () -> SSLContext,
    val certificateDer: () -> ByteArray,
    val password: () -> String,
    val trustProxyHeaders: () -> Boolean = { false },
    /** Invoked on the accept thread for every inbound connection, before any I/O happens. */
    val onConnectionAccepted: () -> Unit = {},
)

class WebServer(
    private val config: WebServerConfig,
    private val assets: StaticAssets,
    private val sessions: SessionStore,
    private val rateLimiter: LoginRateLimiter,
    private val audit: AuditLog,
    private val endpoint: WsEndpoint,
    private val log: ServerLog = ServerLog { _, _ -> },
) {
    private val serverSocket = ServerSocket()
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val webSockets = ConcurrentHashMap.newKeySet<WsConnection>()

    // No work queue: when every worker is busy new connections are dropped instead of piling up.
    private val workers = ThreadPoolExecutor(
        0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue(),
    ) { task -> Thread(task, "web-worker").apply { isDaemon = true } }

    val port: Int get() = serverSocket.localPort

    fun start() {
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(config.port), 32)
        thread(name = "web-accept", isDaemon = true) { acceptLoop() }
    }

    fun stop() {
        try {
            serverSocket.close()
        } catch (_: IOException) {
        }
        webSockets.forEach { it.close(1001, "server stopping") }
        workers.shutdown()
        openSockets.forEach(::closeQuietly)
    }

    fun closeWebSockets(reason: String) = webSockets.forEach { it.close(1008, reason) }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (!serverSocket.isClosed) log.log("accept failed", e)
                continue
            }
            config.onConnectionAccepted()
            try {
                workers.execute { handleConnection(socket) }
            } catch (_: RejectedExecutionException) {
                closeQuietly(socket)
            }
        }
    }

    private fun handleConnection(raw: Socket) {
        openSockets.add(raw)
        var socket: Socket = raw
        try {
            raw.tcpNoDelay = true
            raw.soTimeout = IO_TIMEOUT_MS.toInt()
            // The handshake runs here, on a worker, so a stalling peer cannot block the accept loop.
            val tls = config.sslContext().socketFactory
                .createSocket(raw, null, raw.port, true) as SSLSocket
            socket = tls
            tls.useClientMode = false
            tls.enabledProtocols = TlsContextFactory.PROTOCOLS
            tls.startHandshake()

            val timed = DeadlineInputStream(tls.inputStream, raw)
            val input = BufferedInputStream(timed, 8192)
            val output = BufferedOutputStream(tls.outputStream, 16 * 1024)
            var firstRequest = true
            while (true) {
                timed.deadlineAt = System.nanoTime() +
                    (if (firstRequest) IO_TIMEOUT_MS else KEEP_ALIVE_TIMEOUT_MS) * 1_000_000L
                val request = try {
                    HttpRequest.read(input) ?: return
                } catch (e: HttpException) {
                    HttpResponse.text(e.status, e.message ?: "bad request", close = true).write(output, null)
                    return
                }
                firstRequest = false

                if (WsHandshake.isUpgradeRequest(request)) {
                    timed.deadlineAt = Long.MAX_VALUE
                    upgrade(request, tls, input, output)
                    return
                }
                val response = route(request, clientAddress(request, raw))
                response.write(output, request.header("host"), omitBody = request.method == "HEAD")
                if (response.close) return
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } catch (e: RuntimeException) {
            log.log("connection handler failed", e)
        } finally {
            closeQuietly(socket)
            closeQuietly(raw)
            openSockets.remove(raw)
        }
    }

    private fun route(request: HttpRequest, address: String): HttpResponse {
        val authenticated = sessions.isValid(request.cookie(SessionStore.COOKIE_NAME))
        return when {
            request.path == "/login" && request.method == "POST" -> login(request, address)
            request.path == "/logout" && request.method == "POST" -> logout(request)
            request.method == "POST" -> HttpResponse.text(405, "method not allowed")
            request.path == "/cert.crt" ->
                HttpResponse(200, "application/x-x509-ca-cert", config.certificateDer())
            else -> {
                val route = assets.route(request.path) ?: return HttpResponse.text(404, "not found")
                when {
                    route.public && authenticated && request.path == StaticAssets.LOGIN_PATH ->
                        HttpResponse.redirect(StaticAssets.INDEX_PATH)
                    route.public || authenticated -> assets.response(route)
                    request.path == StaticAssets.INDEX_PATH -> HttpResponse.redirect(StaticAssets.LOGIN_PATH)
                    else -> HttpResponse.text(401, "login required")
                }
            }
        }
    }

    private fun login(request: HttpRequest, address: String): HttpResponse {
        if (!WsHandshake.originMatchesHost(request)) return HttpResponse.text(403, "cross-origin request refused")
        val retryAfter = rateLimiter.retryAfterSeconds(address)
        if (retryAfter > 0) {
            return HttpResponse(
                429, "text/plain; charset=utf-8", "too many attempts".toByteArray(),
                headers = listOf("Retry-After" to retryAfter.toString()),
            )
        }
        val candidate = formField(request.body, "password")
        if (candidate == null || !Passwords.matches(config.password(), candidate)) {
            rateLimiter.recordFailure(address)
            audit.record(address, AuditLog.LOGIN_FAILED, request.header("user-agent"))
            return HttpResponse.text(401, "wrong password")
        }
        rateLimiter.recordSuccess(address)
        audit.record(address, AuditLog.LOGIN_OK, request.header("user-agent"))
        return HttpResponse(
            200, "text/plain; charset=utf-8", "ok".toByteArray(),
            headers = listOf("Set-Cookie" to SessionStore.cookieFor(sessions.create())),
        )
    }

    private fun logout(request: HttpRequest): HttpResponse {
        if (!WsHandshake.originMatchesHost(request)) return HttpResponse.text(403, "cross-origin request refused")
        sessions.revoke(request.cookie(SessionStore.COOKIE_NAME))
        return HttpResponse(
            200, "text/plain; charset=utf-8", "ok".toByteArray(),
            headers = listOf("Set-Cookie" to SessionStore.CLEARING_COOKIE),
        )
    }

    private fun upgrade(request: HttpRequest, socket: Socket, input: InputStream, output: BufferedOutputStream) {
        val host = request.header("host")
        val refusal = when {
            request.path != "/ws" -> HttpResponse.text(404, "not found", close = true)
            !sessions.isValid(request.cookie(SessionStore.COOKIE_NAME)) ->
                HttpResponse.text(401, "login required", close = true)
            !WsHandshake.originMatchesHost(request) ->
                HttpResponse.text(403, "cross-origin request refused", close = true)
            else -> null
        }
        val response = refusal ?: WsHandshake.respond(request)
        response.write(output, host)
        if (response.status != 101) return

        val connection = WsConnection(
            socket, input, output,
            remoteAddress = clientAddress(request, socket),
            userAgent = request.header("user-agent").orEmpty(),
        )
        val listener = endpoint.open(connection)
        if (listener == null) {
            connection.close(1013, "not accepting viewers")
            connection.run(object : WsConnection.Listener {})
            return
        }
        webSockets.add(connection)
        audit.record(connection.remoteAddress, AuditLog.VIEWER_CONNECTED, connection.userAgent)
        try {
            connection.run(listener)
        } finally {
            webSockets.remove(connection)
            audit.record(connection.remoteAddress, AuditLog.VIEWER_DISCONNECTED, connection.userAgent)
        }
    }

    private fun clientAddress(request: HttpRequest, socket: Socket): String {
        val direct = socket.inetAddress?.hostAddress ?: "unknown"
        if (!config.trustProxyHeaders()) return direct
        // Only the last X-Forwarded-For hop was written by our own proxy; earlier ones are client-supplied.
        val forwarded = request.header("cf-connecting-ip")
            ?: request.header("x-forwarded-for")?.substringAfterLast(',')
        val cleaned = forwarded?.trim()?.takeIf { it.length <= 45 && it.all(ADDRESS_CHARS::contains) }
        return if (cleaned.isNullOrEmpty()) direct else cleaned
    }

    private fun formField(body: ByteArray, name: String): String? {
        val pair = String(body, Charsets.UTF_8).split('&').firstOrNull { it.startsWith("$name=") } ?: return null
        return try {
            URLDecoder.decode(pair.substring(name.length + 1), "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    /**
     * Bounds the total time a peer may take to deliver a request. A per-read timeout alone would
     * let a client hold a worker for hours by trickling one byte at a time.
     */
    private class DeadlineInputStream(private val delegate: InputStream, private val socket: Socket) : InputStream() {
        @Volatile
        var deadlineAt = Long.MAX_VALUE

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val deadline = deadlineAt
            if (deadline != Long.MAX_VALUE) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) throw SocketTimeoutException("request deadline exceeded")
                socket.soTimeout = remainingMs.toInt().coerceAtLeast(1)
            }
            return delegate.read(buffer, offset, length)
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 48
        private const val IO_TIMEOUT_MS = 10_000L
        private const val KEEP_ALIVE_TIMEOUT_MS = 5_000L
        private const val ADDRESS_CHARS = "0123456789abcdefABCDEF:."
    }
}
