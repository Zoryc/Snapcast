package app.snapcast.cast

import kotlinx.coroutines.flow.StateFlow

enum class CastConnectionState {
    /** Google Play services / Cast framework not available on this device. */
    UNAVAILABLE,

    /** No receiver found on the network. */
    NO_DEVICES,

    NOT_CONNECTED,
    CONNECTING,
    CONNECTED,
}

data class CastDeviceInfo(
    val name: String,
    val model: String?,
)

enum class RemotePlayerState { IDLE, LOADING, BUFFERING, PLAYING, PAUSED }

data class CastTextTrack(
    val id: Long,
    val name: String?,
    val language: String?,
)

data class CastQueueItem(
    val itemId: Int,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    /** Original page media URL (not the proxied one). */
    val originalUrl: String?,
)

data class RemotePlaybackState(
    val title: String?,
    val subtitle: String?,
    val posterUrl: String?,
    val playerState: RemotePlayerState,
    val positionMs: Long,
    val durationMs: Long,
    val isLive: Boolean,
    val isSeekable: Boolean,
    /** 0.0..1.0 */
    val volume: Double,
    val isMuted: Boolean,
    val playbackRate: Double,
    val supportsPlaybackRate: Boolean,
    val textTracks: List<CastTextTrack>,
    val activeTextTrackId: Long?,
    val queue: List<CastQueueItem>,
    val currentItemId: Int?,
    /** IdleReason name when idle after an error/finish ("FINISHED", "ERROR", ...). */
    val idleReason: String?,
    val originalUrl: String?,
    /** The URL the receiver loaded for the current item (the proxy's URL for a proxied stream). */
    val contentUrl: String? = null,
) {
    /**
     * Nothing is left to play on the receiver: playback was stopped (CANCELED), or the last item finished or
     * failed. IDLE without a reason and INTERRUPTED are brief states while a load replaces the current item.
     * Screens show such a receiver as casting nothing (there is nothing a play button could resume).
     */
    val isEnded: Boolean
        get() {
            if (playerState != RemotePlayerState.IDLE) return false
            return when (idleReason) {
                "CANCELED" -> true
                "FINISHED", "ERROR" -> {
                    val index = queue.indexOfFirst { it.itemId == currentItemId }
                    index < 0 || index == queue.lastIndex
                }
                else -> false
            }
        }
}

/** Text track to attach to a [CastMedia]; [url] must be reachable by the receiver (usually the proxy). */
data class CastSubtitle(
    val trackId: Long,
    val url: String,
    val language: String?,
    val name: String?,
)

enum class HlsSegmentFormat { TS, FMP4, AAC }

/** Receiver-ready description of one item. */
data class CastMedia(
    /** URL the receiver loads (proxy URL or the original when casting directly). */
    val contentUrl: String,
    val contentType: String,
    val isLive: Boolean,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
    val durationMs: Long?,
    val textTracks: List<CastSubtitle> = emptyList(),
    /** Track enabled right after loading, if any. */
    val activeTextTrackId: Long? = null,
    val hlsSegmentFormat: HlsSegmentFormat? = null,
    /** Original page media URL; used for resume positions and queue bookkeeping. */
    val originalUrl: String,
)

/**
 * A remote playback device family (Chromecast today; DLNA / Roku / Fire TV can be added by implementing
 * this interface). All control calls are fire-and-forget and safe to call when not connected (no-op).
 */
interface CastTarget {
    val id: String

    val displayName: String

    val connectionState: StateFlow<CastConnectionState>

    val device: StateFlow<CastDeviceInfo?>

    /** Null when nothing is loaded on the receiver. */
    val playback: StateFlow<RemotePlaybackState?>

    /**
     * True once the last session was ended on purpose (a Stop that also stopped the receiver, or the receiver
     * app closing), so the receiver fetches nothing more. False while a session runs and after one was lost (a
     * network error, the phone no longer seeing the device): the receiver may then go on playing on its own.
     */
    val sessionEndedOnPurpose: StateFlow<Boolean>

    /** Replaces the receiver queue with [items] and starts [startIndex] at [startPositionMs]. */
    suspend fun load(items: List<CastMedia>, startIndex: Int = 0, startPositionMs: Long = 0, autoplay: Boolean = true): Result<Unit>

    suspend fun appendToQueue(items: List<CastMedia>): Result<Unit>

    /** Inserts after the current item. */
    suspend fun insertNext(item: CastMedia): Result<Unit>

    fun play()

    fun pause()

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    fun stop()

    fun setVolume(level: Double)

    fun setMuted(muted: Boolean)

    fun setPlaybackRate(rate: Double)

    /** Enables a text track by id, or disables subtitles with null. */
    fun setActiveTextTrack(trackId: Long?)

    fun moveQueueItem(itemId: Int, toIndex: Int)

    fun removeQueueItem(itemId: Int)

    fun jumpToQueueItem(itemId: Int)

    fun skipNext()

    fun skipPrevious()

    /** Ends the session; [stopCasting] also stops playback on the receiver. */
    fun disconnect(stopCasting: Boolean = true)
}
