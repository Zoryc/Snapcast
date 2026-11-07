package app.snapcast.proxy.http

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A NanoHTTPD response that writes itself instead of relying on `Response.send`, which (in 2.3.1):
 * - prints `Content-Length: -1` for a HEAD answer of unknown length and prints Content-Length twice when the
 *   header was also added explicitly,
 * - swallows (and logs at SEVERE) I/O errors in the middle of a body, leaving a keep-alive connection
 *   desynchronized after a truncated fixed-length body,
 * - gzips `text/` bodies when the client accepts it.
 *
 * Here the body is never compressed, HEAD/204/304 answers carry no body (but keep their declared length),
 * a body is fixed-length when [bodyLength] is known and chunked otherwise, and any failure while sending
 * closes the connection quietly (NanoHTTPD treats a `SocketException("NanoHttpd Shutdown")` as a normal close).
 * CORS headers are added to every response.
 */
internal class ProxyResponse(
    status: IStatus,
    mimeType: String?,
    data: InputStream?,
    /** Body length in bytes, or -1 when unknown (sent chunked). Also the length announced for HEAD. */
    private val bodyLength: Long,
) : NanoHTTPD.Response(status, mimeType, data ?: Streams.empty(), bodyLength) {

    /** Headers in insertion order, keyed by lower-case name. */
    private val headerLines = LinkedHashMap<String, Pair<String, String>>()
    private var keepAliveRequested = true

    /** Told when this response starts and stops being sent (see [ProxyActivity]). */
    var activity: ProxyActivity? = null

    init {
        Cors.HEADERS.forEach { (name, value) -> addHeader(name, value) }
    }

    override fun addHeader(name: String, value: String) {
        super.addHeader(name, value)
        headerLines[name.lowercase(Locale.ROOT)] = name to value
    }

    /** Adds the header when [value] is not null; returns this for chaining. */
    fun header(name: String, value: String?): ProxyResponse {
        if (value != null) addHeader(name, value)
        return this
    }

    /** Asks NanoHTTPD to close the connection after this response. */
    fun closeAfter(): ProxyResponse = header("Connection", "close")

    override fun setKeepAlive(useKeepAlive: Boolean) {
        super.setKeepAlive(useKeepAlive)
        keepAliveRequested = useKeepAlive
    }

    /** Media must never be re-encoded: NanoHTTPD's gzip decision is ignored. */
    override fun setGzipEncoding(encodeAsGzip: Boolean) {
        super.setGzipEncoding(false)
    }

    override fun send(outputStream: OutputStream) {
        val activity = activity
        activity?.begin()
        try {
            sendTracked(outputStream)
        } finally {
            activity?.end()
        }
    }

    private fun sendTracked(outputStream: OutputStream) {
        val code = status.requestStatus
        val noBodyStatus = code < 200 || code == 204 || code == 304
        val sendBody = requestMethod != NanoHTTPD.Method.HEAD && !noBodyStatus
        val chunked = sendBody && bodyLength < 0
        val head = StringBuilder(512)
        head.append("HTTP/1.1 ").append(status.description).append("\r\n")
        mimeType?.let { appendHeader(head, "Content-Type", it) }
        if ("date" !in headerLines) appendHeader(head, "Date", DATE_FORMAT.format(ZonedDateTime.now(ZoneOffset.UTC)))
        headerLines.values.forEach { (name, value) -> appendHeader(head, name, value) }
        if ("connection" !in headerLines) appendHeader(head, "Connection", if (keepAliveRequested) "keep-alive" else "close")
        when {
            noBodyStatus -> Unit
            bodyLength >= 0 -> appendHeader(head, "Content-Length", bodyLength.toString())
            chunked -> appendHeader(head, "Transfer-Encoding", "chunked")
        }
        head.append("\r\n")
        val body = data
        try {
            val out = BufferedOutputStream(outputStream, BUFFER_SIZE)
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            if (sendBody) {
                if (chunked) writeChunked(body, out) else writeFixed(body, out, bodyLength)
            }
            out.flush()
        } catch (e: Exception) {
            // Client gone, upstream failed or the body was shorter than announced: the connection cannot be reused.
            throw SocketException(NANOHTTPD_SHUTDOWN)
        } finally {
            closeQuietly(body)
        }
    }

    private fun writeFixed(body: InputStream, out: BufferedOutputStream, length: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = length
        while (remaining > 0) {
            val read = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Body ended $remaining bytes early")
            out.write(buffer, 0, read)
            remaining -= read
            if (body.available() <= 0) out.flush()
        }
    }

    private fun writeChunked(body: InputStream, out: BufferedOutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = body.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            out.write(Integer.toHexString(read).toByteArray(Charsets.US_ASCII))
            out.write(CRLF)
            out.write(buffer, 0, read)
            out.write(CRLF)
            out.flush()
        }
        out.write(LAST_CHUNK)
    }

    private fun appendHeader(head: StringBuilder, name: String, value: String) {
        head.append(name).append(": ").append(value.replace('\r', ' ').replace('\n', ' ')).append("\r\n")
    }

    private fun closeQuietly(stream: InputStream) {
        try {
            stream.close()
        } catch (e: IOException) {
            // Nothing left to do with a broken body.
        }
    }

    companion object {
        /** The message NanoHTTPD's ClientHandler treats as an orderly connection close (nothing is logged). */
        const val NANOHTTPD_SHUTDOWN = "NanoHttpd Shutdown"
        /**
         * Copy buffer for bodies (and the socket's output buffer). 64 KiB moves a typical segment in few
         * read/write/flush rounds, so the radio and CPU wake less often per second of video.
         */
        private const val BUFFER_SIZE = 64 * 1024
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
        private val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        /** IMF-fixdate (RFC 9110 section 5.6.7). */
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
    }
}

/** CORS headers sent with every proxy response (receivers load media from another origin). */
internal object Cors {
    val HEADERS: List<Pair<String, String>> = listOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers" to "*, Range, Content-Type, Origin, Accept",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges, Content-Type",
    )
}

/** A status line for any code, including those NanoHTTPD's enum lacks (502, 504, ...) and upstream ones. */
internal class HttpStatus(private val code: Int, private val reason: String) : IStatus {
    override fun getDescription(): String = "$code $reason"

    override fun getRequestStatus(): Int = code

    companion object {
        private val REASONS = mapOf(
            200 to "OK", 203 to "Non-Authoritative Information", 204 to "No Content", 206 to "Partial Content",
            400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 410 to "Gone", 416 to "Range Not Satisfiable", 429 to "Too Many Requests",
            451 to "Unavailable For Legal Reasons", 500 to "Internal Server Error", 501 to "Not Implemented",
            502 to "Bad Gateway", 503 to "Service Unavailable", 504 to "Gateway Timeout",
        )

        fun of(code: Int, upstreamReason: String? = null): HttpStatus {
            val reason = REASONS[code]
                ?: upstreamReason?.filter { it in ' '..'~' }?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Status $code"
            return HttpStatus(code, reason)
        }
    }
}

/** Factories for [ProxyResponse]s. */
internal object Responses {
    private const val TEXT = "text/plain; charset=utf-8"

    /** Streams [data]: fixed-length when [length] >= 0, chunked otherwise. */
    fun stream(code: Int, mimeType: String?, data: InputStream, length: Long, reason: String? = null): ProxyResponse =
        ProxyResponse(HttpStatus.of(code, reason), mimeType, data, length)

    /** An in-memory body (sent only for GET; HEAD gets the same headers). */
    fun bytes(code: Int, mimeType: String?, body: ByteArray): ProxyResponse =
        ProxyResponse(HttpStatus.of(code), mimeType, ByteArrayInputStream(body), body.size.toLong())

    /** Headers only, announcing [length] (or nothing when -1). Used for HEAD. */
    fun headersOnly(code: Int, mimeType: String?, length: Long, reason: String? = null): ProxyResponse =
        ProxyResponse(HttpStatus.of(code, reason), mimeType, null, length)

    fun text(code: Int, text: String): ProxyResponse = bytes(code, TEXT, text.toByteArray(Charsets.UTF_8))

    fun error(code: Int, message: String): ProxyResponse = text(code, "$message\n")

    fun notFound(): ProxyResponse = error(404, "Not Found")

    fun forbidden(): ProxyResponse = error(403, "Forbidden")

    fun badGateway(message: String = "Bad Gateway"): ProxyResponse = error(502, message)

    fun rangeNotSatisfiable(total: Long): ProxyResponse =
        error(416, "Range Not Satisfiable").header("Content-Range", "bytes */$total").header("Accept-Ranges", "bytes")

    /** CORS preflight answer. */
    fun preflight(): ProxyResponse = ProxyResponse(HttpStatus.of(204), null, null, 0)
        .header("Access-Control-Max-Age", "86400")
        .header("Access-Control-Allow-Private-Network", "true")
}
