package app.snapcast.cast.controller

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.app.MediaRouteDialogFactory
import app.snapcast.cast.CastConnectionState
import app.snapcast.cast.CastController
import app.snapcast.cast.CastErrorKind
import app.snapcast.cast.CastEvent
import app.snapcast.cast.CastMedia
import app.snapcast.cast.CastResult
import app.snapcast.cast.CastSubtitle
import app.snapcast.cast.CastTarget
import app.snapcast.cast.PendingQueueItem
import app.snapcast.cast.R
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import app.snapcast.cast.chromecast.CastContextProvider
import app.snapcast.cast.chromecast.ChromecastTarget
import app.snapcast.cast.media.CastMediaFactory
import app.snapcast.cast.media.CastMediaRules
import app.snapcast.cast.media.LanMediaProxy
import app.snapcast.cast.media.LocalMediaReader
import app.snapcast.cast.media.MediaProxy
import app.snapcast.cast.media.ResumePolicy
import app.snapcast.cast.media.SubtitleText
import app.snapcast.cast.net.NetworkHelper
import app.snapcast.cast.proxy.ProxyManager
import app.snapcast.cast.service.CastPlaybackService
import app.snapcast.core.media.SubtitleConverter
import app.snapcast.core.model.AppSettings
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.playback.PlaybackPositionRepository
import app.snapcast.data.settings.SettingsRepository
import app.snapcast.proxy.StreamProblem
import app.snapcast.proxy.StreamProxy
import app.snapcast.proxy.SubtitleSource
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CastController] over the Chromecast target, the local proxy and the casting service.
 *
 * All mutable state is confined to the main thread. [events] carries outcomes nobody is waiting for
 * (a request that ran after a device connected, receiver playback errors, the end of a session) plus
 * [CastEvent.Started] / [CastEvent.Queued] for every success; errors returned from a call are not
 * emitted again.
 *
 * Requests that talk to the receiver run in the application scope ([detached]): the caller is usually a
 * screen's viewModelScope, and leaving the screen while the receiver loads must not abandon a load that
 * was already sent (the casting service, the pending queue and private-tab bookkeeping would be skipped).
 */
@Singleton
internal class CastControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chromecast: ChromecastTarget,
    private val castContextProvider: CastContextProvider,
    private val proxyManager: ProxyManager,
    private val networkHelper: NetworkHelper,
    private val localMedia: LocalMediaReader,
    private val settingsRepository: SettingsRepository,
    private val positions: PlaybackPositionRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : CastController {

    override val target: CastTarget get() = chromecast

    override val connectionState: StateFlow<CastConnectionState> get() = chromecast.connectionState

    override val playback: StateFlow<RemotePlaybackState?> get() = chromecast.playback

    private val _events = MutableSharedFlow<CastEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    private val _pendingQueue = MutableStateFlow<List<PendingQueueItem>>(emptyList())
    override val pendingQueue: StateFlow<List<PendingQueueItem>> = _pendingQueue.asStateFlow()

    /** A cast request waiting for a device to connect. */
    private sealed interface CastRequest {
        val startPositionMs: Long?

        data class Remote(
            val video: DetectedVideo,
            val variant: VideoVariant?,
            val subtitle: SubtitleTrack?,
            val direct: Boolean,
            override val startPositionMs: Long?,
        ) : CastRequest

        data class Local(val uri: Uri, val title: String?, val mimeType: String?) : CastRequest {
            override val startPositionMs: Long? get() = null
        }

        /** The pending queue itself, in order (taken from it once the receiver has loaded it). */
        data object Queue : CastRequest {
            override val startPositionMs: Long? get() = null
        }
    }

    /** What one load sends: [items] in order, of which [consumed] come from the pending queue. */
    private class Batch(val items: List<CastMedia>, val consumed: List<PendingQueueItem>)

    /** Last known position of the item playing on the receiver. */
    private data class Progress(val url: String, val positionMs: Long, val durationMs: Long, val isLive: Boolean)

    /** A failure carrying the user-facing result. */
    private class CastFailure(val kind: CastErrorKind, override val message: String) : Exception(message)

    /**
     * The proxied item last seen playing (or paused), where it was and whether it played, and its media once
     * read from the receiver: what [reload] loads again when the receiver fails on it.
     */
    private data class ProxiedItem(
        val contentUrl: String,
        val originalUrl: String?,
        val itemId: Int?,
        val positionMs: Long,
        val paused: Boolean,
        val isLive: Boolean,
        val media: CastMedia? = null,
    )

    // Main-thread state.
    private var pendingRequest: CastRequest? = null
    private var pendingExpiry: Job? = null
    /** LOADs being sent (casts, subtitle reloads, recoveries): receiver errors meanwhile are about the old media. */
    private var loadsInFlight = 0
    private var suppressReceiverErrorsUntil = 0L
    private var proxiedItem: ProxiedItem? = null
    private val recovery = ReceiverRecovery(SystemClock::elapsedRealtime)
    private var recovering = false

    /** Counts the LOADs sent; a load that failed because a newer one replaced it must not be retried. */
    private var loadGeneration = 0
    private var lastProgress: Progress? = null
    private var finishedUrl: String? = null
    private var lastReceiverErrorKey: String? = null
    private var serviceRequested = false

    /** When [ensureServiceForResumedSession] last started the service: a service that keeps failing is not looped. */
    private var serviceRestartedAt = Long.MIN_VALUE / 2
    private var inBandSubtitleSelection: Job? = null
    private val privateUrls = HashSet<String>()

    /**
     * Page of each recently cast video of a normal tab, by video URL, least recently cast first: receiver errors
     * are told about that page.
     */
    private val pagesByUrl = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_PAGES_BY_URL
    }

    private val settings: AppSettings get() = settingsRepository.settings.value

    init {
        scope.launch(Dispatchers.Main.immediate) { observeConnection() }
        scope.launch(Dispatchers.Main.immediate) { observePlayback() }
        scope.launch(Dispatchers.Main.immediate) { savePositionsWhilePlaying() }
        scope.launch(Dispatchers.Main.immediate) {
            // The service stopped (nothing loaded, or a lost session whose receiver stopped using the proxy): a
            // session that comes back with media loaded gets it again, now or once the app is visible.
            CastPlaybackService.running.collect { running ->
                if (!running) {
                    serviceRequested = false
                    ensureServiceForResumedSession(chromecast.playback.value)
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            // Retries a casting service that couldn't start from the background once the app is visible again
            // (playback updates alone stop while the receiver is paused).
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_START) ensureServiceForResumedSession(chromecast.playback.value)
                },
            )
        }
    }

    // region Casting

    override suspend fun cast(
        video: DetectedVideo,
        variant: VideoVariant?,
        subtitle: SubtitleTrack?,
        direct: Boolean,
        startPositionMs: Long?,
    ): CastResult = withContext(Dispatchers.Main.immediate) {
        if (video.isDrmProtected) return@withContext failure(CastErrorKind.DRM, R.string.cast_error_drm)
        if (castContextProvider.await() == null) {
            return@withContext failure(CastErrorKind.UNAVAILABLE, R.string.cast_error_unavailable)
        }
        if (usesProxy(direct)) proxyPrecondition()?.let { return@withContext it }
        val request = CastRequest.Remote(video, variant, subtitle, direct, startPositionMs)
        if (chromecast.connectionState.value != CastConnectionState.CONNECTED) return@withContext defer(request)
        detached { execute(request) }
    }

    override suspend fun castLocalFile(uri: Uri, title: String?, mimeType: String?): CastResult =
        withContext(Dispatchers.Main.immediate) {
            if (castContextProvider.await() == null) {
                return@withContext failure(CastErrorKind.UNAVAILABLE, R.string.cast_error_unavailable)
            }
            proxyPrecondition()?.let { return@withContext it }
            withContext(Dispatchers.IO) { localMedia.keepAccess(uri) }
            val request = CastRequest.Local(uri, title, mimeType)
            if (chromecast.connectionState.value != CastConnectionState.CONNECTED) return@withContext defer(request)
            detached { execute(request) }
        }

    override suspend fun castPendingQueue(): CastResult = withContext(Dispatchers.Main.immediate) {
        if (_pendingQueue.value.none { !it.video.isDrmProtected }) {
            return@withContext failure(CastErrorKind.UNSUPPORTED, R.string.cast_error_queue_empty)
        }
        if (castContextProvider.await() == null) {
            return@withContext failure(CastErrorKind.UNAVAILABLE, R.string.cast_error_unavailable)
        }
        if (usesProxy(direct = false)) proxyPrecondition()?.let { return@withContext it }
        if (chromecast.connectionState.value != CastConnectionState.CONNECTED) {
            return@withContext defer(CastRequest.Queue)
        }
        detached { execute(CastRequest.Queue) }
    }

    override suspend fun addToQueue(video: DetectedVideo, variant: VideoVariant?, playNext: Boolean): CastResult =
        withContext(Dispatchers.Main.immediate) {
            if (video.isDrmProtected) return@withContext failure(CastErrorKind.DRM, R.string.cast_error_drm)
            val connected = chromecast.connectionState.value == CastConnectionState.CONNECTED
            val state = chromecast.playback.value
            if (connected && state != null && loadsInFlight == 0 && ReceiverQueue.isEnded(state)) {
                // The receiver's queue is over (finished, stopped or failed): an appended item would never play,
                // so play it now. This resumes it, sends the pending queue along and starts the casting service.
                val request = CastRequest.Remote(video, variant, subtitle = null, direct = false, startPositionMs = null)
                return@withContext detached { execute(request) }
            }
            if (!connected || state == null) {
                _pendingQueue.update { queue ->
                    val item = PendingQueueItem(PendingQueue.newKey(video, System.currentTimeMillis(), queue), video, variant)
                    PendingQueue.add(queue, item, playNext)
                }
                _events.tryEmit(CastEvent.Queued(video.displayTitle))
                return@withContext CastResult.Success
            }
            detached { appendToReceiver(video, variant, playNext) }
        }

    /** Appends [video] to (or, with [playNext], inserts it into) the receiver's queue. */
    private suspend fun appendToReceiver(video: DetectedVideo, variant: VideoVariant?, playNext: Boolean): CastResult {
        // Appending to a receiver with no media loads it, replacing a load in flight.
        loadGeneration++
        loadsInFlight++
        proxyManager.acquire()
        var failed = true
        try {
            val media = buildRemote(video, variant, subtitle = null, direct = false)
            remember(video)
            // The service keeps the proxy alive for the new item: it may have stopped itself after the previous
            // item ended, and appendToQueue / insertNext load the item when the receiver holds no media.
            startPlaybackService()
            val result = if (playNext) chromecast.insertNext(media) else chromecast.appendToQueue(listOf(media))
            if (result.isFailure) {
                Log.w(TAG, "Queueing failed", result.exceptionOrNull())
                return failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_queue)
            }
            failed = false
            _events.tryEmit(CastEvent.Queued(media.title))
            return CastResult.Success
        } catch (failure: CastFailure) {
            return CastResult.Error(failure.kind, failure.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Queueing failed", e)
            return failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_queue)
        } finally {
            loadsInFlight--
            proxyManager.release()
            afterOwnLoad(failed)
        }
    }

    override fun removePending(key: String) {
        _pendingQueue.update { PendingQueue.remove(it, key) }
    }

    override fun movePending(key: String, toIndex: Int) {
        _pendingQueue.update { PendingQueue.move(it, key, toIndex) }
    }

    override suspend fun loadLocalSubtitle(uri: Uri): CastResult = withContext(Dispatchers.Main.immediate) {
        val state = chromecast.playback.value
        val current = if (state != null && chromecast.connectionState.value == CastConnectionState.CONNECTED) {
            chromecast.currentMedia()
        } else {
            null
        }
        if (state == null || current == null) {
            return@withContext failure(CastErrorKind.UNSUPPORTED, R.string.cast_error_nothing_casting)
        }
        // Detached: leaving the screen after the receiver got the new item must not skip removing the old one.
        detached { reloadWithLocalSubtitle(uri, state, current) }
    }

    /** Reloads [current] at its position with the subtitle file [uri] added and enabled. */
    private suspend fun reloadWithLocalSubtitle(uri: Uri, state: RemotePlaybackState, current: CastMedia): CastResult {
        loadGeneration++
        loadsInFlight++
        proxyManager.acquire()
        var failed = true
        try {
            val fileName = withContext(Dispatchers.IO) { localMedia.displayName(uri) }
            val vtt = withContext(Dispatchers.IO) { readSubtitleAsVtt(uri, SubtitleFormat.UNKNOWN, fileName) }
            val proxy = lanProxy()
            val registered = registerSafely { proxy.registerSubtitle(SubtitleSource.Inline(vtt, SubtitleFormat.VTT)) }
            val trackId = CastMediaRules.nextTrackId(current.textTracks.map { it.trackId })
            val track = CastSubtitle(
                trackId = trackId,
                url = proxy.url(registered.path),
                language = null,
                name = SubtitleText.trackName(fileName, string(R.string.cast_local_subtitle_name)),
            )
            val media = current.copy(textTracks = current.textTracks + track, activeTextTrackId = trackId)
            val positionMs = chromecast.playback.value?.positionMs ?: state.positionMs
            val result = chromecast.replaceCurrent(
                media,
                positionMs,
                autoplay = state.playerState != RemotePlayerState.PAUSED,
            )
            return if (result.isFailure) {
                Log.w(TAG, "Reloading with subtitles failed", result.exceptionOrNull())
                failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_load_failed)
            } else {
                failed = false
                CastResult.Success
            }
        } catch (failure: CastFailure) {
            return CastResult.Error(failure.kind, failure.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Reloading with subtitles failed", e)
            return failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_load_failed)
        } finally {
            loadsInFlight--
            proxyManager.release()
            afterOwnLoad(failed)
        }
    }

    /**
     * After a load other than a cast (a subtitle reload, a queue append): a failure's status is not reported
     * besides the error its caller gets, and an error the load kept from being looked at is looked at now.
     */
    private fun afterOwnLoad(failed: Boolean) {
        if (failed) noteFailedLoad() else onReceiverError(chromecast.playback.value)
    }

    override suspend fun savedPositionMs(originalUrl: String): Long? = try {
        positions.get(originalUrl)?.positionMs
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Reading the saved position failed", e)
        null
    }

    override fun canCastDirectly(video: DetectedVideo): Boolean = CastMediaRules.canCastDirectly(video)

    // endregion

    // region Cast button and device chooser

    override fun createCastButton(context: Context): View {
        val state = castContextProvider.state.value
        if (state == CastContextProvider.State.Unavailable) return hiddenView(context)
        val button = try {
            MediaRouteButton(context)
        } catch (e: RuntimeException) {
            // MediaRouteButton needs an AppCompat-themed context.
            Log.w(TAG, "Cast button unavailable in this context", e)
            return hiddenView(context)
        }
        if (state is CastContextProvider.State.Ready) {
            setUpCastButton(context, button)
        } else {
            button.visibility = View.GONE
            scope.launch(Dispatchers.Main.immediate) {
                if (castContextProvider.await() != null && setUpCastButton(context, button)) {
                    button.visibility = View.VISIBLE
                }
            }
        }
        return button
    }

    private fun hiddenView(context: Context): View = View(context).apply { visibility = View.GONE }

    override fun showDeviceChooser(activity: FragmentActivity) {
        val castContext = castContextProvider.castContextOrNull
        if (castContext != null) {
            showRouteDialog(activity, castContext)
            return
        }
        if (castContextProvider.state.value == CastContextProvider.State.Unavailable) return
        scope.launch(Dispatchers.Main.immediate) {
            val ready = castContextProvider.await() ?: return@launch
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) showRouteDialog(activity, ready)
        }
    }

    private fun setUpCastButton(context: Context, button: MediaRouteButton): Boolean = try {
        CastButtonFactory.setUpMediaRouteButton(context, button)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cast button setup failed", e)
        button.visibility = View.GONE
        false
    }

    /** The chooser while disconnected; the route controller (volume, stop casting) while connected. */
    private fun showRouteDialog(activity: FragmentActivity, castContext: CastContext) {
        val fragmentManager = activity.supportFragmentManager
        if (fragmentManager.isStateSaved || fragmentManager.findFragmentByTag(ROUTE_DIALOG_TAG) != null) return
        val factory = MediaRouteDialogFactory.getDefault()
        if (chromecast.connectionState.value == CastConnectionState.CONNECTED) {
            factory.onCreateControllerDialogFragment().show(fragmentManager, ROUTE_DIALOG_TAG)
            return
        }
        val selector = castContext.mergedSelector ?: return
        val chooser = factory.onCreateChooserDialogFragment()
        chooser.setRouteSelector(selector)
        chooser.show(fragmentManager, ROUTE_DIALOG_TAG)
    }

    // endregion

    // region Request execution

    private fun defer(request: CastRequest): CastResult {
        pendingRequest = request
        pendingExpiry?.cancel()
        pendingExpiry = scope.launch(Dispatchers.Main.immediate) {
            delay(PENDING_REQUEST_TTL_MS)
            pendingRequest = null
        }
        return CastResult.NeedsDevice
    }

    /**
     * Runs [block] in the application scope and waits for it. A cancelled caller (a screen that went away)
     * stops waiting, but the request still completes, including its bookkeeping.
     */
    private suspend fun <T> detached(block: suspend () -> T): T =
        scope.async(Dispatchers.Main.immediate) { block() }.await()

    /** Builds the media (plus the pending queue) and loads it on the connected receiver. */
    private suspend fun execute(request: CastRequest): CastResult {
        val generation = ++loadGeneration
        proxyManager.acquire()
        loadsInFlight++
        inBandSubtitleSelection?.cancel()
        try {
            val batch = buildBatch(request)
            val first = batch.items.first()
            val startMs = startPosition(first, request.startPositionMs)
            // Recorded before the load so no position of a private-tab video is saved while it starts playing.
            // In reverse play order, so the items that play first are the most recently noted.
            batch.consumed.asReversed().forEach { remember(it.video) }
            if (request is CastRequest.Remote) remember(request.video)
            // Started while the app is still visible (Android 12+ refuses it from the background, and the LOAD
            // reply may only come seconds later); the service holds the proxy from now on and stops itself if
            // nothing ends up loaded.
            startPlaybackService()
            val result = chromecast.load(batch.items, 0, startMs, autoplay = true)
            if (result.isFailure) {
                Log.w(TAG, "Load failed", result.exceptionOrNull())
                noteFailedLoad()
                val problem = proxyManager.takeRecentProblem(
                    PROXY_PROBLEM_WINDOW_MS,
                    batch.items.mapNotNullTo(HashSet()) { StreamProxy.streamIdOf(it.contentUrl) },
                )
                if (problem != null && problem.kind == StreamProblem.Kind.UNREACHABLE && request is CastRequest.Remote &&
                    usesProxy(request.direct) && CastMediaRules.canCastDirectly(request.video) && generation == loadGeneration
                ) {
                    // The phone cannot reach the server (an incomplete certificate chain, a filtering DNS) but
                    // the Chromecast may: it is how the video plays with the proxy turned off.
                    Log.w(TAG, "The proxy could not reach the video's server (${problem.description}); casting directly")
                    return execute(request.copy(direct = true))
                }
                return CastResult.Error(CastErrorKind.LOAD_FAILED, problemMessage(problem) ?: string(R.string.cast_error_load_failed))
            }
            val consumed = batch.consumed.mapTo(HashSet()) { it.key }
            _pendingQueue.update { queue -> queue.filterNot { it.key in consumed } }
            lastReceiverErrorKey = null
            // Errors from now on belong to this media, not to a load that failed before it.
            suppressReceiverErrorsUntil = 0L
            _events.tryEmit(CastEvent.Started(first.title))
            val rendition = (request as? CastRequest.Remote)?.subtitle?.takeIf { it.isHlsRendition }
            if (rendition != null) selectInBandSubtitle(rendition, first)
            return CastResult.Success
        } catch (failure: CastFailure) {
            return CastResult.Error(failure.kind, failure.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Load failed", e)
            return failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_load_failed)
        } finally {
            loadsInFlight--
            proxyManager.release()
        }
    }

    private suspend fun buildBatch(request: CastRequest): Batch = when (request) {
        is CastRequest.Remote -> {
            val first = buildRemote(request.video, request.variant, request.subtitle, request.direct)
            val queued = buildPendingQueue()
            Batch(listOf(first) + queued.map { it.second }, queued.map { it.first })
        }
        is CastRequest.Local -> {
            val first = buildLocal(request)
            val queued = buildPendingQueue()
            Batch(listOf(first) + queued.map { it.second }, queued.map { it.first })
        }
        CastRequest.Queue -> {
            val failures = ArrayList<CastFailure>()
            val queued = buildPendingQueue(failures)
            if (queued.isEmpty()) {
                throw failures.firstOrNull() ?: CastFailure(CastErrorKind.UNSUPPORTED, string(R.string.cast_error_queue_empty))
            }
            Batch(queued.map { it.second }, queued.map { it.first })
        }
    }

    /** Pending items that can be built; the others stay in the app-side queue (their failures go to [failures]). */
    private suspend fun buildPendingQueue(
        failures: MutableList<CastFailure>? = null,
    ): List<Pair<PendingQueueItem, CastMedia>> {
        val built = ArrayList<Pair<PendingQueueItem, CastMedia>>()
        for (item in _pendingQueue.value) {
            if (item.video.isDrmProtected) continue
            try {
                built += item to buildRemote(item.video, item.variant, subtitle = null, direct = false)
            } catch (failure: CastFailure) {
                Log.w(TAG, "Skipping queued video: ${failure.message}")
                failures?.add(failure)
            }
        }
        return built
    }

    private fun usesProxy(direct: Boolean): Boolean = settings.proxyEnabled && !direct

    private suspend fun buildRemote(
        video: DetectedVideo,
        picked: VideoVariant?,
        subtitle: SubtitleTrack?,
        direct: Boolean,
    ): CastMedia {
        // An HLS subtitle rendition is only declared by the master playlist: a picked variant would lose it.
        val variant = picked.takeUnless { subtitle?.isHlsRendition == true }
        if (!usesProxy(direct)) return CastMediaFactory.direct(video, variant, subtitle)
        val proxy = lanProxy()
        val localSubtitles = readLocalSubtitles(CastMediaFactory.subtitleCandidates(video, subtitle))
        return registerSafely { CastMediaFactory.proxied(video, variant, subtitle, proxy, localSubtitles) }
    }

    private suspend fun buildLocal(request: CastRequest.Local): CastMedia {
        val proxy = lanProxy()
        val file = try {
            withContext(Dispatchers.IO) { localMedia.openVideo(request.uri, request.mimeType) }
        } catch (e: IOException) {
            Log.w(TAG, "Opening the local video failed", e)
            throw CastFailure(CastErrorKind.LOAD_FAILED, string(R.string.cast_error_local_file))
        }
        val registered = registerSafely { proxy.registerLocal(file.source, file.displayName ?: LOCAL_FILE_NAME) }
        return CastMedia(
            contentUrl = proxy.url(registered.path),
            contentType = file.source.mimeType,
            isLive = false,
            title = request.title?.takeIf { it.isNotBlank() }
                ?: file.displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: string(R.string.cast_local_file_title),
            subtitle = string(R.string.cast_local_file_subtitle),
            posterUrl = null,
            durationMs = file.durationMs,
            originalUrl = request.uri.toString(),
        )
    }

    /**
     * Why a proxied cast cannot work right now (no Wi-Fi, or no local network access on Android 17+), or
     * null. Checked before a request waits for a device, so the user learns it right away.
     */
    private fun proxyPrecondition(): CastResult.Error? = when {
        !hasLocalNetworkAccess() -> failure(CastErrorKind.LOAD_FAILED, R.string.cast_error_local_network)
        networkHelper.lanIpv4() == null -> failure(CastErrorKind.NO_WIFI, R.string.cast_error_no_wifi)
        else -> null
    }

    /**
     * Android 17 blocks local network traffic (the receiver's connections to the proxy, the proxy's requests
     * to LAN hosts) for apps targeting it unless ACCESS_LOCAL_NETWORK is granted. The UI asks for it before
     * casting.
     */
    private fun hasLocalNetworkAccess(): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_SDK ||
            ContextCompat.checkSelfPermission(context, PERMISSION_ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    /** The running proxy bound to the phone's LAN address. */
    private suspend fun lanProxy(): MediaProxy {
        if (!hasLocalNetworkAccess()) {
            throw CastFailure(CastErrorKind.LOAD_FAILED, string(R.string.cast_error_local_network))
        }
        val host = networkHelper.lanIpv4()
            ?: throw CastFailure(CastErrorKind.NO_WIFI, string(R.string.cast_error_no_wifi))
        val proxy = try {
            proxyManager.proxy()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Starting the proxy failed", e)
            throw CastFailure(CastErrorKind.PROXY_FAILED, string(R.string.cast_error_proxy))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Starting the proxy failed", e)
            throw CastFailure(CastErrorKind.PROXY_FAILED, string(R.string.cast_error_proxy))
        }
        return LanMediaProxy(proxy, host)
    }

    /** Runs proxy registrations off the main thread, mapping failures to [CastErrorKind.PROXY_FAILED]. */
    private suspend fun <T> registerSafely(block: () -> T): T = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "Proxy registration failed", e)
        throw CastFailure(CastErrorKind.PROXY_FAILED, string(R.string.cast_error_proxy))
    }

    /**
     * Subtitle files the user picked on the phone ([SubtitleTrack.isLocal]: content:// or file://), read and
     * converted to WebVTT. Only those: any other non-http URL comes from a page or a manifest, and reading it
     * with the app's permissions would let a page publish the app's private files.
     */
    private suspend fun readLocalSubtitles(tracks: List<SubtitleTrack>): Map<String, SubtitleSource> {
        val local = tracks.filter { it.isLocal }
        if (local.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            local.mapNotNull { track ->
                try {
                    val uri = Uri.parse(track.url)
                    val vtt = readSubtitleAsVtt(uri, track.format, localMedia.displayName(uri))
                    track.id to SubtitleSource.Inline(vtt, SubtitleFormat.VTT)
                } catch (failure: CastFailure) {
                    Log.w(TAG, "Skipping local subtitle: ${failure.message}")
                    null
                }
            }.toMap()
        }
    }

    /** Blocking: reads a subtitle file and returns it as WebVTT. */
    private fun readSubtitleAsVtt(uri: Uri, declared: SubtitleFormat, fileName: String?): String {
        val text = try {
            SubtitleText.decode(localMedia.readBytes(uri, SubtitleText.MAX_BYTES))
        } catch (e: IOException) {
            throw CastFailure(CastErrorKind.LOAD_FAILED, string(R.string.cast_error_subtitle_read))
        }
        return try {
            val sniffed = SubtitleConverter.sniffFormat(text)
            val candidates = listOf(sniffed, declared, SubtitleText.formatFromFileName(fileName))
            val format = candidates.firstOrNull { it != SubtitleFormat.UNKNOWN }
                ?: throw CastFailure(CastErrorKind.UNSUPPORTED, string(R.string.cast_error_subtitle_format))
            SubtitleConverter.toVtt(text, format)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Converting the subtitle failed", e)
            throw CastFailure(CastErrorKind.UNSUPPORTED, string(R.string.cast_error_subtitle_format))
        }
    }

    private suspend fun startPosition(media: CastMedia, requestedMs: Long?): Long {
        val resume = settings.resumePlayback
        val saved = if (ResumePolicy.shouldLookUp(requestedMs, resume, media.isLive)) {
            savedPositionMs(media.originalUrl)
        } else {
            null
        }
        return ResumePolicy.startPositionMs(requestedMs, resume, media.isLive, saved)
    }

    /**
     * The service is only marked as requested when the start call succeeds, so a start refused from the
     * background is retried once the app is visible again ([ensureServiceForResumedSession]).
     */
    private fun startPlaybackService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, CastPlaybackService::class.java))
            serviceRequested = true
        } catch (e: IllegalStateException) {
            // Android 12+ refuses foreground services started from the background.
            serviceRequested = false
            Log.w(TAG, "Couldn't start the casting service", e)
        } catch (e: SecurityException) {
            serviceRequested = false
            Log.w(TAG, "Couldn't start the casting service", e)
        }
    }

    /**
     * HLS subtitle renditions are never side-loaded (they are playlists the receiver reads from the master
     * itself). Once the receiver lists the stream's own text tracks, enables the one matching [chosen].
     */
    private fun selectInBandSubtitle(chosen: SubtitleTrack, media: CastMedia) {
        inBandSubtitleSelection?.cancel()
        val sideLoaded = media.textTracks.mapTo(HashSet()) { it.trackId }
        inBandSubtitleSelection = scope.launch(Dispatchers.Main.immediate) {
            val trackId = withTimeoutOrNull(IN_BAND_TRACK_WAIT_MS) {
                chromecast.playback
                    .mapNotNull { state ->
                        state?.takeIf { it.originalUrl == media.originalUrl }
                            ?.let { CastMediaRules.inBandTextTrack(chosen, it.textTracks, sideLoaded) }
                    }
                    .first()
            } ?: return@launch
            chromecast.setActiveTextTrack(trackId)
        }
    }

    // endregion

    // region Observers (main thread)

    private suspend fun observeConnection() {
        var wasConnected = false
        chromecast.connectionState.collect { state ->
            when (state) {
                CastConnectionState.CONNECTED -> {
                    wasConnected = true
                    val request = pendingRequest
                    if (request != null) {
                        pendingRequest = null
                        pendingExpiry?.cancel()
                        scope.launch(Dispatchers.Main.immediate) { runPendingRequest(request) }
                    }
                    // A session that comes back usually brings an unchanged status, which is not emitted again:
                    // a failure seen (or missed) while it was away is looked at now.
                    onReceiverError(chromecast.playback.value)
                }
                CastConnectionState.CONNECTING -> Unit
                CastConnectionState.NOT_CONNECTED,
                CastConnectionState.NO_DEVICES,
                CastConnectionState.UNAVAILABLE,
                -> if (wasConnected) {
                    wasConnected = false
                    serviceRequested = false
                    lastProgress?.let(::persist)
                    lastProgress = null
                    _events.tryEmit(CastEvent.SessionEnded)
                }
            }
        }
    }

    private suspend fun runPendingRequest(request: CastRequest) {
        val result = execute(request)
        if (result is CastResult.Error) {
            val pageUrl = (request as? CastRequest.Remote)?.video?.takeUnless { it.isPrivate }?.pageUrl
            _events.tryEmit(CastEvent.Error(result.kind, result.message, pageUrl))
        }
    }

    /** Notes where [video] comes from: private videos never have their position saved, others their page kept. */
    private fun remember(video: DetectedVideo) {
        if (video.isPrivate) {
            privateUrls += video.url
            pagesByUrl.remove(video.url)
        } else {
            video.pageUrl?.let { pagesByUrl[video.url] = it }
        }
    }

    private suspend fun observePlayback() {
        chromecast.playback.collect { state ->
            trackProgress(state)
            trackProxiedItem(state)
            onReceiverError(state)
            ensureServiceForResumedSession(state)
        }
    }

    /** Remembers the playing item's position; saves it when the item changes and clears it when finished. */
    private fun trackProgress(state: RemotePlaybackState?) {
        val url = state?.originalUrl
        val previous = lastProgress
        if (previous != null && previous.url != url) {
            persist(previous)
            lastProgress = null
        }
        if (state == null || url == null) return
        if (state.idleReason == IDLE_REASON_FINISHED) {
            lastProgress = null
            if (finishedUrl != url) {
                finishedUrl = url
                clearPosition(url)
            }
            return
        }
        // A position seen through a suspended session is the SDK's extrapolation, not the receiver's.
        val connected = chromecast.connectionState.value == CastConnectionState.CONNECTED
        if (connected && state.playerState in ACTIVE_STATES && state.positionMs > 0) {
            if (finishedUrl == url) finishedUrl = null
            lastProgress = Progress(url, state.positionMs, state.durationMs, state.isLive)
        }
    }

    /** Notes the proxied item playing, for a [ReceiverRecovery]; a direct one clears it. */
    private fun trackProxiedItem(state: RemotePlaybackState?) {
        if (state == null || state.playerState !in ACTIVE_STATES) return
        // A state seen through a suspended session is stale: its position is the SDK's extrapolation from the last
        // status while the receiver may have stalled and failed since, so the last trusted one is kept.
        val connected = chromecast.connectionState.value == CastConnectionState.CONNECTED
        val contentUrl = state.contentUrl?.takeIf { StreamProxy.streamIdOf(it) != null }
        if (contentUrl == null) {
            if (connected) proxiedItem = null
            return
        }
        val previous = proxiedItem?.takeIf { it.contentUrl == contentUrl }
        if (!connected && previous != null) return
        proxiedItem = ProxiedItem(
            contentUrl = contentUrl,
            originalUrl = state.originalUrl,
            itemId = state.currentItemId,
            positionMs = state.positionMs,
            paused = state.playerState == RemotePlayerState.PAUSED,
            isLive = state.isLive,
            media = previous?.media,
        )
        if (previous == null) {
            // Read once per item, while the receiver still has it (a failed one may not).
            scope.launch(Dispatchers.Main.immediate) {
                val media = chromecast.currentMedia()?.takeIf { it.contentUrl == contentUrl } ?: return@launch
                proxiedItem = proxiedItem?.takeIf { it.contentUrl == contentUrl }?.copy(media = media)
            }
        }
    }

    /** The receiver failed on its item: a proxied one that may play again is reloaded, otherwise the error is told. */
    private fun onReceiverError(state: RemotePlaybackState?) {
        val current = state ?: return
        if (current.idleReason != IDLE_REASON_ERROR || loadsInFlight > 0) return
        // Seen without a connected session (stale during a suspension, or published as a session is attached):
        // looked at once it is connected (see observeConnection).
        if (chromecast.connectionState.value != CastConnectionState.CONNECTED) return
        val key = errorKey(current)
        if (SystemClock.elapsedRealtime() < suppressReceiverErrorsUntil) {
            // The failed status of a load whose error was already given: never reported later.
            lastReceiverErrorKey = key
            return
        }
        // During a recovery the key is not noted: whatever is new is looked at once it ends.
        if (recovering || key == lastReceiverErrorKey) return
        lastReceiverErrorKey = key
        // The failed item: the proxied one last seen playing, unless the receiver names another (a failed
        // status may not carry its media).
        val item = proxiedItem?.takeIf { item ->
            (current.contentUrl == null || current.contentUrl == item.contentUrl) &&
                (current.currentItemId == null || item.itemId == null || current.currentItemId == item.itemId)
        }
        val pageUrl = (current.originalUrl ?: item?.originalUrl)?.takeUnless { it in privateUrls }?.let(pagesByUrl::get)
        val streamIds = listOfNotNull((current.contentUrl ?: item?.contentUrl)?.let(StreamProxy::streamIdOf))
        val problem = proxyManager.takeRecentProblem(PROXY_PROBLEM_WINDOW_MS, streamIds)
        if (item == null || !recovery.mayRecover(connected = true, problem, proxyManager.receiverIdleMillis())) {
            reportReceiverError(problem, pageUrl)
            return
        }
        recovering = true
        scope.launch(Dispatchers.Main.immediate) {
            val outcome = try {
                recovery.recover(recoveryHost(item, streamIds), current.currentItemId, problem)
            } finally {
                recovering = false
            }
            when (outcome) {
                is ReceiverRecovery.Outcome.Failed -> {
                    noteFailedLoad()
                    reportReceiverError(outcome.problem, pageUrl)
                }
                // Ended without a report: the receiver's status is judged afresh, unless a newer load noted
                // its own failure meanwhile.
                else -> if (lastReceiverErrorKey == key) lastReceiverErrorKey = null
            }
            // Whatever the receiver came to show meanwhile (another item failing, say) is looked at as usual.
            onReceiverError(chromecast.playback.value)
        }
    }

    private fun errorKey(state: RemotePlaybackState): String = "${state.currentItemId}:${state.originalUrl}"

    /** A load whose error its caller gets: its failed status, before or after the reply, is not reported too. */
    private fun noteFailedLoad() {
        suppressReceiverErrorsUntil = SystemClock.elapsedRealtime() + RECEIVER_ERROR_SUPPRESS_MS
        chromecast.playback.value
            ?.takeIf { it.idleReason == IDLE_REASON_ERROR }
            ?.let { lastReceiverErrorKey = errorKey(it) }
    }

    private fun reportReceiverError(problem: StreamProblem?, pageUrl: String?) {
        val message = problemMessage(problem) ?: string(R.string.cast_error_playback)
        _events.tryEmit(CastEvent.Error(CastErrorKind.LOAD_FAILED, message, pageUrl))
    }

    private fun recoveryHost(item: ProxiedItem, streamIds: List<String>) = object : ReceiverRecovery.Host {
        override val connectionState get() = chromecast.connectionState
        override val playback get() = chromecast.playback.value
        override val loadGeneration get() = this@CastControllerImpl.loadGeneration
        override val loadsInFlight get() = this@CastControllerImpl.loadsInFlight

        override suspend fun reload(): Boolean? = this@CastControllerImpl.reload(item)

        override fun takeProblem(): StreamProblem? = proxyManager.takeRecentProblem(PROXY_PROBLEM_WINDOW_MS, streamIds)
    }

    /**
     * One reload of [item] at its last position: true when the receiver took it, false when it failed, null when
     * there is nothing to load.
     */
    private suspend fun reload(item: ProxiedItem): Boolean? {
        loadGeneration++
        loadsInFlight++
        proxyManager.acquire()
        try {
            proxyManager.restartIfStopped()
            val media = chromecast.currentMedia()?.takeIf { it.contentUrl == item.contentUrl } ?: item.media ?: return null
            // A live stream starts again at its live edge.
            val positionMs = if (item.isLive) 0L else item.positionMs
            Log.i(TAG, "The receiver failed on a proxied stream; loading it again at $positionMs ms")
            val result = chromecast.replaceCurrent(media, positionMs, autoplay = !item.paused)
            if (result.isFailure) Log.w(TAG, "Loading the stream again failed", result.exceptionOrNull())
            return result.isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Loading the stream again failed", e)
            return false
        } finally {
            loadsInFlight--
            proxyManager.release()
        }
    }

    /**
     * A session resumed after an app restart, one whose casting service could not be started from the
     * background, or one that outlived its service gets the service (proxy, locks, notification) back while the
     * app is visible. Not for an idle receiver: the service would stop again after its grace period.
     */
    private fun ensureServiceForResumedSession(state: RemotePlaybackState?) {
        if (state == null || state.playerState == RemotePlayerState.IDLE || serviceRequested) return
        if (chromecast.connectionState.value != CastConnectionState.CONNECTED) return
        if (!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val now = SystemClock.elapsedRealtime()
        if (now - serviceRestartedAt < SERVICE_RESTART_INTERVAL_MS) return
        serviceRestartedAt = now
        startPlaybackService()
    }

    /** Saves the position every few seconds while playing, and once when playback pauses or stops. */
    private suspend fun savePositionsWhilePlaying() {
        chromecast.playback
            .map { it?.playerState == RemotePlayerState.PLAYING }
            .distinctUntilChanged()
            .collectLatest { playing ->
                if (!playing) {
                    lastProgress?.let(::persist)
                    return@collectLatest
                }
                while (true) {
                    delay(SAVE_INTERVAL_MS)
                    lastProgress?.let(::persist)
                }
            }
    }

    private fun persist(progress: Progress) {
        val save = ResumePolicy.shouldSave(
            resumeEnabled = settings.resumePlayback,
            isLive = progress.isLive,
            isPrivate = progress.url in privateUrls,
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
        )
        if (!save) return
        scope.launch {
            try {
                positions.save(progress.url, progress.positionMs, progress.durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving the position failed", e)
            }
        }
    }

    private fun clearPosition(url: String) {
        scope.launch {
            try {
                positions.clear(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Clearing the position failed", e)
            }
        }
    }

    // endregion

    /** What the proxy saw when a proxied stream failed just now, when that explains it better than the receiver. */
    private fun problemMessage(problem: StreamProblem?): String? = when (problem?.kind) {
        StreamProblem.Kind.NOT_A_PLAYLIST -> string(R.string.cast_error_link_expired)
        StreamProblem.Kind.UNREACHABLE -> string(R.string.cast_error_unreachable)
        StreamProblem.Kind.UNSUPPORTED_ENCODING, null -> null
    }

    private fun failure(kind: CastErrorKind, @StringRes message: Int): CastResult.Error =
        CastResult.Error(kind, string(message))

    private fun string(@StringRes id: Int): String = context.getString(id)

    private companion object {
        const val TAG = "SnapcastCast"
        const val EVENT_BUFFER = 16
        const val ROUTE_DIALOG_TAG = "snapcast_cast_route_dialog"
        const val LOCAL_FILE_NAME = "video"
        const val PENDING_REQUEST_TTL_MS = 2 * 60_000L
        const val SAVE_INTERVAL_MS = 5_000L
        const val RECEIVER_ERROR_SUPPRESS_MS = 5_000L
        const val IN_BAND_TRACK_WAIT_MS = 20_000L
        const val MAX_PAGES_BY_URL = 32

        /** Shortest time between two automatic restarts of the casting service. */
        const val SERVICE_RESTART_INTERVAL_MS = 30_000L

        /** How recent a proxy problem must be to explain a receiver error. */
        const val PROXY_PROBLEM_WINDOW_MS = 30_000L
        const val IDLE_REASON_FINISHED = ReceiverQueue.IDLE_REASON_FINISHED
        const val IDLE_REASON_ERROR = ReceiverQueue.IDLE_REASON_ERROR

        /** android.Manifest.permission.ACCESS_LOCAL_NETWORK (Android 17, API 37). */
        const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        const val LOCAL_NETWORK_PERMISSION_SDK = 37
        val ACTIVE_STATES = setOf(RemotePlayerState.PLAYING, RemotePlayerState.PAUSED, RemotePlayerState.BUFFERING)
    }
}
