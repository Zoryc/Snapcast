package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The proxy says when a receiver last used it: the casting service keeps it up while one does. */
class StreamProxyActivityTest {
    private val harness = ProxyTestHarness()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `requests and responses still being sent count as use, health checks do not`() {
        assertThat(harness.proxy.idleMillis()).isNull()
        assertThat(harness.fetch("/health").code).isEqualTo(200)
        assertThat(harness.proxy.idleMillis()).isNull()

        harness.origin.text("/v/master.m3u8", "#EXTM3U\n#EXTINF:4,\nseg.ts\n", "application/vnd.apple.mpegurl")
        val stream = harness.proxy.registerStream(ProxyStreamRequest(harness.origin.url("/v/master.m3u8"), emptyMap(), emptyMap(), StreamType.HLS))
        harness.fetch(stream.path)
        val afterRequest = harness.proxy.idleMillis()!!
        assertThat(afterRequest).isAtLeast(0L)
        assertThat(afterRequest).isLessThan(5_000L)
        Thread.sleep(50)
        assertThat(harness.proxy.idleMillis()!!).isAtLeast(40L)
    }

    @Test
    fun `a stopped and restarted proxy serves the streams it had, at the same URLs`() {
        harness.origin.text("/v/master.m3u8", "#EXTM3U\n#EXTINF:4,\nseg.ts\n", "application/vnd.apple.mpegurl")
        // The app's proxy listens on a fixed port (8642 by default), not an ephemeral one.
        val port = ServerSocket(0).use { it.localPort }
        val proxy = StreamProxy(harness.upstreamClient, ProxyConfig(port = port, bindAddress = "127.0.0.1", fallbackToEphemeralPort = false))
        fun fetch(path: String) = harness.client.newCall(Request("http://127.0.0.1:$port$path".toHttpUrl())).execute().use { it.code to it.body.string() }
        try {
            proxy.start()
            val stream = proxy.registerStream(ProxyStreamRequest(harness.origin.url("/v/master.m3u8"), emptyMap(), emptyMap(), StreamType.HLS))
            val first = fetch(stream.path)
            assertThat(first.first).isEqualTo(200)

            proxy.stop()
            proxy.start()
            // Connections to the stopped server are dead; a receiver (Chromium) retries on a fresh one.
            harness.client.connectionPool.evictAll()

            assertThat(proxy.port).isEqualTo(port)
            assertThat(fetch(stream.path)).isEqualTo(first)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `a progressive file still streaming keeps the proxy in use while the receiver reads nothing`() {
        // A body the upstream sends slowly: the response stays open, as when a receiver's buffer is full.
        val release = CountDownLatch(1)
        harness.origin.on("/movie.mp4") {
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "video/mp4")
                .body(Buffer().write(ByteArray(64 * 1024)))
                .throttleBody(1024, 200, TimeUnit.MILLISECONDS)
                .build()
        }
        val stream = harness.proxy.registerStream(ProxyStreamRequest(harness.origin.url("/movie.mp4"), emptyMap(), emptyMap(), StreamType.MP4))
        val reader = thread {
            harness.client.newCall(Request(harness.proxyUrl(stream.path).toHttpUrl())).execute().use { response ->
                val source = response.body.source()
                source.request(1)
                release.await(10, TimeUnit.SECONDS)
            }
        }
        Thread.sleep(600)
        assertThat(harness.proxy.idleMillis()).isEqualTo(0L)
        release.countDown()
        reader.join(15_000)
    }

    @Test
    fun `a proxy that had to fall back to another port gets that port back when restarted`() {
        harness.origin.text("/v/master.m3u8", "#EXTM3U\n#EXTINF:4,\nseg.ts\n", "application/vnd.apple.mpegurl")
        ServerSocket(0).use { taken ->
            val proxy = StreamProxy(harness.upstreamClient, ProxyConfig(port = taken.localPort, bindAddress = "127.0.0.1"))
            try {
                proxy.start()
                val fallback = proxy.port
                assertThat(fallback).isNotEqualTo(taken.localPort)
                val stream = proxy.registerStream(ProxyStreamRequest(harness.origin.url("/v/master.m3u8"), emptyMap(), emptyMap(), StreamType.HLS))

                proxy.stop()
                proxy.start()
                harness.client.connectionPool.evictAll()

                // The receiver's URLs name the fallback port.
                assertThat(proxy.port).isEqualTo(fallback)
                val code = harness.client.newCall(Request("http://127.0.0.1:$fallback${stream.path}".toHttpUrl())).execute().use { it.code }
                assertThat(code).isEqualTo(200)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun `a receiver coming back after a minute or more is reported, health checks are not`() {
        val comebacks = AtomicInteger()
        var now = 1_000_000L
        val proxy = StreamProxy(
            harness.upstreamClient,
            ProxyConfig(port = 0, bindAddress = "127.0.0.1"),
            onReceiverActive = { comebacks.incrementAndGet() },
            activityClock = { now },
        )
        harness.origin.text("/v/master.m3u8", "#EXTM3U\n#EXTINF:4,\nseg.ts\n", "application/vnd.apple.mpegurl")
        fun fetch(path: String) = harness.client.newCall(Request("http://127.0.0.1:${proxy.port}$path".toHttpUrl())).execute().use { it.code }
        try {
            proxy.start()
            assertThat(fetch("/health")).isEqualTo(200)
            assertThat(comebacks.get()).isEqualTo(0)
            val stream = proxy.registerStream(ProxyStreamRequest(harness.origin.url("/v/master.m3u8"), emptyMap(), emptyMap(), StreamType.HLS))
            fetch(stream.path)
            assertThat(comebacks.get()).isEqualTo(1)
            now += 6_000L
            fetch(stream.path)
            assertThat(comebacks.get()).isEqualTo(1)
            now += 3 * 60_000L
            fetch(stream.path)
            assertThat(comebacks.get()).isEqualTo(2)
        } finally {
            proxy.stop()
        }
    }
}
