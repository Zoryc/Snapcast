package app.snapcast.proxy.http

import java.util.concurrent.atomic.AtomicInteger

/**
 * When a receiver last used the proxy: a request being answered or a response still being sent (a progressive
 * file streams through one response for the whole film, idle while the receiver's buffer is full) counts as in
 * use, then the time since the last one ended. A response that has not [progressed] for [STALLED_MS] (a
 * receiver that vanished without closing, its connection stuck until the kernel gives up) stops counting.
 * Every [begin] is matched by an [end]. Thread-safe.
 */
internal class ProxyActivity(private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }) {
    private val busy = AtomicInteger()

    @Volatile
    private var lastAt = 0L

    @Volatile
    private var used = false

    fun begin() {
        busy.incrementAndGet()
        touch()
    }

    fun end() {
        busy.decrementAndGet()
        touch()
    }

    /** Bytes went out to a receiver. */
    fun progressed() = touch()

    /** Milliseconds since the proxy was last used: 0 while it answers a request, null if it never did. */
    fun idleMillis(): Long? {
        if (!used) return null
        val idle = (clock() - lastAt).coerceAtLeast(0L)
        return if (busy.get() > 0 && idle < STALLED_MS) 0L else idle
    }

    private fun touch() {
        lastAt = clock()
        used = true
    }

    companion object {
        const val STALLED_MS = 10 * 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
