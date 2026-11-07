package app.snapcast.proxy

import app.snapcast.core.model.StreamType
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
}
