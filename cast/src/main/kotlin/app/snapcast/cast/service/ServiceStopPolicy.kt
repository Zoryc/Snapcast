package app.snapcast.cast.service

import app.snapcast.cast.CastConnectionState

/**
 * When the casting service may stop. While the phone has no session with the receiver (suspended, lost to a
 * network error, the device out of sight with the screen off), the receiver may go on playing, or be paused and
 * resumed from the TV: from the proxy, which stops with the service, so it then stops only once no receiver has
 * used the proxy for [RECEIVER_IDLE_MS]. A session ended on purpose stops it at once.
 */
internal object ServiceStopPolicy {
    enum class Decision {
        /** Something is loaded on a connected receiver. */
        KEEP,

        /** Connected with nothing loaded: stop unless this changes within the grace period. */
        STOP_AFTER_GRACE,

        /** No session: after the grace period, stop once the receiver has stopped using the proxy. */
        STOP_WHEN_RECEIVER_IDLE,

        /** The session was ended on purpose. */
        STOP_NOW,
    }

    const val GRACE_MS = 30_000L

    /**
     * A playing receiver fetches from the proxy every few seconds, or keeps a response open; a paused one
     * fetches nothing until it resumes, and the phone cannot tell the two apart without a session.
     */
    const val RECEIVER_IDLE_MS = 30 * 60_000L

    fun decide(state: CastConnectionState, mediaLoaded: Boolean, endedOnPurpose: Boolean): Decision = when (state) {
        CastConnectionState.CONNECTED -> if (mediaLoaded) Decision.KEEP else Decision.STOP_AFTER_GRACE
        CastConnectionState.CONNECTING -> Decision.STOP_WHEN_RECEIVER_IDLE
        CastConnectionState.NOT_CONNECTED,
        CastConnectionState.NO_DEVICES,
        CastConnectionState.UNAVAILABLE,
        -> if (endedOnPurpose) Decision.STOP_NOW else Decision.STOP_WHEN_RECEIVER_IDLE
    }
}
