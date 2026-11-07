package app.snapcast.proxy.http

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProxyActivityTest {
    private var now = 1_000L
    private val activity = ProxyActivity { now }

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
}
