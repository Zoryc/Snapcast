package app.snapcast.cast.service

import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.RemotePlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The casting service's Wi-Fi and wake locks. */
internal interface ServiceLocks {
    fun acquire()

    fun release()
}

/**
 * When the casting service holds its locks ([WakeLockPolicy]) and when it stops ([ServiceStopPolicy]), from the
 * receiver's session ([connectionState], [playerState]: null while nothing is known to be loaded,
 * [endedOnPurpose]) and from its use of the proxy ([receiverIdleMillis], see ProxyManager.receiverIdleMillis).
 * No Android types, so the timing is tested on its own; runs on [scope] (the service's main-thread scope).
 */
internal class CastServiceKeeper(
    private val scope: CoroutineScope,
    private val connectionState: Flow<CastConnectionState>,
    private val playerState: Flow<RemotePlayerState?>,
    private val endedOnPurpose: Flow<Boolean>,
    private val receiverIdleMillis: () -> Long?,
    private val locks: ServiceLocks,
    private val onStop: (reason: String) -> Unit,
) {
    /** The receiver's last known state, kept when the session (and with it the state) is lost. */
    private var lastKnown: RemotePlayerState? = null

    /** Holds the locks while the receiver plays; releases them a while after it pauses, stops or is lost. */
    fun observeLocks(): Job = scope.launch {
        playerState
            .map(WakeLockPolicy::decide)
            .distinctUntilChanged()
            .collectLatest { decision ->
                when (decision) {
                    WakeLockPolicy.Decision.HOLD -> locks.acquire()
                    WakeLockPolicy.Decision.RELEASE_AFTER_DELAY -> {
                        delay(WakeLockPolicy.RELEASE_DELAY_MS)
                        locks.release()
                    }
                    WakeLockPolicy.Decision.RELEASE_WHEN_RECEIVER_IDLE -> {
                        delay(WakeLockPolicy.RELEASE_DELAY_MS)
                        awaitReceiverIdle(ServiceStopPolicy.RECEIVER_IDLE_MS)
                        locks.release()
                    }
                }
            }
    }

    /** Calls [onStop] when the service should stop; each new decision replaces the pending one. */
    fun observeStop(): Job = scope.launch {
        combine(connectionState, playerState, endedOnPurpose) { state, player, onPurpose ->
            if (player != null) lastKnown = player
            ServiceStopPolicy.decide(state, player != null && player != RemotePlayerState.IDLE, onPurpose)
        }
            .distinctUntilChanged()
            .collectLatest { decision ->
                when (decision) {
                    ServiceStopPolicy.Decision.KEEP -> Unit
                    ServiceStopPolicy.Decision.STOP_NOW -> onStop("the session was ended")
                    ServiceStopPolicy.Decision.STOP_AFTER_GRACE -> {
                        delay(ServiceStopPolicy.GRACE_MS)
                        onStop("nothing loaded on the receiver")
                    }
                    ServiceStopPolicy.Decision.STOP_WHEN_RECEIVER_IDLE -> {
                        delay(ServiceStopPolicy.GRACE_MS)
                        val paused = lastKnown == RemotePlayerState.PAUSED
                        awaitReceiverIdle(if (paused) ServiceStopPolicy.PAUSED_RECEIVER_IDLE_MS else ServiceStopPolicy.RECEIVER_IDLE_MS)
                        onStop("no session, and no receiver using the proxy")
                    }
                }
            }
    }

    /**
     * Returns once no receiver has used the proxy for [idleMs] (at once if none ever did). A receiver that lost
     * its session with the phone goes on playing from the proxy, which must stay up.
     */
    private suspend fun awaitReceiverIdle(idleMs: Long) {
        while (true) {
            val idle = receiverIdleMillis() ?: return
            if (idle >= idleMs) return
            delay(idleMs - idle)
        }
    }
}
