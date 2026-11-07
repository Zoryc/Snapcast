package app.snapcast.cast.controller

/**
 * When a proxied cast that failed on the receiver is loaded again where it stopped. The receiver gives up on a
 * stream after a few seconds of failed requests (a Wi-Fi hiccup on the phone, a server that hung for a moment)
 * and never retries by itself, while a new load plays again within a second. Bounded, so a stream that keeps
 * failing ends with its error. [clock] is in milliseconds.
 */
internal class ReceiverRecovery(private val clock: () -> Long) {
    private val attempts = ArrayDeque<Long>()

    /**
     * How long to wait before reloading, or null when the error is to be reported instead: the media is not
     * proxied ([proxied]), there is no session to reload with ([connected]), the proxy saw a problem a reload
     * cannot fix ([permanentProblem]: an expired link, an unsupported encoding), the receiver had not used the
     * proxy just before ([receiverIdleMs], null if never: the error is old news, from a session that comes back
     * long after), or the attempts are used up. Counts the attempt when it returns a delay.
     */
    fun retryDelay(proxied: Boolean, connected: Boolean, permanentProblem: Boolean, receiverIdleMs: Long?): Long? {
        if (!proxied || !connected || permanentProblem) return null
        if (receiverIdleMs == null || receiverIdleMs > RECENT_USE_MS) return null
        val now = clock()
        while (attempts.isNotEmpty() && now - attempts.first() >= WINDOW_MS) attempts.removeFirst()
        if (attempts.size >= DELAYS_MS.size) return null
        val delay = DELAYS_MS[attempts.size]
        attempts.addLast(now)
        return delay
    }

    companion object {
        /** One delay per attempt within [WINDOW_MS]: a short blip is over by the first, a longer one by the last. */
        val DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L)
        const val WINDOW_MS = 10 * 60_000L
        const val RECENT_USE_MS = 2 * 60_000L
    }
}
