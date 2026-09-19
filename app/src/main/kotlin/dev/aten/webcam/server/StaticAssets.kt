package dev.aten.webcam.server

import java.util.concurrent.ConcurrentHashMap

fun interface AssetSource {
    /** Returns the bytes of a bundled web asset, or null when it does not exist. */
    fun read(name: String): ByteArray?
}

fun interface ServerLog {
    fun log(message: String, error: Throwable?)
}

/** Serves a fixed whitelist of files, so request paths are never turned into file lookups. */
class StaticAssets(private val source: AssetSource) {
    class Route(val asset: String, val contentType: String, val public: Boolean)

    private val cache = ConcurrentHashMap<String, ByteArray>()

    fun route(path: String): Route? = ROUTES[path]

    fun response(route: Route): HttpResponse {
        val body = cache[route.asset] ?: source.read(route.asset)?.also { cache[route.asset] = it }
            ?: return HttpResponse.text(404, "not found")
        return HttpResponse(200, route.contentType, body)
    }

    companion object {
        private const val HTML = "text/html; charset=utf-8"
        private const val JS = "text/javascript; charset=utf-8"
        private const val CSS = "text/css; charset=utf-8"

        const val INDEX_PATH = "/"
        const val LOGIN_PATH = "/login"

        val paths: Set<String> get() = ROUTES.keys

        private val ROUTES = mapOf(
            INDEX_PATH to Route("index.html", HTML, public = false),
            "/app.js" to Route("app.js", JS, public = false),
            "/player-worklet.js" to Route("player-worklet.js", JS, public = false),
            "/capture-worklet.js" to Route("capture-worklet.js", JS, public = false),
            LOGIN_PATH to Route("login.html", HTML, public = true),
            "/login.js" to Route("login.js", JS, public = true),
            "/style.css" to Route("style.css", CSS, public = true),
        )
    }
}
