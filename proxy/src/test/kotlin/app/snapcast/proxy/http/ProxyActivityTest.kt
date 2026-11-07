package app.snapcast.proxy.http

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProxyActivityTest {
    private var now = 1_000L
    private var comebacks = 0
    private val activity = ProxyActivity(clock = { now }, onActive = { comebacks++ })

    @Test
    fun `idle time counts from the end of the last request, and is zero while one is answered`() {
        assertThat(activity.idleMillis()).isNull()
        activity.begin()
        now += 90_000
        assertThat(activity.idleMillis()).isEqualTo(0L)
        activity.end()
        now += 5_000
        assertThat(activity.idleMillis()).isEqualTo(5_000L)
    }

    @Test
    fun `a response that stopped moving for ten minutes no longer counts as use`() {
        activity.begin()
        now += 9 * 60_000L
        activity.progressed()
        now += 9 * 60_000L
        assertThat(activity.idleMillis()).isEqualTo(0L)
        now += 2 * 60_000L
        assertThat(activity.idleMillis()).isEqualTo(11 * 60_000L)
        activity.end()
        assertThat(activity.idleMillis()).isEqualTo(0L)
    }

    @Test
    fun `bytes moving again on a response that had stopped for a while count as coming back`() {
        activity.begin()
        assertThat(comebacks).isEqualTo(1)
        now += 1_000
        activity.progressed()
        assertThat(comebacks).isEqualTo(1)
        // Paused on the TV with a progressive file: the response sends nothing for a quarter of an hour.
        now += 15 * 60_000L
        assertThat(activity.idleMillis()).isEqualTo(15 * 60_000L)
        activity.progressed()
        assertThat(comebacks).isEqualTo(2)
        assertThat(activity.idleMillis()).isEqualTo(0L)
    }
}
