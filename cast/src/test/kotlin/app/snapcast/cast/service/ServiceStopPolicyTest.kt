package app.snapcast.cast.service

import app.snapcast.cast.CastConnectionState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServiceStopPolicyTest {

    @Test
    fun `keeps running while media is loaded on a connected receiver`() {
        assertThat(ServiceStopPolicy.decide(CastConnectionState.CONNECTED, mediaLoaded = true, endedOnPurpose = false))
            .isEqualTo(ServiceStopPolicy.Decision.KEEP)
    }

    @Test
    fun `waits when a connected receiver has nothing loaded`() {
        assertThat(ServiceStopPolicy.decide(CastConnectionState.CONNECTED, mediaLoaded = false, endedOnPurpose = false))
            .isEqualTo(ServiceStopPolicy.Decision.STOP_AFTER_GRACE)
    }

    @Test
    fun `a lost or suspended session keeps the proxy up while the receiver still uses it`() {
        for (state in CastConnectionState.entries.filter { it != CastConnectionState.CONNECTED }) {
            assertThat(ServiceStopPolicy.decide(state, mediaLoaded = true, endedOnPurpose = false))
                .isEqualTo(ServiceStopPolicy.Decision.STOP_WHEN_RECEIVER_IDLE)
        }
        assertThat(ServiceStopPolicy.RECEIVER_IDLE_MS).isEqualTo(30 * 60_000L)
    }

    @Test
    fun `stops at once when the session was ended on purpose`() {
        for (state in listOf(CastConnectionState.NOT_CONNECTED, CastConnectionState.NO_DEVICES, CastConnectionState.UNAVAILABLE)) {
            assertThat(ServiceStopPolicy.decide(state, mediaLoaded = false, endedOnPurpose = true))
                .isEqualTo(ServiceStopPolicy.Decision.STOP_NOW)
        }
        // Reconnecting after a Stop is a new session starting: the grace period applies.
        assertThat(ServiceStopPolicy.decide(CastConnectionState.CONNECTING, mediaLoaded = false, endedOnPurpose = true))
            .isEqualTo(ServiceStopPolicy.Decision.STOP_WHEN_RECEIVER_IDLE)
    }
}
