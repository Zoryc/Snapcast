package app.snapcast.cast.service

import app.snapcast.cast.RemotePlayerState

/**
 * When the casting service holds its partial wake lock (and its Wi-Fi lock): only while the receiver is
 * fetching from the proxy. While playback is paused or stopped the locks are released after
 * [RELEASE_DELAY_MS], so a paused or finished session does not keep the CPU and the Wi-Fi radio awake; they
 * are taken again as soon as playback resumes. Without a session the receiver's state is unknown: they are
 * then held while it uses the proxy, released once it has not for [RECEIVER_IDLE_MS], and taken again when it
 * comes back to it.
 */
internal object WakeLockPolicy {
    enum class Decision {
        /** Playing, buffering or loading: the receiver pulls media through the proxy. */
        HOLD,

        /** Paused or idle: release unless playback resumes within [RELEASE_DELAY_MS]. */
        RELEASE_AFTER_DELAY,

        /** No connected session (lost, suspended, or none yet): hold them while the receiver uses the proxy. */
        RELEASE_WHEN_RECEIVER_IDLE,
    }

    /** Covers short pauses and the receiver buffering ahead after a pause. */
    const val RELEASE_DELAY_MS = 60_000L

    /** A playing receiver fetches from the proxy every few seconds, or keeps a response open. */
    const val RECEIVER_IDLE_MS = 120_000L

    fun decide(state: RemotePlayerState?): Decision = when (state) {
        RemotePlayerState.PLAYING,
        RemotePlayerState.BUFFERING,
        RemotePlayerState.LOADING,
        -> Decision.HOLD
        RemotePlayerState.PAUSED,
        RemotePlayerState.IDLE,
        -> Decision.RELEASE_AFTER_DELAY
        null -> Decision.RELEASE_WHEN_RECEIVER_IDLE
    }
}
