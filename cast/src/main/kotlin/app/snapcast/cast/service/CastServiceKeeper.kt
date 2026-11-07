package app.snapcast.cast.service

import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.RemotePlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The casting service's Wi-Fi and wake locks. */
internal interface ServiceLocks {
    fun acquire()

    fun release()

    /** Renews the time limit of the locks while they are held. */
    fun refresh()
}

/**
 * When the casting service holds its locks ([WakeLockPolicy]) and when it stops ([ServiceStopPolicy]), from the
 * receiver's session ([connectionState], [playerState]: null while nothing is known to be loaded,
 * [endedOnPurpose]) and from its use of the proxy ([receiverIdleMillis], see ProxyManager.receiverIdleMillis;
 * [receiverActive] emits when a receiver uses the proxy again after a minute or more without, not replaying
 * earlier ones).
 * No Android types, so the timing is tested on its own; runs on [scope] (the service's main-thread scope).
 */
internal class CastServiceKeeper(
    private val scope: CoroutineScope,
    private val connectionState: Flow<CastConnectionState>,
    private val playerState: Flow<RemotePlayerState?>,
    private val endedOnPurpose: Flow<Boolean>,
    private val receiverIdleMillis: () -> Long?,
    private val receiverActive: Flow<*>,
    private val locks: ServiceLocks,
    private val onStop: (reason: String) -> Unit,
) {
    /** Holds the locks while the receiver plays; releases them a while after it pauses, stops or is lost. */
    fun observeLocks(): Job = scope.launch {
        // A state seen through a suspended session is stale (the receiver may have been paused or resumed from
        // the TV since): only a connected session's state is trusted.
        combine(connectionState, playerState) { connection, player ->
            if (connection == CastConnectionState.CONNECTED) player else null
        }
            .map(WakeLockPolicy::decide)
            .distinctUntilChanged()
            .collectLatest { decision ->
                when (decision) {
                    WakeLockPolicy.Decision.HOLD -> {
                        locks.acquire()
                        renewing { awaitCancellation() }
                    }
                    WakeLockPolicy.Decision.RELEASE_AFTER_DELAY -> {
                        delay(WakeLockPolicy.RELEASE_DELAY_MS)
                        locks.release()
                    }
                    WakeLockPolicy.Decision.RELEASE_WHEN_RECEIVER_IDLE -> {
                        // The receiver's state is unknown: follow its use of the proxy, which goes on (or resumes
                        // after a pause on the TV) without the phone being told. Event-driven once released: a
                        // delay would not run while the phone sleeps.
                        while (true) {
                            if (receiverIdleMillis()?.let { it < WakeLockPolicy.RECEIVER_IDLE_MS } == true) {
                                locks.acquire()
                                renewing { awaitReceiverIdle(WakeLockPolicy.RECEIVER_IDLE_MS) }
                            }
                            locks.release()
                            receiverActive.first()
                        }
                    }
                }
            }
    }

    /** Calls [onStop] when the service should stop; each new decision replaces the pending one. */
    fun observeStop(): Job = scope.launch {
        combine(connectionState, playerState, endedOnPurpose) { state, player, onPurpose ->
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
                        awaitReceiverIdle(ServiceStopPolicy.RECEIVER_IDLE_MS)
                        onStop("no session, and no receiver using the proxy")
                    }
                }
            }
    }

    /** Runs [block], renewing the locks' time limit meanwhile (a cast can outlast it). */
    private suspend fun <T> renewing(block: suspend () -> T): T = coroutineScope {
        val renewal = launch {
            while (true) {
                delay(LOCK_RENEWAL_MS)
                locks.refresh()
            }
        }
        try {
            block()
        } finally {
            renewal.cancel()
        }
    }

    /**
     * Returns once no receiver has used the proxy for [idleMs] (at once if none ever did). The idle time is
     * wall-clock time, while delays stop with the phone asleep: it is checked at least every [IDLE_CHECK_MS] of
     * awake time, so a wait that ran out during sleep ends soon after the phone wakes.
     */
    private suspend fun awaitReceiverIdle(idleMs: Long) {
        while (true) {
            val idle = receiverIdleMillis() ?: return
            if (idle >= idleMs) return
            delay((idleMs - idle).coerceAtMost(IDLE_CHECK_MS))
        }
    }

    private companion object {
        const val LOCK_RENEWAL_MS = 30 * 60_000L
        const val IDLE_CHECK_MS = 60_000L
    }
}
