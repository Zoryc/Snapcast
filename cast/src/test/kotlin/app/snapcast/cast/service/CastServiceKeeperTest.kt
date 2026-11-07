package app.snapcast.cast.service

import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.RemotePlayerState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The casting service's timing on simulated clocks: casts of tens of minutes run in milliseconds. */
@OptIn(ExperimentalCoroutinesApi::class)
class CastServiceKeeperTest {
    private class Locks : ServiceLocks {
        var held = false

        override fun acquire() {
            held = true
        }

        override fun release() {
            held = false
        }
    }

    /** A cast in progress: the receiver session as the phone sees it, and the receiver's use of the proxy. */
    private class Cast(val scope: TestScope) {
        val connection = MutableStateFlow(CastConnectionState.CONNECTED)
        val player = MutableStateFlow<RemotePlayerState?>(RemotePlayerState.PLAYING)
        val endedOnPurpose = MutableStateFlow(false)
        val locks = Locks()
        val stops = mutableListOf<Pair<Long, String>>()

        /** When the receiver last fetched from the proxy (virtual ms), or null if it never did. */
        var lastFetchAt: Long? = null

        /** Fetching a response held open for the whole film (a progressive file): in use without new requests. */
        var holdingResponse = false
        var receiverPlays = false

        val keeper = CastServiceKeeper(
            scope = scope.backgroundScope,
            connectionState = connection,
            playerState = player,
            endedOnPurpose = endedOnPurpose,
            receiverIdleMillis = {
                if (holdingResponse) 0L else lastFetchAt?.let { scope.testScheduler.currentTime - it }
            },
            locks = locks,
            onStop = { reason -> stops += scope.testScheduler.currentTime to reason },
        )

        init {
            locks.acquire()
            keeper.observeLocks()
            keeper.observeStop()
        }

        /** The receiver fetches the next segment every [everyMs] while [receiverPlays]. */
        fun playFromProxy(everyMs: Long = SEGMENT_MS) {
            receiverPlays = true
            scope.backgroundScope.launch {
                while (receiverPlays) {
                    lastFetchAt = scope.testScheduler.currentTime
                    delay(everyMs)
                }
            }
        }
    }

    @Test
    fun `a proxied cast plays on after the phone loses the session, and the proxy stops once the receiver is done`() = runTest {
        val cast = Cast(this)
        cast.playFromProxy()
        advanceTimeBy(10 * MINUTE)
        // The screen is off: Android no longer sees the Chromecast, the session is detached.
        cast.connection.value = CastConnectionState.NO_DEVICES
        cast.player.value = null
        advanceTimeBy(20 * MINUTE)
        assertThat(cast.stops).isEmpty()
        assertThat(cast.locks.held).isTrue()

        // The film ends on the receiver: no more fetches.
        cast.receiverPlays = false
        val lastFetch = cast.lastFetchAt!!
        advanceTimeBy(ServiceStopPolicy.RECEIVER_IDLE_MS - SEGMENT_MS)
        assertThat(cast.stops).isEmpty()
        advanceTimeBy(2 * SEGMENT_MS)
        runCurrent()
        assertThat(cast.stops.map { it.second }).containsExactly("no session, and no receiver using the proxy")
        assertThat(cast.stops.single().first - lastFetch).isAtLeast(ServiceStopPolicy.RECEIVER_IDLE_MS)
        assertThat(cast.stops.single().first - lastFetch).isAtMost(ServiceStopPolicy.RECEIVER_IDLE_MS + SEGMENT_MS)
        assertThat(cast.locks.held).isFalse()
    }

    @Test
    fun `a suspended session that resumes never stops the service nor releases the locks`() = runTest {
        val cast = Cast(this)
        cast.playFromProxy()
        advanceTimeBy(10 * MINUTE)
        cast.connection.value = CastConnectionState.CONNECTING
        advanceTimeBy(5 * MINUTE)
        cast.connection.value = CastConnectionState.CONNECTED
        advanceTimeBy(30 * MINUTE)
        assertThat(cast.stops).isEmpty()
        assertThat(cast.locks.held).isTrue()
    }

    @Test
    fun `a progressive file streaming through one open response keeps the proxy up`() = runTest {
        val cast = Cast(this)
        cast.holdingResponse = true
        cast.connection.value = CastConnectionState.NOT_CONNECTED
        cast.player.value = null
        advanceTimeBy(60 * MINUTE)
        assertThat(cast.stops).isEmpty()
        assertThat(cast.locks.held).isTrue()
    }

    @Test
    fun `a Stop ends at once, even while the receiver was still fetching`() = runTest {
        val cast = Cast(this)
        cast.playFromProxy()
        advanceTimeBy(10 * MINUTE)
        cast.endedOnPurpose.value = true
        cast.connection.value = CastConnectionState.NOT_CONNECTED
        cast.player.value = null
        runCurrent()
        assertThat(cast.stops.map { it.second }).containsExactly("the session was ended")
    }

    @Test
    fun `a direct cast that loses its session stops after the grace period`() = runTest {
        val cast = Cast(this)
        advanceTimeBy(10 * MINUTE)
        cast.connection.value = CastConnectionState.NOT_CONNECTED
        cast.player.value = null
        advanceTimeBy(ServiceStopPolicy.GRACE_MS - 1)
        assertThat(cast.stops).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(cast.stops).hasSize(1)
    }

    @Test
    fun `nothing loaded on a connected receiver stops after the grace period`() = runTest {
        val cast = Cast(this)
        cast.playFromProxy()
        cast.player.value = RemotePlayerState.IDLE
        advanceTimeBy(ServiceStopPolicy.GRACE_MS + 1)
        runCurrent()
        assertThat(cast.stops.map { it.second }).containsExactly("nothing loaded on the receiver")
    }

    @Test
    fun `a receiver paused with an open response gets its locks released after a minute, the service stays`() = runTest {
        val cast = Cast(this)
        cast.holdingResponse = true
        cast.player.value = RemotePlayerState.PAUSED
        advanceTimeBy(WakeLockPolicy.RELEASE_DELAY_MS + 1)
        runCurrent()
        assertThat(cast.locks.held).isFalse()
        advanceTimeBy(60 * MINUTE)
        assertThat(cast.stops).isEmpty()
        cast.player.value = RemotePlayerState.PLAYING
        runCurrent()
        assertThat(cast.locks.held).isTrue()
    }

    private companion object {
        const val MINUTE = 60_000L
        const val SEGMENT_MS = 6_000L
    }
}
