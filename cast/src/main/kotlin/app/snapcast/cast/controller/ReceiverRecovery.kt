package app.snapcast.cast.controller

import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import app.snapcast.proxy.StreamProblem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads a proxied stream the receiver failed on again where it stopped. The receiver gives up on a stream after a
 * few seconds of failed requests (a Wi-Fi hiccup on the phone, a server that hung for a moment) and never retries
 * by itself, while a new load plays again within a second. At most [DELAYS_MS].size reloads per [WINDOW_MS], each
 * after its delay, so a stream that keeps failing ends with its error. [clock] is in milliseconds. Main thread.
 */
internal class ReceiverRecovery(private val clock: () -> Long) {

    /** What a recovery works with: the receiver as the controller sees it, and the reload itself. */
    interface Host {
        val connectionState: StateFlow<CastConnectionState>
        val playback: RemotePlaybackState?

        /** Identifies the Cast session: kept through a suspension, new when a session ends or another starts. */
        val session: Any?

        /** Changes with every load sent: casts, queue appends, subtitle reloads, and [reload]. */
        val loadGeneration: Int
        val loadsInFlight: Int

        /**
         * Loads the failed item again at its last position, as exactly one load: true when the receiver took it,
         * false when it failed, null when there is nothing to load.
         */
        suspend fun reload(): Boolean?

        /** What the proxy saw go wrong with the stream just now, if anything (taken once). */
        fun takeProblem(): StreamProblem?
    }

    sealed interface Outcome {
        data object Recovered : Outcome

        /** Something else replaced the failed item (a cast, an append, a Stop, another item): nothing to report. */
        data object Replaced : Outcome

        /** The session went away, or stayed suspended for [MAX_SUSPENSION_MS]: to be looked at if it comes back. */
        data object Lost : Outcome

        /** Reloading did not help, or cannot: [problem] (null when unknown) is to be reported. */
        data class Failed(val problem: StreamProblem?) : Outcome
    }

    private val reloads = ArrayDeque<Long>()

    /**
     * Whether a failure is worth a recovery: there is a session to reload with ([connected]), the proxy saw no
     * problem a reload cannot fix ([problem]: an expired link, an unsupported encoding), the receiver used the
     * proxy just before ([receiverIdleMs], null if never: otherwise the error is old news, from a session that comes
     * back long after), and reloads are left.
     */
    fun mayRecover(connected: Boolean, problem: StreamProblem?, receiverIdleMs: Long?): Boolean {
        if (!connected || isPermanent(problem)) return false
        if (receiverIdleMs == null || receiverIdleMs > RECENT_USE_MS) return false
        return reloadsInWindow() < DELAYS_MS.size
    }

    /**
     * Reloads the stream [failedContentUrl] that failed as queue item [failedItemId] until the receiver takes it
     * or the reloads run out. Waits through a suspended session; stops as soon as something else replaced the
     * item, or the session is another one.
     */
    suspend fun recover(
        host: Host,
        failedItemId: Int?,
        failedContentUrl: String,
        firstProblem: StreamProblem?,
    ): Outcome {
        var problem = firstProblem
        var generation = host.loadGeneration
        var reloadFailed = false
        val session = host.session
        // The other items already queued: a reload's copies get new ids, so being on one of these means the
        // receiver moved on (a skip), even when its status carries no media.
        val others = host.playback?.queue.orEmpty()
            .mapNotNullTo(HashSet()) { queued -> queued.itemId.takeIf { it != failedItemId } }
        while (true) {
            val used = reloadsInWindow()
            if (used >= DELAYS_MS.size) return Outcome.Failed(problem)
            delay(DELAYS_MS[used])
            if (host.connectionState.value != CastConnectionState.CONNECTED) {
                if (!awaitConnected(host)) {
                    return if (host.loadGeneration == generation) Outcome.Lost else Outcome.Replaced
                }
                // The resumed session publishes the receiver's current status.
                delay(SETTLE_MS)
            }
            // Stopped and cast again, or moved to another device: not the receiver that failed.
            if (host.session !== session) {
                return if (host.loadGeneration == generation) Outcome.Lost else Outcome.Replaced
            }
            if (host.loadGeneration != generation || host.loadsInFlight > 0) return Outcome.Replaced
            val failed = stillFailed(host.playback, reloadFailed, failedItemId, failedContentUrl, others)
            if (!failed) return Outcome.Replaced
            reloads.addLast(clock())
            val reloaded = host.reload() ?: return Outcome.Failed(problem)
            if (host.loadGeneration != generation + 1) return Outcome.Replaced
            if (reloaded) return Outcome.Recovered
            if (host.session !== session) return Outcome.Lost
            // The outage goes on, or the link has died meanwhile.
            generation = host.loadGeneration
            reloadFailed = true
            problem = host.takeProblem() ?: problem
            if (isPermanent(problem)) return Outcome.Failed(problem)
        }
    }

    /**
     * Before a reload: the failed item, still failed. After a failed reload: the copy of the stream it left (a
     * failed load may leave no idle reason, no media, or no status at all), not one stopped, finished or replaced
     * since, nor another item the receiver moved on to.
     */
    private fun stillFailed(
        state: RemotePlaybackState?,
        reloadFailed: Boolean,
        failedItemId: Int?,
        failedContentUrl: String,
        others: Set<Int>,
    ): Boolean =
        if (reloadFailed) {
            state == null || (
                state.playerState == RemotePlayerState.IDLE && state.idleReason !in ENDED_REASONS &&
                    (state.contentUrl == null || state.contentUrl == failedContentUrl) &&
                    (state.currentItemId == null || state.currentItemId !in others)
                )
        } else {
            state != null && state.idleReason == ReceiverQueue.IDLE_REASON_ERROR && state.currentItemId == failedItemId
        }

    private suspend fun awaitConnected(host: Host): Boolean =
        withTimeoutOrNull(MAX_SUSPENSION_MS) {
            host.connectionState.first { it != CastConnectionState.CONNECTING }
        } == CastConnectionState.CONNECTED

    private fun reloadsInWindow(): Int {
        val now = clock()
        while (reloads.isNotEmpty() && now - reloads.first() >= WINDOW_MS) reloads.removeFirst()
        return reloads.size
    }

    private fun isPermanent(problem: StreamProblem?): Boolean =
        problem != null && problem.kind != StreamProblem.Kind.UNREACHABLE

    companion object {
        /** The wait before each reload within [WINDOW_MS]: a short blip is over by the first, a long one by the last. */
        val DELAYS_MS = longArrayOf(2_000L, 5_000L, 15_000L)
        const val WINDOW_MS = 10 * 60_000L
        const val RECENT_USE_MS = 2 * 60_000L
        const val MAX_SUSPENSION_MS = 2 * 60_000L
        const val SETTLE_MS = 1_000L

        /** Idle reasons of media someone ended (stopped, finished, replaced), as opposed to one that failed. */
        private val ENDED_REASONS = setOf(
            ReceiverQueue.IDLE_REASON_CANCELED,
            ReceiverQueue.IDLE_REASON_FINISHED,
            ReceiverQueue.IDLE_REASON_INTERRUPTED,
        )
    }
}
