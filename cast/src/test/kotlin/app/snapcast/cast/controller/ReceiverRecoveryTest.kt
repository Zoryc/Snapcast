package app.snapcast.cast.controller

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReceiverRecoveryTest {
    private var now = 0L
    private val recovery = ReceiverRecovery { now }

    private fun retry(
        proxied: Boolean = true,
        connected: Boolean = true,
        permanentProblem: Boolean = false,
        receiverIdleMs: Long? = 3_000L,
    ) = recovery.retryDelay(proxied, connected, permanentProblem, receiverIdleMs)

    @Test
    fun `a proxied stream that failed while the receiver was using it is reloaded, a few times, waiting longer each time`() {
        assertThat(retry()).isEqualTo(2_000L)
        now += 30_000L
        assertThat(retry()).isEqualTo(5_000L)
        now += 30_000L
        assertThat(retry()).isEqualTo(15_000L)
        now += 30_000L
        assertThat(retry()).isNull()
    }

    @Test
    fun `the attempts come back once the earlier ones are ten minutes old`() {
        repeat(3) { assertThat(retry()).isNotNull() }
        assertThat(retry()).isNull()
        now += ReceiverRecovery.WINDOW_MS
        assertThat(retry()).isEqualTo(2_000L)
    }

    @Test
    fun `nothing is reloaded for a direct cast, without a session, or for a problem a reload cannot fix`() {
        assertThat(retry(proxied = false)).isNull()
        assertThat(retry(connected = false)).isNull()
        assertThat(retry(permanentProblem = true)).isNull()
        // None of these used an attempt.
        assertThat(retry()).isEqualTo(2_000L)
    }

    @Test
    fun `an error found when a session comes back long after is reported, not replayed`() {
        assertThat(retry(receiverIdleMs = null)).isNull()
        assertThat(retry(receiverIdleMs = ReceiverRecovery.RECENT_USE_MS + 1)).isNull()
        assertThat(retry(receiverIdleMs = 0L)).isEqualTo(2_000L)
    }
}
