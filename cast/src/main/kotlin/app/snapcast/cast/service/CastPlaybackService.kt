package app.snapcast.cast.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.media.AudioManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import app.snapcast.cast.CastDeviceInfo
import app.snapcast.cast.CastTarget
import app.snapcast.cast.R
import app.snapcast.cast.RemotePlaybackState
import app.snapcast.cast.RemotePlayerState
import app.snapcast.cast.proxy.ProxyManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import kotlin.math.abs

/**
 * Foreground service (type `mediaPlayback`) running while something is cast. It keeps the local proxy
 * alive (a [ProxyManager] reference), holds Wi-Fi and partial wake locks while the receiver plays
 * ([WakeLockPolicy]), and owns the media notification and the [MediaSessionCompat] (lock-screen controls,
 * remote volume) mirrored from [CastTarget.playback].
 *
 * Started by the cast controller right before it sends a load (while the app is still in the foreground);
 * stops itself when the session is ended, when nothing has been loaded for [ServiceStopPolicy.GRACE_MS], or,
 * when the session was lost, once no receiver has used the proxy for [ServiceStopPolicy.RECEIVER_IDLE_MS]
 * (timing in [CastServiceKeeper]). Works without the notification permission (the service still runs; the
 * notification is just not shown).
 */
@AndroidEntryPoint
class CastPlaybackService : Service() {

    @Inject
    internal lateinit var target: CastTarget

    @Inject
    internal lateinit var proxyManager: ProxyManager

    @Inject
    internal lateinit var okHttpClient: OkHttpClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: CastNotificationFactory
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var volumeProvider: RemoteVolumeProvider
    private lateinit var locks: CastLocks
    private lateinit var keeper: CastServiceKeeper
    private lateinit var artwork: ArtworkLoader

    private var holdsProxy = false
    private var stopping = false
    private var stopPolicyJob: Job? = null
    private var lastStartId = 0
    private var lastContent: CastNotificationContent? = null

    /** What the receiver last reported, shown while the session is lost (it may play on meanwhile). */
    private var lastKnownPlayback: RemotePlaybackState? = null
    private var lastKnownDevice: CastDeviceInfo? = null

    /** The session is lost and [lastKnownPlayback] is shown: the transport controls and volume reach nothing. */
    private var sessionLost = false
    private var lastMetadata: MetadataKey? = null
    private var lastSessionState: SessionStateKey? = null
    private var lastSessionPositionMs = 0L
    private var lastSessionUpdateAt = 0L

    /** Fields the session metadata is built from. */
    private data class MetadataKey(
        val title: String?,
        val subtitle: String?,
        val durationMs: Long,
        val posterUrl: String?,
    )

    /** Session playback state apart from the position, which the system extrapolates from the speed. */
    private data class SessionStateKey(val state: Int, val actions: Long, val speed: Float, val controllable: Boolean)

    override fun onCreate() {
        super.onCreate()
        _running.value = true
        notifications = CastNotificationFactory(this).also { it.ensureChannel() }
        artwork = ArtworkLoader(okHttpClient)
        locks = CastLocks(this)
        volumeProvider = RemoteVolumeProvider(VolumeSteps.toSteps(target.playback.value?.volume ?: 1.0))
        mediaSession = MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(SessionCallback())
            notifications.sessionActivity()?.let { setSessionActivity(it) }
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }
        proxyManager.acquire()
        holdsProxy = true
        // A session that outlived an earlier service is served again by the same proxy (same URLs).
        scope.launch { proxyManager.restartIfStopped() }
        locks.acquire()
        keeper = CastServiceKeeper(
            scope = scope,
            connectionState = target.connectionState,
            playerState = target.playback.map { it?.playerState },
            endedOnPurpose = target.sessionEndedOnPurpose,
            receiverIdleMillis = proxyManager::receiverIdleMillis,
            receiverActive = proxyManager.receiverActive,
            locks = locks,
            onStop = { reason ->
                Log.i(TAG, "Stopping: $reason")
                shutdown()
            },
        )
        observe()
        observeLockPolicy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        stopping = false
        startInForeground()
        // Only once in the foreground may the service decide to stop (stopping earlier would break the
        // startForegroundService contract). Each start re-evaluates the decision.
        stopPolicyJob?.cancel()
        stopPolicyJob = observeStopPolicy()
        when (intent?.action) {
            ACTION_TOGGLE_PLAYBACK -> target.togglePlayPause()
            ACTION_REWIND -> target.seekBy(-SKIP_MS)
            ACTION_FORWARD -> target.seekBy(SKIP_MS)
            ACTION_STOP -> target.disconnect(stopCasting = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        if (::locks.isInitialized) locks.release()
        if (holdsProxy) {
            holdsProxy = false
            proxyManager.release()
        }
        _running.value = false
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            combine(target.playback, target.device) { playback, device -> playback to device }
                .collect { (playback, device) ->
                    // Without a session the receiver may play on from the proxy: the notification and the media
                    // session keep its last state rather than going blank (Android 16 demotes the foreground service
                    // of a media session that stays inactive for 10 minutes).
                    if (playback != null) lastKnownPlayback = playback
                    if (device != null) lastKnownDevice = device
                    val lost = playback == null && lastKnownPlayback != null
                    if (lost != sessionLost) {
                        sessionLost = lost
                        // The volume keys go back to the phone meanwhile: a remote volume would move a slider
                        // that changes nothing.
                        if (lost) {
                            mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
                        } else {
                            mediaSession.setPlaybackToRemote(volumeProvider)
                        }
                    }
                    render(playback ?: lastKnownPlayback, device ?: lastKnownDevice, controllable = !lost)
                }
        }
    }

    private fun observeLockPolicy() {
        keeper.observeLocks()
    }

    private fun observeStopPolicy(): Job = keeper.observeStop()

    // region Rendering

    private fun render(playback: RemotePlaybackState?, device: CastDeviceInfo?, controllable: Boolean) {
        updatePlaybackState(playback, controllable)
        if (playback != null) {
            val steps = VolumeSteps.toSteps(playback.volume)
            if (volumeProvider.currentVolume != steps) volumeProvider.currentVolume = steps
        }
        updateMetadata(playback)
        val content = notificationContent(playback, device, controllable)
        if (content != lastContent) {
            lastContent = content
            postNotification(content)
        }
    }

    private fun notificationContent(
        playback: RemotePlaybackState?,
        device: CastDeviceInfo?,
        controllable: Boolean = true,
    ): CastNotificationContent =
        CastNotificationContent(
            title = playback?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.cast_notification_title_default),
            text = when {
                !controllable -> device?.let { getString(R.string.cast_notification_not_connected_to, it.name) }
                    ?: getString(R.string.cast_notification_not_connected)
                else -> device?.let { getString(R.string.cast_notification_casting_to, it.name) }
                    ?: getString(R.string.cast_notification_casting)
            },
            subText = playback?.subtitle,
            isPlaying = playback?.playerState == RemotePlayerState.PLAYING ||
                playback?.playerState == RemotePlayerState.BUFFERING,
            isSeekable = playback?.isSeekable == true,
            artwork = artwork.cached(playback?.posterUrl),
            controllable = controllable,
        )

    /**
     * Mirrors the receiver state into the media session. Position-only changes are skipped while they match
     * the system's extrapolation (position + elapsed time x speed), so the session is not updated every second.
     * While the session is lost (not [controllable]) the last state is kept, so the session stays active, with
     * only Stop.
     */
    private fun updatePlaybackState(playback: RemotePlaybackState?, controllable: Boolean) {
        val state = when (playback?.playerState) {
            RemotePlayerState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            RemotePlayerState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            RemotePlayerState.BUFFERING, RemotePlayerState.LOADING -> PlaybackStateCompat.STATE_BUFFERING
            RemotePlayerState.IDLE -> PlaybackStateCompat.STATE_STOPPED
            null -> PlaybackStateCompat.STATE_NONE
        }
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        if (playback?.isSeekable == true) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
        }
        if (!controllable) actions = PlaybackStateCompat.ACTION_STOP
        if (controllable && playback != null) {
            val index = playback.queue.indexOfFirst { it.itemId == playback.currentItemId }
            if (index >= 0 && index < playback.queue.lastIndex) {
                actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            }
            if (index > 0) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) playback?.playbackRate?.toFloat() ?: 1f else 0f
        val positionMs = playback?.positionMs ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        val now = SystemClock.elapsedRealtime()
        // The custom actions below depend only on isSeekable and the skip bits, which are part of actions.
        val key = SessionStateKey(state, actions, speed, controllable)
        val expectedMs = lastSessionPositionMs + ((now - lastSessionUpdateAt) * speed).toLong()
        if (key == lastSessionState && abs(expectedMs - positionMs) < POSITION_DRIFT_MS) return
        lastSessionState = key
        lastSessionPositionMs = positionMs
        lastSessionUpdateAt = now
        val builder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, positionMs, speed, now)
        // Android 13+ builds the shade and lock-screen controls from the session, not from the notification's
        // actions: only play/pause, previous/next and custom actions get buttons (ACTION_STOP, REWIND and
        // FAST_FORWARD don't). Custom actions fill the previous slot, the next slot, then two extra slots, so
        // with both previous and next present only two fit: Stop is kept. Older versions ignore them.
        val skipBoth = PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (controllable && playback?.isSeekable == true && (actions and skipBoth) != skipBoth) {
            builder.addCustomAction(ACTION_REWIND, getString(R.string.cast_action_rewind), R.drawable.ic_cast_replay_10)
            builder.addCustomAction(ACTION_FORWARD, getString(R.string.cast_action_forward), R.drawable.ic_cast_forward_10)
        }
        builder.addCustomAction(ACTION_STOP, getString(R.string.cast_action_stop), R.drawable.ic_cast_stop)
        mediaSession.setPlaybackState(builder.build())
    }

    private fun updateMetadata(playback: RemotePlaybackState?) {
        val key = MetadataKey(playback?.title, playback?.subtitle, playback?.durationMs ?: 0L, playback?.posterUrl)
        if (key == lastMetadata) return
        lastMetadata = key
        mediaSession.setMetadata(metadata(key))
        val posterUrl = key.posterUrl ?: return
        if (artwork.cached(posterUrl) != null) return
        scope.launch {
            val bitmap = artwork.load(posterUrl) ?: return@launch
            if (lastMetadata != key) return@launch
            mediaSession.setMetadata(metadata(key))
            lastContent?.let { previous ->
                val updated = previous.copy(artwork = bitmap)
                lastContent = updated
                postNotification(updated)
            }
        }
    }

    private fun metadata(key: MetadataKey): MediaMetadataCompat {
        val builder = MediaMetadataCompat.Builder()
        val title = key.title?.takeIf { it.isNotBlank() } ?: getString(R.string.cast_notification_title_default)
        builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
        key.subtitle?.let {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, it)
        }
        if (key.durationMs > 0) builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, key.durationMs)
        artwork.cached(key.posterUrl)?.let {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
        }
        return builder.build()
    }

    // endregion

    // region Foreground and notification

    private fun startInForeground() {
        val content = lastContent
            ?: notificationContent(target.playback.value, target.device.value).also { lastContent = it }
        val notification = notifications.build(content, mediaSession.sessionToken)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, CastNotificationFactory.NOTIFICATION_ID, notification, type)
        } catch (e: RuntimeException) {
            // E.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.w(TAG, "Couldn't enter the foreground", e)
            stopSelf()
        }
    }

    private fun postNotification(content: CastNotificationContent) {
        if (stopping) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val notification = notifications.build(content, mediaSession.sessionToken)
            NotificationManagerCompat.from(this).notify(CastNotificationFactory.NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Posting the notification failed", e)
        }
    }

    /** Leaves the foreground and stops, unless a newer start request arrived meanwhile. */
    private fun shutdown() {
        if (stopping) return
        stopping = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // A newer start request is pending: its onStartCommand re-enters the foreground and re-evaluates.
        if (!stopSelfResult(lastStartId)) stopping = false
    }

    // endregion

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() = target.play()

        override fun onPause() = target.pause()

        override fun onSeekTo(pos: Long) = target.seekTo(pos)

        override fun onFastForward() = target.seekBy(SKIP_MS)

        override fun onRewind() = target.seekBy(-SKIP_MS)

        override fun onSkipToNext() = target.skipNext()

        override fun onSkipToPrevious() = target.skipPrevious()

        override fun onStop() = target.stop()

        /** The Android 13+ shade / lock-screen buttons ([updatePlaybackState]). */
        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ACTION_REWIND -> target.seekBy(-SKIP_MS)
                ACTION_FORWARD -> target.seekBy(SKIP_MS)
                // Same as the notification's Stop: ends the session (target.stop() would keep it connected).
                ACTION_STOP -> target.disconnect(stopCasting = true)
            }
        }
    }

    /** Hardware volume keys and the system volume UI control the receiver volume in [VolumeSteps]. */
    private inner class RemoteVolumeProvider(initialSteps: Int) :
        VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE, VolumeSteps.MAX, initialSteps) {

        override fun onSetVolumeTo(volume: Int) = applySteps(volume.coerceIn(0, VolumeSteps.MAX))

        override fun onAdjustVolume(direction: Int) = applySteps(VolumeSteps.adjust(currentVolume, direction))

        private fun applySteps(steps: Int) {
            currentVolume = steps
            target.setVolume(VolumeSteps.toLevel(steps))
        }
    }

    companion object {
        private val _running = MutableStateFlow(false)

        /** Whether the service exists (it holds the proxy): the controller restarts it for a session that outlived it. */
        internal val running: StateFlow<Boolean> = _running.asStateFlow()

        internal const val ACTION_TOGGLE_PLAYBACK = "app.snapcast.cast.action.TOGGLE_PLAYBACK"
        internal const val ACTION_REWIND = "app.snapcast.cast.action.REWIND"
        internal const val ACTION_FORWARD = "app.snapcast.cast.action.FORWARD"
        internal const val ACTION_STOP = "app.snapcast.cast.action.STOP"

        private const val TAG = "SnapcastCast"
        private const val MEDIA_SESSION_TAG = "SnapcastCast"
        private const val SKIP_MS = 10_000L
        private const val POSITION_DRIFT_MS = 1_500L
    }
}
