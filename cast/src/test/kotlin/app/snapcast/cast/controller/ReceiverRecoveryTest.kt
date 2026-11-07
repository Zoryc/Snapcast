package app.snapcast.cast.controller

import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import app.snapcast.proxy.StreamProblem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Recovery of a failed proxied stream, on a simulated clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverRecoveryTest {

    private fun state(
        itemId: Int?,
        player: RemotePlayerState = RemotePlayerState.IDLE,
        idleReason: String? = ERROR,
        contentUrl: String? = FILM,
    ) =
        RemotePlaybackState(
            title = "Film", subtitle = null, posterUrl = null, playerState = player, positionMs = 600_000L,
            durationMs = 6_000_000L, isLive = false, isSeekable = true, volume = 1.0, isMuted = false,
            playbackRate = 1.0, supportsPlaybackRate = true, textTracks = emptyList(), activeTextTrackId = null,
            queue = emptyList(), currentItemId = itemId,
            idleReason = if (player == RemotePlayerState.IDLE) idleReason else null,
            originalUrl = "https://cdn.test/film.m3u8", contentUrl = contentUrl,
        )

    /** The controller and the receiver: item 1 failed; each reload is one load, answered from [results]. */
    private class Receiver(val scope: TestScope) : ReceiverRecovery.Host {
        override val connectionState = MutableStateFlow(CastConnectionState.CONNECTED)
        override var playback: RemotePlaybackState? = null
        override var session: Any? = Any()
        override var loadGeneration = 7
        override var loadsInFlight = 0
        val reloadsAt = mutableListOf<Long>()
        val results = ArrayDeque<Boolean?>()
        val problems = ArrayDeque<StreamProblem?>()
        var duringReload: () -> Unit = {}
        var afterFailedReload: (itemId: Int) -> RemotePlaybackState? = { null }

        override suspend fun reload(): Boolean? {
            loadGeneration++
            reloadsAt += scope.testScheduler.currentTime
            duringReload()
            val result = if (results.isEmpty()) true else results.removeFirst()
            if (result == false) playback = afterFailedReload(100 + reloadsAt.size)
            return result
        }

        override fun takeProblem(): StreamProblem? = problems.removeFirstOrNull()
    }

    private fun TestScope.setUp(): Pair<ReceiverRecovery, Receiver> {
        val receiver = Receiver(this)
        receiver.playback = state(itemId = 1)
        return ReceiverRecovery { testScheduler.currentTime } to receiver
    }

    private fun problem(kind: StreamProblem.Kind) = StreamProblem("abc", kind, kind.name, 0L)

    @Test
    fun `a failed stream is reloaded after two seconds`() = runTest {
        val (recovery, receiver) = setUp()
        val outcome = recovery.recover(receiver, failedItemId = 1, failedContentUrl = FILM, firstProblem = null)
        assertThat(outcome).isEqualTo(ReceiverRecovery.Outcome.Recovered)
        assertThat(receiver.reloadsAt).containsExactly(2_000L)
    }

    @Test
    fun `a reload that fails is tried again after 5 and 15 s, then the error is reported, and the reloads come back after ten minutes`() = runTest {
        val (recovery, receiver) = setUp()
        repeat(3) { receiver.results.addLast(false) }
        val unreachable = problem(StreamProblem.Kind.UNREACHABLE)
        receiver.problems.addLast(unreachable)
        val outcome = recovery.recover(receiver, failedItemId = 1, failedContentUrl = FILM, firstProblem = null)
        assertThat(outcome).isEqualTo(ReceiverRecovery.Outcome.Failed(unreachable))
        assertThat(receiver.reloadsAt).containsExactly(2_000L, 7_000L, 22_000L).inOrder()
        assertThat(recovery.mayRecover(connected = true, problem = null, receiverIdleMs = 0L)).isFalse()
        advanceTimeBy(ReceiverRecovery.WINDOW_MS)
        assertThat(recovery.mayRecover(connected = true, problem = null, receiverIdleMs = 0L)).isTrue()
    }

    @Test
    fun `an expired link found by a failed reload ends at once with that explanation`() = runTest {
        val (recovery, receiver) = setUp()
        receiver.results.addLast(false)
        val expired = problem(StreamProblem.Kind.NOT_A_PLAYLIST)
        receiver.problems.addLast(expired)
        assertThat(recovery.recover(receiver, 1, FILM, null)).isEqualTo(ReceiverRecovery.Outcome.Failed(expired))
        assertThat(receiver.reloadsAt).hasSize(1)
    }

    @Test
    fun `a session suspended during the wait is waited for, and the wait costs no attempt`() = runTest {
        val (recovery, receiver) = setUp()
        val result = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(1_000L)
        receiver.connectionState.value = CastConnectionState.CONNECTING
        advanceTimeBy(30_000L)
        assertThat(receiver.reloadsAt).isEmpty()
        receiver.connectionState.value = CastConnectionState.CONNECTED
        runCurrent()
        advanceTimeBy(ReceiverRecovery.SETTLE_MS + 1)
        assertThat(result.await()).isEqualTo(ReceiverRecovery.Outcome.Recovered)
        assertThat(receiver.reloadsAt).containsExactly(31_000L + ReceiverRecovery.SETTLE_MS)

        // Only the reload counted: the next recovery waits 5 s, then 15 s, then the reloads run out.
        receiver.playback = state(itemId = 1)
        repeat(2) { receiver.results.addLast(false) }
        receiver.afterFailedReload = { id -> state(itemId = id) }
        val start = testScheduler.currentTime
        assertThat(recovery.recover(receiver, 1, FILM, null)).isEqualTo(ReceiverRecovery.Outcome.Failed(null))
        assertThat(receiver.reloadsAt).containsExactly(32_000L, start + 5_000L, start + 20_000L).inOrder()
    }

    @Test
    fun `a session suspended after a failed reload is waited for, and the failed item it left is reloaded`() = runTest {
        val (recovery, receiver) = setUp()
        receiver.results.addLast(false)
        // The failed load leaves an idle item without a reason.
        receiver.afterFailedReload = { id -> state(itemId = id, idleReason = null) }
        receiver.duringReload = { receiver.connectionState.value = CastConnectionState.CONNECTING }
        val result = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(60_000L)
        receiver.connectionState.value = CastConnectionState.CONNECTED
        assertThat(result.await()).isEqualTo(ReceiverRecovery.Outcome.Recovered)
        assertThat(receiver.reloadsAt).hasSize(2)
    }

    @Test
    fun `a session lost, or suspended for more than two minutes, ends the recovery without a reload`() = runTest {
        val (recovery, receiver) = setUp()
        val lost = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(1_000L)
        receiver.connectionState.value = CastConnectionState.NOT_CONNECTED
        assertThat(lost.await()).isEqualTo(ReceiverRecovery.Outcome.Lost)

        receiver.connectionState.value = CastConnectionState.CONNECTING
        val suspended = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(ReceiverRecovery.MAX_SUSPENSION_MS + 10_000L)
        assertThat(suspended.await()).isEqualTo(ReceiverRecovery.Outcome.Lost)
        assertThat(receiver.reloadsAt).isEmpty()

        // Neither abandoned wait used an attempt: all three are left.
        receiver.connectionState.value = CastConnectionState.CONNECTED
        repeat(3) { receiver.results.addLast(false) }
        receiver.afterFailedReload = { id -> state(itemId = id) }
        val start = testScheduler.currentTime
        assertThat(recovery.recover(receiver, 1, FILM, null)).isEqualTo(ReceiverRecovery.Outcome.Failed(null))
        assertThat(receiver.reloadsAt).containsExactly(start + 2_000L, start + 7_000L, start + 22_000L).inOrder()
    }

    @Test
    fun `stopping and casting again, or moving to another device, during the wait ends the recovery`() = runTest {
        val (recovery, receiver) = setUp()
        receiver.results.addLast(false)
        val result = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(3_000L)
        assertThat(receiver.reloadsAt).hasSize(1)
        // A new session, on the same device or another one, with nothing loaded.
        receiver.connectionState.value = CastConnectionState.NOT_CONNECTED
        receiver.session = Any()
        receiver.playback = null
        receiver.connectionState.value = CastConnectionState.CONNECTED
        assertThat(result.await()).isEqualTo(ReceiverRecovery.Outcome.Lost)
        assertThat(receiver.reloadsAt).hasSize(1)
    }

    @Test
    fun `after a failed reload, another stream the receiver moved on to is not reloaded over`() = runTest {
        val (recovery, receiver) = setUp()
        receiver.results.addLast(false)
        receiver.afterFailedReload = { id -> state(itemId = id) }
        val result = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(3_000L)
        // Skipped to the next item, which fails too.
        receiver.playback = state(itemId = 2, contentUrl = "http://192.168.1.5:8642/stream/def/other.m3u8")
        assertThat(result.await()).isEqualTo(ReceiverRecovery.Outcome.Replaced)
        assertThat(receiver.reloadsAt).hasSize(1)
    }

    @Test
    fun `a cast, an append or a subtitle reload during the wait ends the recovery quietly`() = runTest {
        val (recovery, receiver) = setUp()
        val result = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(1_000L)
        receiver.loadGeneration++
        assertThat(result.await()).isEqualTo(ReceiverRecovery.Outcome.Replaced)
        assertThat(receiver.reloadsAt).isEmpty()

        // One still in flight when the wait ends.
        receiver.loadsInFlight = 1
        assertThat(recovery.recover(receiver, 1, FILM, null)).isEqualTo(ReceiverRecovery.Outcome.Replaced)
        assertThat(receiver.reloadsAt).isEmpty()
    }

    @Test
    fun `a load started while the reload was in flight wins, failed or not`() = runTest {
        val (recovery, receiver) = setUp()
        receiver.results.addLast(false)
        receiver.duringReload = { receiver.loadGeneration++ }
        assertThat(recovery.recover(receiver, 1, FILM, null)).isEqualTo(ReceiverRecovery.Outcome.Replaced)
        assertThat(receiver.reloadsAt).hasSize(1)
    }

    @Test
    fun `the receiver moving on, or a stop after a failed reload, ends the recovery quietly`() = runTest {
        val (recovery, receiver) = setUp()
        val result = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(1_000L)
        receiver.playback = state(itemId = 2, player = RemotePlayerState.PLAYING)
        assertThat(result.await()).isEqualTo(ReceiverRecovery.Outcome.Replaced)

        receiver.playback = state(itemId = 1)
        receiver.results.addLast(false)
        receiver.afterFailedReload = { id -> state(itemId = id) }
        val second = async { recovery.recover(receiver, 1, FILM, null) }
        advanceTimeBy(3_000L)
        assertThat(receiver.reloadsAt).hasSize(1)
        // Stopped (from the TV, another phone) before the next reload.
        receiver.playback = state(itemId = 101, idleReason = ReceiverQueue.IDLE_REASON_CANCELED)
        assertThat(second.await()).isEqualTo(ReceiverRecovery.Outcome.Replaced)
        assertThat(receiver.reloadsAt).hasSize(1)
    }

    @Test
    fun `nothing to load is reported at once`() = runTest {
        val (recovery, receiver) = setUp()
        receiver.results.addLast(null)
        assertThat(recovery.recover(receiver, 1, FILM, null)).isEqualTo(ReceiverRecovery.Outcome.Failed(null))
    }

    @Test
    fun `no recovery without a session, for a problem a reload cannot fix, or for an error that is old news`() = runTest {
        val (recovery, _) = setUp()
        assertThat(recovery.mayRecover(connected = false, problem = null, receiverIdleMs = 0L)).isFalse()
        assertThat(recovery.mayRecover(true, problem(StreamProblem.Kind.NOT_A_PLAYLIST), 0L)).isFalse()
        assertThat(recovery.mayRecover(true, problem(StreamProblem.Kind.UNSUPPORTED_ENCODING), 0L)).isFalse()
        assertThat(recovery.mayRecover(true, null, receiverIdleMs = null)).isFalse()
        assertThat(recovery.mayRecover(true, null, ReceiverRecovery.RECENT_USE_MS + 1)).isFalse()
        assertThat(recovery.mayRecover(true, problem(StreamProblem.Kind.UNREACHABLE), 3_000L)).isTrue()
    }

    private companion object {
        const val ERROR = ReceiverQueue.IDLE_REASON_ERROR
        const val FILM = "http://192.168.1.5:8642/stream/abc/film.m3u8"
    }
}
