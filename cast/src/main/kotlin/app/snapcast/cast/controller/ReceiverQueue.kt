package app.snapcast.cast.controller

import app.snapcast.cast.RemotePlaybackState

/** Pure decisions about the queue on the receiver. */
internal object ReceiverQueue {
    const val IDLE_REASON_FINISHED = "FINISHED"
    const val IDLE_REASON_CANCELED = "CANCELED"
    const val IDLE_REASON_ERROR = "ERROR"
    const val IDLE_REASON_INTERRUPTED = "INTERRUPTED"

    /** True when the receiver's queue is over, so an appended item would never play ([RemotePlaybackState.isEnded]). */
    fun isEnded(state: RemotePlaybackState): Boolean = state.isEnded
}
