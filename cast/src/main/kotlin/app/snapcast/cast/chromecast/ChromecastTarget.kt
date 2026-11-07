package app.snapcast.cast.chromecast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouter
import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.CastDeviceInfo
import app.snapcast.cast.CastMedia
import app.snapcast.cast.CastQueueItem
import app.snapcast.cast.CastTarget
import app.snapcast.cast.CastTextTrack
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import app.snapcast.data.di.ApplicationScope
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.MediaQueue
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CastTarget] for Chromecast devices through the Cast framework and the Default Media Receiver.
 *
 * Connection state follows the framework's [CastState]; playback state mirrors the [RemoteMediaClient]
 * (status, metadata, queue, 1 s progress ticks) and the session volume. Every SDK call runs on the main
 * thread; control calls are no-ops while no session is connected.
 */
@Singleton
internal class ChromecastTarget @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val castContextProvider: CastContextProvider,
    @ApplicationScope private val scope: CoroutineScope,
) : CastTarget {

    override val id: String = TARGET_ID

    override val displayName: String = DISPLAY_NAME

    private val _connectionState = MutableStateFlow(CastConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<CastConnectionState> = _connectionState.asStateFlow()

    private val _device = MutableStateFlow<CastDeviceInfo?>(null)
    override val device: StateFlow<CastDeviceInfo?> = _device.asStateFlow()

    private val _playback = MutableStateFlow<RemotePlaybackState?>(null)
    override val playback: StateFlow<RemotePlaybackState?> = _playback.asStateFlow()

    private val _sessionEndedOnPurpose = MutableStateFlow(false)
    override val sessionEndedOnPurpose: StateFlow<Boolean> = _sessionEndedOnPurpose.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Main-thread state.
    private var castContext: CastContext? = null
    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var mediaQueue: MediaQueue? = null
    private var queueSnapshot: List<CastQueueItem> = emptyList()

    /** The receiver reports a rate of 0 while paused; keep the last real one. */
    private var lastPositiveRate = 1.0

    private val castStateListener = CastStateListener { refreshConnection() }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _sessionEndedOnPurpose.value = false
            refreshConnection()
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            Log.i(TAG, "Session started")
            _sessionEndedOnPurpose.value = false
            attachSession(session)
            refreshConnection()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.i(TAG, "Session start failed: ${CastStatusCodes.getStatusCodeString(error)}")
            detachSession()
            refreshConnection()
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            Log.i(TAG, "Session ended: ${CastStatusCodes.getStatusCodeString(error)}")
            // The receiver app closed: nothing plays on. Every other end may leave the receiver playing (the SDK
            // reports success for any end started from the phone, a lost session given up included), so only a
            // Stop from this app (disconnect) counts as on purpose besides this.
            if (error == CastStatusCodes.APPLICATION_NOT_RUNNING) _sessionEndedOnPurpose.value = true
            detachSession()
            refreshConnection()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            Log.i(TAG, "Session resuming")
            refreshConnection()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            Log.i(TAG, "Session resumed (was suspended: $wasSuspended)")
            _sessionEndedOnPurpose.value = false
            attachSession(session)
            refreshConnection()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.i(TAG, "Session resume failed: ${CastStatusCodes.getStatusCodeString(error)}")
            detachSession()
            refreshConnection()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // Connection lost (screen off, Wi-Fi power save, out of range): the receiver plays on meanwhile.
            Log.i(TAG, "Session suspended: ${suspensionReason(reason)}")
            refreshConnection()
        }
    }

    /**
     * "Stop casting" in the Cast dialog or the system's media output switcher: the receiver is stopped, but the
     * SDK reports a plain end, as it does for a lost session it gave up on. Only a connected session can be
     * stopped this way, which tells the two apart.
     */
    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            if (reason != MediaRouter.UNSELECT_REASON_STOPPED || CastDevice.getFromBundle(route.extras) == null) return
            if (_connectionState.value == CastConnectionState.CONNECTED) {
                Log.i(TAG, "Stopped from the route dialog")
                _sessionEndedOnPurpose.value = true
            }
        }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publish(refreshQueue = true)

        override fun onMetadataUpdated() = publish(refreshQueue = false)

        override fun onQueueStatusUpdated() = publish(refreshQueue = true)

        override fun onPreloadStatusUpdated() = publish(refreshQueue = false)
    }

    private val queueCallback = object : MediaQueue.Callback() {
        override fun mediaQueueChanged() = publish(refreshQueue = true)

        override fun itemsReloaded() = publish(refreshQueue = true)

        override fun itemsInsertedInRange(insertIndex: Int, insertCount: Int) = publish(refreshQueue = true)

        override fun itemsUpdatedAtIndexes(indexes: IntArray) = publish(refreshQueue = true)

        override fun itemsRemovedAtIndexes(indexes: IntArray) = publish(refreshQueue = true)
    }

    private val progressListener = RemoteMediaClient.ProgressListener { _, _ -> publish(refreshQueue = false) }

    private val castListener = object : Cast.Listener() {
        override fun onVolumeChanged() = publish(refreshQueue = false)
    }

    init {
        scope.launch(Dispatchers.Main.immediate) {
            val context = castContextProvider.await()
            if (context == null) {
                _connectionState.value = CastConnectionState.UNAVAILABLE
            } else {
                attach(context)
            }
        }
    }

    // region Loading and queue

    override suspend fun load(
        items: List<CastMedia>,
        startIndex: Int,
        startPositionMs: Long,
        autoplay: Boolean,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        if (items.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Nothing to load"))
        val remote = client ?: return@withContext notConnected()
        val index = startIndex.coerceIn(items.indices)
        val start = startPositionMs.coerceAtLeast(0)
        val builder = MediaLoadRequestData.Builder().setAutoplay(autoplay)
        // Without a position the receiver starts at its default: the beginning, or the live edge.
        if (start > 0) builder.setCurrentTime(start)
        if (items.size == 1) {
            builder.setMediaInfo(MediaInfoMapper.toMediaInfo(items[index]))
        } else {
            val queueItems = items.mapIndexed { i, media ->
                MediaInfoMapper.toQueueItem(media, startPositionMs = if (i == index) start else null)
            }
            builder.setQueueData(
                MediaQueueData.Builder()
                    .setItems(queueItems)
                    .setStartIndex(index)
                    .build(),
            )
        }
        items[index].activeTextTrackId?.let { builder.setActiveTrackIds(longArrayOf(it)) }
        remote.load(builder.build()).awaitResult()
    }

    override suspend fun appendToQueue(items: List<CastMedia>): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        if (items.isEmpty()) return@withContext Result.success(Unit)
        val remote = client ?: return@withContext notConnected()
        if (!hasMedia(remote)) return@withContext load(items)
        val queueItems = items.map { MediaInfoMapper.toQueueItem(it) }
        if (queueItems.size == 1) {
            remote.queueAppendItem(queueItems[0], null).awaitResult()
        } else {
            remote.queueInsertItems(queueItems.toTypedArray(), MediaQueueItem.INVALID_ITEM_ID, null).awaitResult()
        }
    }

    override suspend fun insertNext(item: CastMedia): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val remote = client ?: return@withContext notConnected()
        if (!hasMedia(remote)) return@withContext load(listOf(item))
        val queueItem = MediaInfoMapper.toQueueItem(item)
        val beforeId = QueuePositions.insertBeforeId(queueItemIds(remote), currentItemId(remote))
        if (beforeId == null) {
            remote.queueAppendItem(queueItem, null).awaitResult()
        } else {
            remote.queueInsertItems(arrayOf(queueItem), beforeId, null).awaitResult()
        }
    }

    /** The current item rebuilt from the receiver's media info, or null when nothing (of ours) is loaded. */
    suspend fun currentMedia(): CastMedia? = withContext(Dispatchers.Main.immediate) {
        val remote = client ?: return@withContext null
        val info = remote.mediaInfo ?: remote.mediaStatus?.mediaInfo ?: return@withContext null
        MediaInfoMapper.fromMediaInfo(info, remote.mediaStatus?.activeTrackIds)
    }

    /**
     * Replaces the current item with [media] at [positionMs], keeping the rest of the queue: inserted after
     * the current item and played, then the old item is removed. A single item is simply reloaded.
     */
    suspend fun replaceCurrent(media: CastMedia, positionMs: Long, autoplay: Boolean): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            val remote = client ?: return@withContext notConnected()
            val ids = queueItemIds(remote)
            val currentId = currentItemId(remote)
            if (ids.size <= 1 || currentId == null) {
                return@withContext load(listOf(media), startPositionMs = positionMs, autoplay = autoplay)
            }
            val beforeId = QueuePositions.insertBeforeId(ids, currentId) ?: MediaQueueItem.INVALID_ITEM_ID
            val item = MediaInfoMapper.toQueueItem(media, startPositionMs = positionMs)
            val result = remote.queueInsertAndPlayItem(item, beforeId, positionMs.coerceAtLeast(0), null).awaitResult()
            if (result.isSuccess) {
                remote.queueRemoveItem(currentId, null)
                if (!autoplay) remote.pause()
            }
            result
        }

    override fun moveQueueItem(itemId: Int, toIndex: Int) = onMain {
        client?.queueMoveItemToNewIndex(itemId, toIndex.coerceAtLeast(0), null)
    }

    override fun removeQueueItem(itemId: Int) = onMain { client?.queueRemoveItem(itemId, null) }

    override fun jumpToQueueItem(itemId: Int) = onMain { client?.queueJumpToItem(itemId, null) }

    override fun skipNext() = onMain { client?.queueNext(null) }

    override fun skipPrevious() = onMain { client?.queuePrev(null) }

    // endregion

    // region Transport controls

    override fun play() = onMain { client?.play() }

    override fun pause() = onMain { client?.pause() }

    override fun togglePlayPause() = onMain { client?.togglePlayback() }

    override fun seekTo(positionMs: Long) = onMain { seek(positionMs) }

    override fun seekBy(deltaMs: Long) = onMain {
        val remote = client ?: return@onMain
        val duration = remote.streamDuration
        var target = remote.approximateStreamPosition + deltaMs
        if (duration > 0) target = target.coerceAtMost(duration)
        seek(target)
    }

    override fun stop() = onMain { client?.stop() }

    override fun setVolume(level: Double) = onMain {
        val current = session ?: return@onMain
        val volume = level.coerceIn(0.0, 1.0)
        runCatching { current.setVolume(volume) }
        _playback.update { it?.copy(volume = volume) }
    }

    override fun setMuted(muted: Boolean) = onMain {
        val current = session ?: return@onMain
        runCatching { current.setMute(muted) }
        _playback.update { it?.copy(isMuted = muted) }
    }

    override fun setPlaybackRate(rate: Double) = onMain {
        client?.setPlaybackRate(rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE))
    }

    override fun setActiveTextTrack(trackId: Long?) = onMain {
        val remote = client ?: return@onMain
        val textTrackIds = MediaInfoMapper.textTracks(remote.mediaInfo).mapTo(HashSet()) { it.id }
        val otherActive = (remote.mediaStatus?.activeTrackIds ?: LongArray(0)).filter { it !in textTrackIds }
        remote.setActiveMediaTracks((otherActive + listOfNotNull(trackId)).toLongArray())
    }

    override fun disconnect(stopCasting: Boolean) = onMain {
        // Ending without stopping the receiver leaves it playing, possibly from the proxy.
        if (stopCasting) _sessionEndedOnPurpose.value = true
        castContext?.sessionManager?.endCurrentSession(stopCasting)
    }

    private fun seek(positionMs: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }

    // endregion

    // region Session tracking (main thread)

    private fun attach(context: CastContext) {
        castContext = context
        context.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        context.addCastStateListener(castStateListener)
        // No discovery flags: this only listens, the Cast framework runs discovery.
        context.mergedSelector?.let { selector ->
            runCatching { MediaRouter.getInstance(appContext).addCallback(selector, routerCallback) }
                .onFailure { Log.w(TAG, "Route callback unavailable", it) }
        }
        context.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attachSession)
        refreshConnection()
    }

    private fun refreshConnection() {
        val context = castContext ?: return
        val current = context.sessionManager.currentCastSession
        val previous = _connectionState.value
        _connectionState.value = when (context.castState) {
            CastState.CONNECTED -> if (current != null && current.isConnected) {
                attachSession(current)
                CastConnectionState.CONNECTED
            } else {
                CastConnectionState.CONNECTING
            }
            CastState.CONNECTING -> {
                current?.let { _device.value = deviceInfo(it) }
                CastConnectionState.CONNECTING
            }
            CastState.NO_DEVICES_AVAILABLE -> {
                detachSession()
                CastConnectionState.NO_DEVICES
            }
            else -> {
                detachSession()
                CastConnectionState.NOT_CONNECTED
            }
        }
        if (_connectionState.value != previous) Log.i(TAG, "Connection: $previous -> ${_connectionState.value}")
    }

    private fun attachSession(newSession: CastSession) {
        val newClient = newSession.remoteMediaClient
        if (newSession === session && newClient === client) return
        detachSession()
        session = newSession
        newSession.addCastListener(castListener)
        client = newClient
        newClient?.registerCallback(mediaCallback)
        newClient?.addProgressListener(progressListener, PROGRESS_INTERVAL_MS)
        mediaQueue = newClient?.mediaQueue?.also { it.registerCallback(queueCallback) }
        _device.value = deviceInfo(newSession)
        publish(refreshQueue = true)
    }

    private fun detachSession() {
        mediaQueue?.unregisterCallback(queueCallback)
        mediaQueue = null
        client?.let {
            it.unregisterCallback(mediaCallback)
            it.removeProgressListener(progressListener)
        }
        client = null
        session?.let { runCatching { it.removeCastListener(castListener) } }
        session = null
        queueSnapshot = emptyList()
        lastPositiveRate = 1.0
        _device.value = null
        _playback.value = null
    }

    private fun deviceInfo(session: CastSession): CastDeviceInfo? = session.castDevice?.let { device ->
        CastDeviceInfo(
            name = device.friendlyName?.takeIf { it.isNotBlank() } ?: DISPLAY_NAME,
            model = device.modelName?.takeIf { it.isNotBlank() },
        )
    }

    // endregion

    // region Playback state mapping (main thread)

    private fun publish(refreshQueue: Boolean) {
        val remote = client
        val status = remote?.mediaStatus
        if (remote == null || status == null) {
            _playback.value = null
            return
        }
        val info = remote.mediaInfo ?: status.mediaInfo
        val playerState = playerState(status.playerState)
        val idleReason = if (playerState == RemotePlayerState.IDLE) idleReasonName(status.idleReason) else null
        if (info == null && idleReason == null) {
            queueSnapshot = emptyList()
            _playback.value = null
            return
        }
        if (refreshQueue || queueSnapshot.isEmpty()) queueSnapshot = buildQueue(status)

        val metadata = info?.metadata
        val textTracks = MediaInfoMapper.textTracks(info).map { CastTextTrack(it.id, it.name, it.language) }
        val activeIds = status.activeTrackIds ?: LongArray(0)
        status.playbackRate.takeIf { it > 0 }?.let { lastPositiveRate = it }
        val isLive = remote.isLiveStream || info?.streamType == MediaInfo.STREAM_TYPE_LIVE
        val durationMs = remote.streamDuration.takeIf { it > 0 }
            ?: info?.streamDuration?.takeIf { it > 0 }
            ?: 0L
        val currentSession = session
        val volume = currentSession?.let { runCatching { it.volume }.getOrNull() } ?: 1.0
        val muted = currentSession?.let { runCatching { it.isMute }.getOrNull() } ?: false

        _playback.value = RemotePlaybackState(
            title = metadata?.getString(MediaMetadata.KEY_TITLE),
            subtitle = metadata?.getString(MediaMetadata.KEY_SUBTITLE),
            posterUrl = MediaInfoMapper.posterUrl(metadata),
            playerState = playerState,
            positionMs = remote.approximateStreamPosition.coerceAtLeast(0),
            durationMs = durationMs,
            isLive = isLive,
            isSeekable = !isLive && durationMs > 0 && status.isMediaCommandSupported(MediaStatus.COMMAND_SEEK),
            volume = volume.coerceIn(0.0, 1.0),
            isMuted = muted,
            playbackRate = lastPositiveRate,
            supportsPlaybackRate = status.isMediaCommandSupported(MediaStatus.COMMAND_PLAYBACK_RATE),
            textTracks = textTracks,
            activeTextTrackId = textTracks.firstOrNull { it.id in activeIds }?.id,
            queue = queueSnapshot,
            currentItemId = status.currentItemId.takeIf { it != MediaQueueItem.INVALID_ITEM_ID },
            idleReason = idleReason,
            originalUrl = MediaInfoMapper.originalUrl(info),
            contentUrl = info?.contentUrl?.takeIf { it.isNotBlank() } ?: info?.contentId?.takeIf { it.isNotBlank() },
        )
    }

    private fun buildQueue(status: MediaStatus): List<CastQueueItem> {
        val queue = mediaQueue
        val fromStatus = status.queueItems.associateBy { it.itemId }
        val queueIds = queue?.itemIds?.toList().orEmpty()
        val ids = queueIds.ifEmpty { status.queueItems.map { it.itemId } }
        return ids.mapIndexed { index, itemId ->
            val item = fromStatus[itemId]
                ?: queue?.takeIf { queueIds.isNotEmpty() && index < it.itemCount }?.getItemAtIndex(index, true)
            val media = item?.media
            val metadata = media?.metadata
            CastQueueItem(
                itemId = itemId,
                title = metadata?.getString(MediaMetadata.KEY_TITLE).orEmpty(),
                subtitle = metadata?.getString(MediaMetadata.KEY_SUBTITLE),
                posterUrl = MediaInfoMapper.posterUrl(metadata),
                originalUrl = MediaInfoMapper.originalUrl(media),
            )
        }
    }

    private fun queueItemIds(remote: RemoteMediaClient): List<Int> {
        val fromQueue = remote.mediaQueue.itemIds.toList()
        return fromQueue.ifEmpty { remote.mediaStatus?.queueItems.orEmpty().map { it.itemId } }
    }

    private fun currentItemId(remote: RemoteMediaClient): Int? =
        remote.mediaStatus?.currentItemId?.takeIf { it != MediaQueueItem.INVALID_ITEM_ID }

    private fun hasMedia(remote: RemoteMediaClient): Boolean =
        remote.mediaInfo != null || remote.mediaStatus?.mediaInfo != null

    // endregion

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    private fun notConnected(): Result<Unit> = Result.failure(CastRequestException("Not connected to a Cast device"))

    private companion object {
        const val TAG = "SnapcastCast"
        const val TARGET_ID = "chromecast"
        const val DISPLAY_NAME = "Chromecast"
        const val PROGRESS_INTERVAL_MS = 1_000L
        const val MIN_PLAYBACK_RATE = 0.5
        const val MAX_PLAYBACK_RATE = 2.0

        /** CastSession's suspension reasons (Cast.Listener's), not status codes. */
        fun suspensionReason(reason: Int): String = when (reason) {
            SUSPEND_SERVICE_DISCONNECTED -> "Play services disconnected"
            SUSPEND_NETWORK_LOST -> "network lost"
            else -> "reason $reason"
        }

        const val SUSPEND_SERVICE_DISCONNECTED = 1
        const val SUSPEND_NETWORK_LOST = 2

        fun playerState(state: Int): RemotePlayerState = when (state) {
            MediaStatus.PLAYER_STATE_PLAYING -> RemotePlayerState.PLAYING
            MediaStatus.PLAYER_STATE_PAUSED -> RemotePlayerState.PAUSED
            MediaStatus.PLAYER_STATE_BUFFERING -> RemotePlayerState.BUFFERING
            MediaStatus.PLAYER_STATE_LOADING -> RemotePlayerState.LOADING
            else -> RemotePlayerState.IDLE
        }

        fun idleReasonName(reason: Int): String? = when (reason) {
            MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
            MediaStatus.IDLE_REASON_CANCELED -> "CANCELED"
            MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
            MediaStatus.IDLE_REASON_ERROR -> "ERROR"
            else -> null
        }
    }
}
