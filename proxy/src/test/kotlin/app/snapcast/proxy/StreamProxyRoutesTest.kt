package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import app.snapcast.proxy.http.Base64Url
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertFailsWith

/** Routing, signatures, CORS and server lifecycle. */
class StreamProxyRoutesTest {
    private val harness = ProxyTestHarness()
    private val origin = harness.origin

    @After
    fun tearDown() = harness.close()

    private fun registerHls(): ProxiedStream {
        origin.text(
            "/hls/master.m3u8",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n",
            "application/vnd.apple.mpegurl",
        )
        origin.text("/hls/low.m3u8", "#EXTM3U\n#EXTINF:4,\nseg1.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
        return harness.proxy.registerStream(ProxyStreamRequest(origin.url("/hls/master.m3u8"), emptyMap(), typeHint = StreamType.HLS))
    }

    private fun variantPath(stream: ProxiedStream): String =
        harness.fetch(stream.path).text.lines().first { it.startsWith("/u/") }

    @Test
    fun `OPTIONS answers 204 with CORS headers on any path`() {
        listOf("/stream/whatever", "/", "/u/a/b/c").forEach { path ->
            val response = harness.fetch(path, mapOf("Access-Control-Request-Method" to "GET"), method = "OPTIONS")
            assertThat(response.code).isEqualTo(204)
            assertCors(response)
            assertThat(response.header("Access-Control-Allow-Headers")).contains("Range")
        }
    }

    @Test
    fun `every answer carries CORS headers`() {
        val stream = registerHls()
        assertCors(harness.fetch(stream.path).also { assertThat(it.code).isEqualTo(200) })
        assertCors(harness.fetch("/stream/0123456789abcdef0123456789abcdef/x").also { assertThat(it.code).isEqualTo(404) })
        assertCors(harness.fetch("/u/${stream.id}/bad/sig/x").also { assertThat(it.code).isEqualTo(403) })
        assertCors(harness.fetch("/health"))
    }

    @Test
    fun `health check answers ok`() {
        val response = harness.fetch("/health")
        assertThat(response.code).isEqualTo(200)
        assertThat(response.text).isEqualTo("ok")
    }

    @Test
    fun `unknown ids and paths answer 404`() {
        assertThat(harness.fetch("/stream/0123456789abcdef0123456789abcdef/index.m3u8").code).isEqualTo(404)
        assertThat(harness.fetch("/stream").code).isEqualTo(404)
        assertThat(harness.fetch("/u/0123456789abcdef0123456789abcdef/sig/aHR0cA/x").code).isEqualTo(404)
        assertThat(harness.fetch("/local/nope").code).isEqualTo(404)
        assertThat(harness.fetch("/sub/nope.vtt").code).isEqualTo(404)
        assertThat(harness.fetch("/favicon.ico").code).isEqualTo(404)
    }

    @Test
    fun `tampered or missing signatures answer 403`() {
        val stream = registerHls()
        val variant = variantPath(stream)
        assertThat(harness.fetch(variant).code).isEqualTo(200)

        val parts = variant.split('/')
        val signature = parts[3]
        val badSig = parts.toMutableList().also { it[3] = (if (signature[0] == 'A') 'B' else 'A') + signature.drop(1) }
        assertThat(harness.fetch(badSig.joinToString("/")).code).isEqualTo(403)

        val otherUrl = parts.toMutableList().also { it[4] = Base64Url.encode("https://evil.example/steal") }
        assertThat(harness.fetch(otherUrl.joinToString("/")).code).isEqualTo(403)

        assertThat(harness.fetch("/u/${stream.id}").code).isEqualTo(403)
        assertThat(harness.fetch("/b/${stream.id}/x/${Base64Url.encode("https://evil.example/")}/a.m4s").code).isEqualTo(403)
        assertThat(origin.requests.none { it.url.encodedPath.contains("steal") }).isTrue()
    }

    @Test
    fun `signatures are bound to the stream id`() {
        val first = registerHls()
        val second = harness.proxy.registerStream(ProxyStreamRequest(origin.url("/hls/master.m3u8"), emptyMap()))
        val variant = variantPath(first)
        val reused = variant.replaceFirst("/u/${first.id}/", "/u/${second.id}/")
        assertThat(harness.fetch(reused).code).isEqualTo(403)
    }

    @Test
    fun `a signature is bound to its route`() {
        val stream = registerHls()
        origin.text("/hls/account.json", "secret", "application/json")
        // The signature of one exact URL, replayed as a base, would fetch any file next to it with the user's cookies.
        val exact = variantPath(stream).split('/')
        assertThat(harness.fetch("/b/${exact[2]}/${exact[3]}/${exact[4]}/account.json").code).isEqualTo(403)
        assertThat(origin.requests.none { it.url.encodedPath == "/hls/account.json" }).isTrue()

        val base = signedBasePath("http://origin.test:${origin.port}/dash/").split('/')
        assertThat(harness.fetch("/u/${base[2]}/${base[3]}/${base[4]}/x").code).isEqualTo(403)
    }

    @Test
    fun `base routes cannot leave the signed origin`() {
        val signed = signedBasePath("http://origin.test:${origin.port}/dash/")
        origin.text("/dash/seg-1.m4s", "segment", "video/iso.segment")

        assertThat(harness.fetch(signed + "seg-1.m4s").text).isEqualTo("segment")
        // Network-path and absolute references are forced under the signed base: the origin answers its own 404.
        listOf(signed + "/evil.example/x", signed + "http:%2F%2Fevil.example/x").forEach { path ->
            val response = harness.fetch(path)
            assertThat(response.code).isEqualTo(404)
            assertThat(response.text).isEqualTo("missing")
        }
        assertThat(origin.requests.map { it.url.encodedPath }.filter { it.contains("evil") }).hasSize(2)
    }

    @Test
    fun `base routes cannot climb out of the signed directory`() {
        val signed = signedBasePath("http://origin.test:${origin.port}/dash/")
        origin.text("/dash/seg-1.m4s", "segment", "video/iso.segment")
        origin.text("/secret", "secret", "text/plain")
        origin.text("/dash/sub/seg-2.m4s", "nested", "video/iso.segment")

        // Templates keep working, nested directories included.
        assertThat(harness.fetch(signed + "seg-1.m4s").text).isEqualTo("segment")
        assertThat(harness.fetch(signed + "sub/seg-2.m4s").text).isEqualTo("nested")
        // Sent raw: OkHttp (and every receiver) removes "..", "%2E%2E" and "." segments before sending.
        listOf("%2E%2E/secret", "../secret", "sub/../../secret", "%2e%2e%2fsecret", "..%5Csecret", "..;/secret", "./seg-1.m4s")
            .forEach { rest -> assertThat(rawStatusLine(signed + rest)).contains(" 403 ") }
        assertThat(origin.requests.none { it.url.encodedPath == "/secret" }).isTrue()
    }

    /** Sends a GET with [path] exactly as given (no client-side normalization) and returns the status line. */
    private fun rawStatusLine(path: String): String = Socket("127.0.0.1", harness.proxy.port).use { socket ->
        socket.soTimeout = 10_000
        val request = "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
        socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
    }

    /** A signed base route, produced by rewriting a DASH manifest (the only producer of base routes). */
    private fun signedBasePath(baseDir: String): String {
        origin.text(
            "/dash/m.mpd",
            "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><BaseURL>$baseDir</BaseURL></MPD>",
            "application/dash+xml",
        )
        val dash = harness.proxy.registerStream(ProxyStreamRequest(origin.url("/dash/m.mpd"), emptyMap(), typeHint = StreamType.DASH))
        val text = harness.fetch(dash.path).text
        return Regex("<BaseURL>(/b/[^<]+)</BaseURL>").find(text)!!.groupValues[1]
    }

    @Test
    fun `methods other than GET, HEAD and OPTIONS are refused`() {
        val stream = registerHls()
        val response = harness.client.newCall(
            okhttp3.Request.Builder().url(harness.proxyUrl(stream.path)).delete().build(),
        ).execute().use { it.code to it.header("Allow") }
        assertThat(response.first).isEqualTo(405)
        assertThat(response.second).isEqualTo("GET, HEAD, OPTIONS")
    }

    @Test
    fun `unregister and clear remove registrations`() {
        val stream = registerHls()
        assertThat(harness.fetch(stream.path).code).isEqualTo(200)
        harness.proxy.unregister(stream.id)
        assertThat(harness.fetch(stream.path).code).isEqualTo(404)

        val again = registerHls()
        harness.proxy.clear()
        assertThat(harness.fetch(again.path).code).isEqualTo(404)
    }

    @Test
    fun `ids are random 128-bit hex values`() {
        val ids = (1..20).map { registerHls().id }
        assertThat(ids.toSet()).hasSize(20)
        ids.forEach { assertThat(it).matches("[0-9a-f]{32}") }
    }

    @Test
    fun `start is idempotent and stop releases the port`() {
        val proxy = harness.proxy
        val port = proxy.port
        assertThat(proxy.isRunning).isTrue()
        proxy.start()
        assertThat(proxy.port).isEqualTo(port)

        proxy.stop()
        assertThat(proxy.isRunning).isFalse()
        assertThat(proxy.port).isEqualTo(-1)
        assertFailsWith<IOException> { harness.client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:$port/health").build()).execute() }

        proxy.start()
        assertThat(proxy.isRunning).isTrue()
        assertThat(harness.fetch("/health").code).isEqualTo(200)
    }

    @Test
    fun `registrations survive a restart`() {
        val stream = registerHls()
        harness.proxy.stop()
        harness.proxy.start()
        assertThat(harness.fetch(stream.path).code).isEqualTo(200)
    }

    @Test
    fun `a busy port falls back to an ephemeral one`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { busy ->
            val proxy = StreamProxy(OkHttpClient(), ProxyConfig(port = busy.localPort, bindAddress = "127.0.0.1"))
            try {
                proxy.start()
                assertThat(proxy.isRunning).isTrue()
                assertThat(proxy.port).isNotEqualTo(busy.localPort)
                assertThat(proxy.port).isGreaterThan(0)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun `a busy port fails when fallback is disabled`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { busy ->
            val proxy = StreamProxy(
                OkHttpClient(),
                ProxyConfig(port = busy.localPort, bindAddress = "127.0.0.1", fallbackToEphemeralPort = false),
            )
            assertFailsWith<IOException> { proxy.start() }
            assertThat(proxy.isRunning).isFalse()
            assertThat(proxy.port).isEqualTo(-1)
        }
    }

    @Test
    fun `url formats IPv4 and bracketed IPv6 hosts`() {
        val port = harness.proxy.port
        assertThat(harness.proxy.url("192.168.1.20", "/stream/x/index.m3u8")).isEqualTo("http://192.168.1.20:$port/stream/x/index.m3u8")
        assertThat(harness.proxy.url("fe80::1", "/health")).isEqualTo("http://[fe80::1]:$port/health")
        assertThat(harness.proxy.url("[::1]", "health")).isEqualTo("http://[::1]:$port/health")
        val stopped = StreamProxy(OkHttpClient(), ProxyConfig(port = 9999))
        assertThat(stopped.url("10.0.0.2", "/x")).isEqualTo("http://10.0.0.2:9999/x")
    }

    private fun assertCors(response: ProxyTestHarness.Fetched) {
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("*")
        assertThat(response.header("Access-Control-Allow-Methods")).isEqualTo("GET, HEAD, OPTIONS")
        assertThat(response.header("Access-Control-Allow-Headers")).isEqualTo("*, Range, Content-Type, Origin, Accept")
        assertThat(response.header("Access-Control-Expose-Headers"))
            .isEqualTo("Content-Length, Content-Range, Accept-Ranges, Content-Type")
    }
}
