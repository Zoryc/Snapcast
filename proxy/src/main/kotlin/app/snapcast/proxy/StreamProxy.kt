package app.snapcast.proxy

import app.snapcast.proxy.http.ProxyActivity
import app.snapcast.proxy.server.HeaderPolicy
import app.snapcast.proxy.server.ProxyHandler
import app.snapcast.proxy.server.ProxyHttpServer
import app.snapcast.proxy.server.ProxyRoutes
import app.snapcast.proxy.server.Registration
import app.snapcast.proxy.server.Registry
import app.snapcast.proxy.server.UpstreamClient
import app.snapcast.proxy.server.UrlSigner
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.SecureRandom

/**
 * Local HTTP server the receiver streams from. Forwards requests to the real URLs with the captured
 * headers, supports Range/HEAD/OPTIONS, streams bodies without buffering whole files, rewrites HLS
 * playlists and DASH manifests so every sub-resource also goes through the proxy, and adds CORS headers.
 *
 * Routes:
 * - `GET|HEAD /stream/{id}[/{name}]`            main resource of a registered stream
 * - `GET|HEAD /u/{id}/{sig}/{b64url}[/{name}]`  exact sub-resource URL (HLS variants, segments, keys)
 * - `GET|HEAD /b/{id}/{sig}/{b64base}/{rest}`   base-relative sub-resource (DASH templates/BaseURL)
 * - `GET|HEAD /local/{id}[/{name}]`             registered [LocalSource]
 * - `GET|HEAD /sub/{id}.vtt`                    registered subtitle, always served as WebVTT
 * - `OPTIONS *`                                 CORS preflight
 * - `GET /health`                               liveness check
 *
 * `{sig}` is an HMAC over the stream id and the encoded URL with a per-process secret, so the proxy
 * cannot be used as an open relay for the user's cookies.
 *
 * Registrations survive [stop]/[start]; [clear] removes them.
 *
 * Thread-safe. [start]/[stop] may be called from any thread.
 */
class StreamProxy(
    private val client: OkHttpClient,
    private val config: ProxyConfig = ProxyConfig(),
    /** HMAC key; random per instance when null. */
    secret: ByteArray? = null,
    private val log: (String) -> Unit = {},
    /** Told of each receiver request (not health checks), on the server's thread: keep it cheap. */
    onRequest: () -> Unit = {},
) {
    private val registry = Registry()
    private val routes: ProxyRoutes
    private val upstream = UpstreamClient(client)
    private val activity = ProxyActivity(onRequest = onRequest)
    private val handler: ProxyHandler
    private val lock = Any()

    @Volatile
    private var server: ProxyHttpServer? = null

    /** The port last bound, tried first on a restart: a receiver's URLs name it (it may be an ephemeral fallback). */
    private var lastBoundPort = -1

    init {
        val key = secret?.takeIf { it.isNotEmpty() } ?: ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        val signer = UrlSigner(key)
        routes = ProxyRoutes(signer)
        handler = ProxyHandler(registry, routes, signer, upstream, log) { problem ->
            synchronized(problemLock) {
                problems.remove(problem.streamId)
                problems[problem.streamId] = problem
                while (problems.size > MAX_PROBLEMS) problems.remove(problems.keys.first())
            }
        }
    }

    private val problemLock = Any()

    /** Latest problem per stream id, oldest first. */
    private val problems = LinkedHashMap<String, StreamProblem>()

    /**
     * The latest problem of one of [streamIds] (any stream when null), if it was at most [withinMs] ago, taken
     * once: it explains the receiver error that follows, and a later, unrelated error must not get the same
     * explanation.
     */
    fun takeRecentProblem(
        withinMs: Long,
        streamIds: Collection<String>? = null,
        now: Long = System.currentTimeMillis(),
    ): StreamProblem? = synchronized(problemLock) {
        val problem = problems.values.lastOrNull { (streamIds == null || it.streamId in streamIds) && now - it.atMs in 0..withinMs }
        problem?.let { problems.remove(it.streamId) }
        problem
    }

    val isRunning: Boolean get() = server?.isAlive == true

    /** Bound port while running, otherwise -1. */
    val port: Int get() = server?.takeIf { it.isAlive }?.listeningPort ?: -1

    /** Starts the server (no-op when already running). Throws IOException when the port cannot be bound. */
    fun start() {
        synchronized(lock) {
            if (server?.isAlive == true) return
            server?.stop()
            server = null
            val previous = lastBoundPort.takeIf { it > 0 && it != config.port }
            server = previous?.let { runCatching { bind(it) }.getOrNull() } ?: try {
                bind(config.port)
            } catch (e: IOException) {
                if (!config.fallbackToEphemeralPort || config.port == 0) throw e
                log("Port ${config.port} unavailable (${e.message}); binding an ephemeral port")
                bind(0)
            }
            lastBoundPort = server?.listeningPort ?: -1
            log("Proxy listening on ${config.bindAddress}:$port")
        }
    }

    /** Stops the server: closes the listening socket and every client connection and cancels upstream calls. */
    fun stop() {
        synchronized(lock) {
            server?.stop()
            server = null
            upstream.cancelAll()
        }
    }

    fun registerStream(request: ProxyStreamRequest): ProxiedStream {
        val policy = HeaderPolicy(request.headers, request.credentialsUrl ?: request.url, request.cookiesByHost, config.defaultUserAgent)
        val id = registry.add(Registration.Stream(request, policy))
        val name = ProxyRoutes.streamName(request.url, ProxyRoutes.typeOf(request.url, request.typeHint))
        return ProxiedStream(id, routes.stream(id, name))
    }

    fun registerLocal(source: LocalSource, fileNameHint: String): ProxiedStream {
        val id = registry.add(Registration.Local(source))
        val name = ProxyRoutes.sanitizeName(fileNameHint) ?: "media"
        return ProxiedStream(id, routes.local(id, name))
    }

    fun registerSubtitle(source: SubtitleSource): ProxiedSubtitle {
        val policy = (source as? SubtitleSource.Remote)?.let {
            HeaderPolicy(it.headers, it.url, emptyMap(), config.defaultUserAgent)
        }
        val id = registry.add(Registration.Subtitle(source, policy))
        return ProxiedSubtitle(id, routes.subtitle(id))
    }

    /**
     * Milliseconds since a receiver last used the proxy (0 while a response is being sent), or null if none has.
     * A receiver keeps fetching from it while it plays, whether or not the phone still talks to it.
     */
    fun idleMillis(): Long? = activity.idleMillis()

    fun unregister(id: String) {
        registry.remove(id)
    }

    /** Removes every registration. */
    fun clear() {
        registry.clear()
    }

    /** "http://[host]:[port][path]" (IPv6 hosts are bracketed). Uses the configured port while stopped. */
    fun url(host: String, path: String): String {
        val trimmed = host.trim()
        val authorityHost = if (trimmed.contains(':') && !trimmed.startsWith("[")) {
            // RFC 6874: a zone id separator inside brackets is written "%25".
            "[" + trimmed.replace("%", "%25") + "]"
        } else {
            trimmed
        }
        val actualPort = port.takeIf { it > 0 } ?: config.port
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "http://$authorityHost:$actualPort$normalizedPath"
    }

    private fun bind(port: Int): ProxyHttpServer {
        // NanoHTTPD binds on its listener thread and would wait forever if the address were rejected there.
        if (port !in 0..MAX_PORT) throw IOException("Invalid port $port")
        val created = ProxyHttpServer(config.bindAddress.trim().ifEmpty { null }, port, handler, activity)
        try {
            created.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        } catch (e: IOException) {
            created.stop()
            throw e
        }
        return created
    }

    companion object {
        private const val SECRET_BYTES = 32
        private const val MAX_PORT = 65535
        private const val MAX_PROBLEMS = 32

        /** The id of the registered stream a proxy URL (or path) belongs to, or null for any other URL. */
        fun streamIdOf(url: String): String? {
            val marker = "/${ProxyRoutes.STREAM}/"
            val start = url.indexOf(marker)
            if (start < 0) return null
            return url.substring(start + marker.length).substringBefore('/').takeIf { it.isNotEmpty() }
        }
    }
}
