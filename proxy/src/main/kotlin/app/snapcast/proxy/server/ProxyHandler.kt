package app.snapcast.proxy.server

import app.snapcast.core.media.HlsPlaylistText
import app.snapcast.core.media.HlsVariables
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.media.SubtitleConverter
import app.snapcast.core.model.StreamType
import app.snapcast.core.util.UrlUtils
import app.snapcast.proxy.StreamProblem
import app.snapcast.proxy.SubtitleSource
import app.snapcast.proxy.http.Base64Url
import app.snapcast.proxy.http.ByteRange
import app.snapcast.proxy.http.ClosingInputStream
import app.snapcast.proxy.http.ContentRange
import app.snapcast.proxy.http.LimitedInputStream
import app.snapcast.proxy.http.PathCodec
import app.snapcast.proxy.http.ProxyResponse
import app.snapcast.proxy.http.Responses
import app.snapcast.proxy.http.Streams
import app.snapcast.proxy.http.TextCodec
import app.snapcast.proxy.rewrite.DashRewriter
import app.snapcast.proxy.rewrite.HlsRewriter
import app.snapcast.proxy.rewrite.SafeXml
import fi.iki.elonen.NanoHTTPD.Method
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** The parts of a client request the proxy looks at. */
internal class ClientRequest(
    val method: Method,
    /** Percent-DECODED path, as NanoHTTPD provides it. */
    val path: String,
    /** Raw (still encoded) query string without "?", or null. */
    val rawQuery: String?,
    /** Request headers keyed by lower-case name. */
    private val headers: Map<String, String>,
) {
    val isHead: Boolean get() = method == Method.HEAD

    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

/**
 * Routes and answers proxy requests (see [app.snapcast.proxy.StreamProxy] for the routes). Remote resources are
 * fetched with the registration's [HeaderPolicy]; bodies are streamed except playlists and subtitles, which are
 * read (capped) to be rewritten or converted.
 */
internal class ProxyHandler(
    private val registry: Registry,
    private val routes: ProxyRoutes,
    private val signer: UrlSigner,
    private val upstream: UpstreamClient,
    private val log: (String) -> Unit,
    private val onProblem: (StreamProblem) -> Unit = {},
) {
    /** Never throws: failures become 4xx/5xx answers (502 for upstream I/O errors). */
    fun serve(request: ClientRequest): ProxyResponse = try {
        route(request)
    } catch (e: IOException) {
        log("Upstream failure for ${describe(request)}: ${e.javaClass.simpleName}: ${e.message}")
        Responses.badGateway()
    } catch (e: RuntimeException) {
        log("Proxy error for ${describe(request)}: $e")
        Responses.error(500, "Internal Server Error")
    }

    /** Method and route kind only: paths carry encoded upstream URLs, which do not belong in logs. */
    private fun describe(request: ClientRequest): String = "${request.method} /${request.path.split('/').getOrNull(1).orEmpty()}"

    private fun route(request: ClientRequest): ProxyResponse {
        when (request.method) {
            Method.OPTIONS -> return Responses.preflight()
            Method.GET, Method.HEAD -> Unit
            // NanoHTTPD does not consume request bodies, so the connection cannot be reused.
            else -> return Responses.error(405, "Method Not Allowed").header("Allow", "GET, HEAD, OPTIONS").closeAfter()
        }
        val segments = request.path.split('/').drop(1)
        return when (segments.firstOrNull()) {
            ProxyRoutes.HEALTH -> if (segments.drop(1).all { it.isEmpty() }) Responses.text(200, "ok") else Responses.notFound()
            ProxyRoutes.STREAM -> serveStream(segments.getOrNull(1), request)
            ProxyRoutes.EXACT -> serveExact(segments, request)
            ProxyRoutes.BASE -> serveBase(request)
            ProxyRoutes.LOCAL -> serveLocal(segments.getOrNull(1), request)
            ProxyRoutes.SUBTITLE -> serveSubtitle(segments.getOrNull(1), segments.size)
            else -> Responses.notFound()
        }
    }

    // ---- remote streams ----

    private fun serveStream(id: String?, request: ClientRequest): ProxyResponse {
        if (id == null) return Responses.notFound()
        val stream = registry.stream(id) ?: return Responses.notFound()
        return proxyRemote(Target(id, stream, stream.request.url, main = true), request)
    }

    /** `/u/{id}/{sig}/{b64url}[/{name}]` */
    private fun serveExact(segments: List<String>, request: ClientRequest): ProxyResponse {
        val id = segments.getOrNull(1) ?: return Responses.notFound()
        val stream = registry.stream(id) ?: return Responses.notFound()
        val signature = segments.getOrNull(2)
        val encoded = segments.getOrNull(3)
        if (encoded.isNullOrEmpty() || !signer.verify(ProxyRoutes.EXACT, id, encoded, signature)) return Responses.forbidden()
        val url = Base64Url.decodeToString(encoded)?.takeIf(UrlUtils::isHttp) ?: return Responses.error(400, "Bad Request")
        return proxyRemote(Target(id, stream, url, main = false), request)
    }

    /** `/b/{id}/{sig}/{b64base}/{rest}`: rest (and the raw query) resolved against the decoded base. */
    private fun serveBase(request: ClientRequest): ProxyResponse {
        val parts = request.path.split('/', limit = 6)
        val id = parts.getOrNull(2) ?: return Responses.notFound()
        val stream = registry.stream(id) ?: return Responses.notFound()
        val signature = parts.getOrNull(3)
        val encoded = parts.getOrNull(4)
        if (encoded.isNullOrEmpty() || !signer.verify(ProxyRoutes.BASE, id, encoded, signature)) return Responses.forbidden()
        val base = Base64Url.decodeToString(encoded)?.takeIf(UrlUtils::isHttp) ?: return Responses.error(400, "Bad Request")
        val target = resolveUnder(base, parts.getOrElse(5) { "" }, request.rawQuery) ?: return Responses.forbidden()
        return proxyRemote(Target(id, stream, target, main = false), request)
    }

    /**
     * Resolves a decoded [rest] (re-encoded first) against [base]. The reference is forced to be a relative path
     * ("./" prefix), may not contain dot-segments, and the result must stay under the base's directory (on its
     * origin), so a signed base can neither be turned into a relay for other hosts nor for other paths of its
     * host, which receive the user's cookies.
     */
    private fun resolveUnder(base: String, rest: String, rawQuery: String?): String? {
        if (hasDotSegment(rest)) return null
        val reference = "./" + PathCodec.encodePath(rest) + (rawQuery?.let { "?$it" } ?: "")
        val resolved = UrlUtils.resolve(base, reference)
        val baseUrl = base.toHttpUrlOrNull() ?: return null
        val target = resolved.toHttpUrlOrNull() ?: return null
        val sameOrigin = target.scheme == baseUrl.scheme && target.host == baseUrl.host && target.port == baseUrl.port
        // Defense in depth: both paths are OkHttp-canonical, and the target's is exactly what is fetched.
        val baseDir = baseUrl.encodedPath.substringBeforeLast('/') + "/"
        val underBase = target.encodedPath.startsWith(baseDir)
        return resolved.takeIf { sameOrigin && underBase }
    }

    /**
     * True when the decoded [rest] has a "." or ".." segment. NanoHTTPD decodes the path, so "%2E%2E" shows up here
     * as ".."; receivers remove dot-segments before sending and rewritten references never contain them, so only
     * a hand-crafted request does, to climb out of the signed directory. '\' also separates segments and a
     * ";params" suffix is ignored, as some upstream servers treat "..\" and "..;" like "../".
     */
    private fun hasDotSegment(rest: String): Boolean =
        rest.split('/', '\\').any { segment -> segment.substringBefore(';').let { it == "." || it == ".." } }

    /** A remote resource of a registered stream: its main URL or a sub-resource. */
    private class Target(val id: String, val stream: Registration.Stream, val url: String, val main: Boolean) {
        /** The stream's type hint applies to its main resource only. */
        val hint: StreamType? = if (main) stream.request.typeHint else null
        val urlType: StreamType? = MediaClassifier.typeFromUrl(url)
        val expected: StreamType? = hint ?: urlType
        val knownPlaylist: Boolean = expected?.isAdaptive == true
    }

    private fun proxyRemote(target: Target, request: ClientRequest): ProxyResponse {
        val url = target.url.toHttpUrlOrNull() ?: return Responses.badGateway("Unsupported upstream URL")
        // Playlists are always fetched whole: a partial playlist cannot be rewritten.
        val range = if (target.knownPlaylist) null else ByteRange.parse(request.header("range"))
        try {
            if (request.isHead && !target.knownPlaylist) return headRemote(url, target, range)
            val response = upstream.execute(url, "GET", target.stream.policy, range?.headerValue, identity = !target.knownPlaylist)
            return answer(response, target, range, request.isHead)
        } catch (e: IOException) {
            unreachableReason(e)?.let { report(target, StreamProblem.Kind.UNREACHABLE, it) }
            throw e
        }
    }

    /**
     * Why the server could not be reached at all (no HTTP answer), or null for other I/O failures. Only a
     * certificate failure counts among TLS errors: on Android a connection reset mid-handshake is an
     * SSLException too, and says nothing the Chromecast would do better.
     */
    private fun unreachableReason(e: IOException): String? = when {
        e is SSLPeerUnverifiedException || (e is SSLException && causedByCertificate(e)) -> "a certificate the phone does not trust"
        e is UnknownHostException -> "a name the phone cannot resolve"
        e is ConnectException || e is NoRouteToHostException -> "a connection the phone could not open"
        else -> null
    }

    private fun causedByCertificate(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (cause is CertificateException || cause is CertPathValidatorException) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    private fun answer(response: UpstreamResponse, target: Target, range: ByteRange?, head: Boolean): ProxyResponse {
        val body = ClosingInputStream(response.response.body.byteStream(), response::close)
        try {
            val code = response.code
            if (code in 300..399) {
                body.close()
                return Responses.badGateway("Too many redirects")
            }
            if (code !in 200..299) return passThroughError(response, body, head)
            val contentType = response.header("Content-Type")
            val encoding = contentEncoding(response)
            var stream: InputStream = body
            if (code == 200) {
                val candidate = playlistCandidate(target, contentType, response.finalUrl)
                // The body is looked at whenever it can be read: a playlist is served rewritten only when it is
                // one, whatever its Content-Type or URL says (a 16-byte key labelled as a playlist stays a key,
                // a playlist labelled video/... is still rewritten).
                val readable = when {
                    encoding == null -> body
                    candidate != null -> decompressing(body, encoding) ?: return unsupportedEncoding(body, target, encoding)
                    // A playlist without a telling name or type, compressed although identity was asked for.
                    isGenericType(contentType) -> decompressing(body, encoding)
                    else -> null
                }
                if (readable != null) {
                    val pushback = PushbackInputStream(readable, SNIFF_BYTES)
                    val start = Streams.readUpTo(pushback, SNIFF_BYTES)
                    pushback.unread(start)
                    sniff(start)?.let { return servePlaylist(it, pushback, response, target) }
                    if (candidate != null && target.main && !looksBinary(start)) return notAPlaylist(pushback, target, candidate, start)
                    if (readable !== body) {
                        // Decompressed, but not a playlist after all: served decompressed, of unknown length.
                        val mime = contentTypeFor(contentType, target)
                        if (head) return Responses.headersOnly(200, mime, -1).also { pushback.close() }
                        return Responses.stream(200, mime, pushback, -1)
                    }
                    stream = pushback
                }
            }
            return media(response, stream, target, range, contentType, encoding, head)
        } catch (e: Throwable) {
            body.close()
            throw e
        }
    }

    private fun media(
        response: UpstreamResponse,
        stream: InputStream,
        target: Target,
        range: ByteRange?,
        contentType: String?,
        encoding: String?,
        head: Boolean,
    ): ProxyResponse {
        val code = response.code
        val length = response.response.body.contentLength()
        val mime = contentTypeFor(contentType, target)
        if (code == 200 && range != null && length >= 0 && encoding == null) {
            // The upstream ignored the Range header: skip to the start ourselves.
            val resolved = range.resolve(length)
            if (resolved == null) {
                stream.close()
                return Responses.rangeNotSatisfiable(length)
            }
            val answer = if (head) {
                stream.close()
                Responses.headersOnly(206, mime, resolved.length)
            } else {
                Streams.skipFully(stream, resolved.start)
                Responses.stream(206, mime, LimitedInputStream(stream, resolved.length), resolved.length)
            }
            return answer.header("Content-Range", resolved.contentRange).header("Accept-Ranges", "bytes")
        }
        val answer = if (head) {
            stream.close()
            Responses.headersOnly(code, mime, length, response.response.message)
        } else {
            Responses.stream(code, mime, stream, length, response.response.message)
        }
        val acceptRanges = if (code == 206 || (length >= 0 && encoding == null)) "bytes" else response.header("Accept-Ranges")
        return answer
            .header("Content-Range", response.header("Content-Range"))
            .header("Accept-Ranges", acceptRanges)
            .header("Content-Encoding", encoding)
    }

    private fun passThroughError(response: UpstreamResponse, body: InputStream, head: Boolean): ProxyResponse {
        val length = response.response.body.contentLength()
        val contentType = response.header("Content-Type")
        val answer = if (head) {
            body.close()
            Responses.headersOnly(response.code, contentType, length, response.response.message)
        } else {
            Responses.stream(response.code, contentType, body, length, response.response.message)
        }
        return answer.header("Content-Range", response.header("Content-Range")).header("Content-Encoding", contentEncoding(response))
    }

    /**
     * HEAD: asks the upstream with HEAD; when that fails, is refused (403/405/501) or announces no length, with
     * `GET` + `Range: bytes=0-0`, whose Content-Range reveals the total length. Only headers are returned.
     */
    private fun headRemote(url: HttpUrl, target: Target, range: ByteRange?): ProxyResponse {
        val policy = target.stream.policy
        val headResponse = try {
            upstream.execute(url, "HEAD", policy, null, identity = true)
        } catch (e: IOException) {
            log("Upstream HEAD failed (${e.message}); retrying with a ranged GET")
            null
        }
        val headContentType = headResponse?.header("Content-Type")
        val fallback = headResponse == null || headResponse.code in HEAD_UNSUPPORTED || lacksLength(headResponse, target)
        val response = if (fallback) {
            headResponse?.close()
            upstream.execute(url, "GET", policy, "bytes=0-0", identity = true)
        } else {
            headResponse
        }
        response.use {
            val code = response.code
            val contentType = response.header("Content-Type") ?: headContentType
            if (code in 300..399) return Responses.badGateway("Too many redirects")
            if (code in 200..299 && playlistCandidate(target, contentType, response.finalUrl) != null) {
                // The announced length must be the one of the rewritten playlist.
                response.close()
                return answer(upstream.execute(url, "GET", policy, null, identity = false), target, null, head = true)
            }
            val emptyResource = fallback && code == 416
            if (code !in 200..299 && !emptyResource) {
                val length = if (fallback) -1 else response.header("Content-Length")?.toLongOrNull() ?: -1
                return Responses.headersOnly(code, contentType, length, response.response.message)
            }
            val encoding = contentEncoding(response)
            val total: Long? = when {
                emptyResource -> ContentRange.parse(response.header("Content-Range"))?.total ?: 0L
                fallback && code == 206 -> ContentRange.parse(response.header("Content-Range"))?.total
                else -> response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            }
            val mime = contentTypeFor(contentType, target)
            if (range != null && total != null && encoding == null) {
                val resolved = range.resolve(total) ?: return Responses.rangeNotSatisfiable(total)
                return Responses.headersOnly(206, mime, resolved.length)
                    .header("Content-Range", resolved.contentRange)
                    .header("Accept-Ranges", "bytes")
            }
            val status = if (fallback) 200 else code
            val acceptRanges = if (total != null && encoding == null) "bytes" else response.header("Accept-Ranges")
            return Responses.headersOnly(status, mime, total ?: -1)
                .header("Accept-Ranges", acceptRanges)
                .header("Content-Encoding", encoding)
        }
    }

    /** A successful HEAD answer without Content-Length: the ranged GET may still reveal the total length. */
    private fun lacksLength(response: UpstreamResponse, target: Target): Boolean =
        response.code in 200..299 &&
            response.header("Content-Length") == null &&
            contentEncoding(response) == null &&
            playlistCandidate(target, response.header("Content-Type"), response.finalUrl) == null

    /**
     * The real content codings of [response] (lower case), or null for none: values that are not codings
     * ("none", "UTF-8", "binary", ...), which some servers send, are ignored like `identity`.
     */
    private fun contentEncoding(response: UpstreamResponse): String? =
        response.header("Content-Encoding")
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it in CONTENT_CODINGS }
            ?.joinToString(", ")
            ?.takeIf { it.isNotEmpty() }

    /** [body] decoded from [encoding] (gzip, or deflate with or without its zlib header); null when it cannot be. */
    private fun decompressing(body: InputStream, encoding: String): InputStream? = when (encoding) {
        "gzip", "x-gzip" -> GZIPInputStream(body)
        "deflate" -> {
            // Servers send "deflate" both zlib-wrapped (RFC 9110) and raw: a zlib header is CMF 0x?8 with
            // (CMF * 256 + FLG) a multiple of 31.
            val pushback = PushbackInputStream(body, 2)
            val first = Streams.readUpTo(pushback, 2)
            pushback.unread(first)
            val zlib = first.size == 2 &&
                (first[0].toInt() and 0x0F) == 8 &&
                ((first[0].toInt() and 0xFF) * 256 + (first[1].toInt() and 0xFF)) % 31 == 0
            val inflater = Inflater(!zlib)
            object : InflaterInputStream(pushback, inflater) {
                // close() only ends an Inflater it created itself; this one's native memory is freed here.
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        inflater.end()
                    }
                }
            }
        }
        else -> null
    }

    private fun unsupportedEncoding(body: InputStream, target: Target, encoding: String): ProxyResponse {
        body.close()
        report(target, StreamProblem.Kind.UNSUPPORTED_ENCODING, "a playlist compressed as $encoding")
        return Responses.badGateway("Playlist compressed as $encoding, which the proxy cannot decode")
    }

    /**
     * The stream's playlist URL answered 200 with something else (an expired link's web page, a JSON error, nothing):
     * an error for the receiver, not a "playlist" made of the page's lines.
     */
    private fun notAPlaylist(body: InputStream, target: Target, expected: StreamType, head: ByteArray): ProxyResponse {
        body.close()
        val instead = HlsPlaylistText.whatInstead(String(head, Charsets.UTF_8)).description
        log("Main resource of a stream is not a ${expected.label} playlist but $instead")
        report(target, StreamProblem.Kind.NOT_A_PLAYLIST, instead)
        return Responses.badGateway("Expected a ${expected.label} playlist, got $instead")
    }

    private fun report(target: Target, kind: StreamProblem.Kind, description: String) {
        onProblem(StreamProblem(target.id, kind, description, System.currentTimeMillis()))
    }

    // ---- playlists ----

    /**
     * HLS/DASH as announced by the type hint (main resource), else by a stream Content-Type, else by the URL
     * (unknown types such as `video/vnd.mpeg.dash.mpd` or `text/plain` included); null otherwise. Candidates are
     * confirmed by looking at the body, so a mislabeled media file is still served as media.
     */
    private fun playlistCandidate(target: Target, contentType: String?, finalUrl: HttpUrl): StreamType? {
        if (target.hint != null) return target.hint.takeIf { it.isAdaptive }
        MediaClassifier.typeFromContentType(contentType)?.let { return it.takeIf(StreamType::isAdaptive) }
        return (target.urlType ?: MediaClassifier.typeFromUrl(finalUrl.toString()))?.takeIf { it.isAdaptive }
    }

    private fun sniff(head: ByteArray): StreamType? =
        if (head.isEmpty()) null else MediaClassifier.sniffPlaylist(String(head, Charsets.UTF_8))

    /** Media containers (MP4, TS, WebM) contain NUL bytes right at the start; playlists never do. */
    private fun looksBinary(head: ByteArray): Boolean = head.any { it.toInt() == 0 }

    private fun servePlaylist(type: StreamType, stream: InputStream, response: UpstreamResponse, target: Target): ProxyResponse {
        val bytes = stream.use { Streams.readCapped(it, MAX_PLAYLIST_BYTES) }
        val playlistUrl = response.finalUrl.toString()
        val declared = response.response.body.contentType()?.charset()
        val mapper = routes.mapperFor(target.id)
        return when (type) {
            StreamType.HLS -> {
                val decoded = TextCodec.decodePlaylist(bytes, declared)
                // Byte order marks and old Mac line breaks would otherwise turn tag lines into "URIs".
                val text = HlsPlaylistText.clean(decoded.text) ?: decoded.text
                if (target.main) target.stream.mainVariables = HlsVariables.defined(text, playlistUrl)
                // Variables are substituted first: once a URI is signed into a route, the receiver cannot.
                val resolved = HlsVariables.resolve(text, playlistUrl, target.stream.mainVariables)
                val rewritten = HlsRewriter.rewrite(resolved, playlistUrl, mapper)
                Responses.bytes(200, HLS_MIME, rewritten.toByteArray(decoded.charset)).header("Cache-Control", "no-cache")
            }
            else -> {
                val rewritten = DashRewriter.rewrite(SafeXml.decode(bytes, declared), playlistUrl, mapper)
                    ?: return Responses.badGateway("Unsupported DASH manifest")
                Responses.bytes(200, DASH_MIME, rewritten.toByteArray(Charsets.UTF_8)).header("Cache-Control", "no-cache")
            }
        }
    }

    /**
     * The upstream Content-Type, except that a generic one (or none) on the main resource is replaced by the
     * stream type's MIME type; a sub-resource without any gets a guess from its URL.
     */
    private fun contentTypeFor(contentType: String?, target: Target): String {
        val specific = contentType?.takeUnless { isGenericType(it) }
        if (specific != null) return specific
        val byType = target.expected?.takeUnless { it == StreamType.OTHER || it.isAdaptive }?.defaultMimeType
        if (target.main && byType != null) return byType
        return contentType
            ?: byType
            ?: MediaClassifier.subtitleFormatFromUrl(target.url)?.mimeType
            ?: "application/octet-stream"
    }

    // ---- local files ----

    private fun serveLocal(id: String?, request: ClientRequest): ProxyResponse {
        val source = registry.local(id)?.source ?: return Responses.notFound()
        val mime = source.mimeType.ifBlank { "application/octet-stream" }
        val total = source.length?.takeIf { it >= 0 }
        return try {
            if (total == null) {
                // Unknown length: Range cannot be honoured.
                if (request.isHead) Responses.headersOnly(200, mime, -1) else Responses.stream(200, mime, source.open(0), -1)
            } else {
                val range = ByteRange.parse(request.header("range"))
                if (range == null) {
                    val data = if (request.isHead) null else LimitedInputStream(source.open(0), total)
                    localResponse(200, mime, data, total).header("Accept-Ranges", "bytes")
                } else {
                    val resolved = range.resolve(total) ?: return Responses.rangeNotSatisfiable(total)
                    val data = if (request.isHead) null else LimitedInputStream(source.open(resolved.start), resolved.length)
                    localResponse(206, mime, data, resolved.length)
                        .header("Content-Range", resolved.contentRange)
                        .header("Accept-Ranges", "bytes")
                }
            }
        } catch (e: IOException) {
            log("Local source failed: ${e.message}")
            Responses.error(500, "Local media unavailable")
        }
    }

    private fun localResponse(code: Int, mime: String, data: InputStream?, length: Long): ProxyResponse =
        if (data == null) Responses.headersOnly(code, mime, length) else Responses.stream(code, mime, data, length)

    // ---- subtitles ----

    /** `/sub/{id}.vtt` */
    private fun serveSubtitle(name: String?, segmentCount: Int): ProxyResponse {
        if (name == null || segmentCount != 2) return Responses.notFound()
        val registration = registry.subtitle(name.removeSuffix(".vtt")) ?: return Responses.notFound()
        val vtt = when (val source = registration.source) {
            is SubtitleSource.Inline -> SubtitleConverter.toVtt(source.text, source.format)
            is SubtitleSource.Remote -> {
                val url = source.url.toHttpUrlOrNull() ?: return Responses.badGateway("Unsupported subtitle URL")
                val policy = registration.policy ?: return Responses.badGateway("Unsupported subtitle URL")
                upstream.execute(url, "GET", policy, null, identity = false).use { response ->
                    if (response.code !in 200..299) {
                        val code = if (response.code in 300..399) 502 else response.code
                        return Responses.error(code, "Subtitle unavailable")
                    }
                    val bytes = Streams.readCapped(response.response.body.byteStream(), MAX_SUBTITLE_BYTES)
                    val text = TextCodec.decodeSubtitle(bytes, response.response.body.contentType()?.charset())
                    SubtitleConverter.toVtt(text, source.format)
                }
            }
        }
        return Responses.bytes(200, VTT_MIME, vtt.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val HLS_MIME = "application/vnd.apple.mpegurl"
        const val DASH_MIME = "application/dash+xml"
        const val VTT_MIME = "text/vtt; charset=utf-8"
        private const val SNIFF_BYTES = 4096
        private const val MAX_CAUSE_DEPTH = 8
        private val CONTENT_CODINGS = setOf("gzip", "x-gzip", "deflate", "br", "compress", "x-compress", "zstd")
        private const val MAX_PLAYLIST_BYTES = 8L * 1024 * 1024
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024
        private val HEAD_UNSUPPORTED = setOf(403, 405, 501)

        /** Content types servers use for anything: the URL or the body decides. */
        private val GENERIC_TYPES = setOf(
            "application/octet-stream", "binary/octet-stream", "application/binary", "application/unknown",
            "application/x-unknown", "application/download", "application/x-download", "application/force-download",
            "application/xml", "content/unknown", "unknown/unknown", "*/*",
        )

        fun isGenericType(contentType: String?): Boolean {
            val base = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            return base.isNullOrEmpty() || base in GENERIC_TYPES || (base.startsWith("text/") && base != "text/vtt")
        }
    }
}
