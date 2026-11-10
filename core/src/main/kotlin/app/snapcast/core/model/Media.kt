package app.snapcast.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

/** Container / delivery format of a detected stream. */
@Serializable
enum class StreamType(val label: String) {
    HLS("HLS"),
    DASH("DASH"),
    MP4("MP4"),
    WEBM("WebM"),
    MKV("MKV"),
    TS("TS"),
    OTHER("Video");

    /** True for manifest-based adaptive formats whose sub-resources must be rewritten by the proxy. */
    val isAdaptive: Boolean get() = this == HLS || this == DASH

    /** MIME type to announce to receivers/players when the server did not provide a usable one. */
    val defaultMimeType: String
        get() = when (this) {
            HLS -> "application/x-mpegURL"
            DASH -> "application/dash+xml"
            MP4 -> "video/mp4"
            WEBM -> "video/webm"
            MKV -> "video/x-matroska"
            TS -> "video/mp2t"
            OTHER -> "video/*"
        }
}

/**
 * HTTP request headers captured from the browser for a media request. They are replayed by
 * the proxy (and the local player) because many hosts reject requests without them.
 */
@Serializable
data class MediaHeaders(
    val referer: String? = null,
    val userAgent: String? = null,
    val origin: String? = null,
    val cookie: String? = null,
    /** Any other headers worth replaying (e.g. Authorization, X-Requested-With). */
    val extra: Map<String, String> = emptyMap(),
) {
    /** Flattened header map with canonical header names, omitting blanks. */
    fun toMap(): Map<String, String> = buildMap {
        extra.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
        referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        origin?.takeIf { it.isNotBlank() }?.let { put("Origin", it) }
        cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
    }

    val isEmpty: Boolean get() = toMap().isEmpty()

    companion object {
        val NONE = MediaHeaders()
    }
}

/** One quality level of an adaptive stream (an HLS variant or a DASH representation). */
@Serializable
data class VideoVariant(
    /** Absolute URL of the variant playlist (HLS). For DASH this is the manifest URL. */
    val url: String,
    val bandwidth: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val codecs: String? = null,
    val frameRate: Double? = null,
    val name: String? = null,
    /**
     * HLS: the variant's audio is a separate EXT-X-MEDIA rendition that only the master playlist references,
     * so this variant's playlist alone plays without sound and cannot be cast on its own.
     */
    val separateAudio: Boolean = false,
) {
    /** Human label such as "1080p", "720p · 60fps" or "2.5 Mbps". */
    val qualityLabel: String
        get() {
            val resolution = VideoResolution.label(width, height)
            val base = when {
                resolution != null -> resolution
                name != null -> name
                bandwidth != null && bandwidth > 0 -> formatBitrate(bandwidth)
                else -> "Auto"
            }
            val fps = frameRate?.takeIf { it >= 49.0 }?.let { " · ${it.toInt()}fps" } ?: ""
            return base + fps
        }

    companion object {
        fun formatBitrate(bps: Long): String = when {
            bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
            else -> "${bps / 1000} kbps"
        }
    }
}

@Serializable
enum class SubtitleFormat(val mimeType: String) {
    VTT("text/vtt"),
    SRT("application/x-subrip"),
    TTML("application/ttml+xml"),
    UNKNOWN("text/plain"),
}

@Serializable
data class SubtitleTrack(
    /** Stable id (hash of url) unique within a video. */
    val id: String,
    /**
     * Absolute http(s) URL, or a content:// / file:// URI for user-picked local files ([isLocal]). Tracks
     * found on pages or in manifests are always http(s).
     */
    val url: String,
    val label: String? = null,
    val language: String? = null,
    val format: SubtitleFormat = SubtitleFormat.UNKNOWN,
    /** Picked by the user from the phone's storage; the only tracks whose non-http URI may be read. */
    val isLocal: Boolean = false,
    /**
     * The URL is an HLS subtitle rendition (a media playlist of WebVTT segments) that the stream's master
     * playlist already delivers; it is not a file that can be side-loaded.
     */
    val isHlsRendition: Boolean = false,
)

/** Information about content protection found on a page or inside a manifest. */
@Serializable
data class DrmInfo(
    /** Key system / scheme if known, e.g. "com.widevine.alpha", "FairPlay", "PlayReady". */
    val system: String? = null,
    /** Where the signal came from, e.g. "EME request", "HLS SAMPLE-AES key", "DASH ContentProtection". */
    val reason: String,
)

@Serializable
enum class DetectionSource { NETWORK, DOM, BOTH }

/** What the page's own player is doing with a video: the one the page shows, playing or paused. */
@Serializable
enum class PagePlayback { PLAYING, PAUSED }

/** A video stream found while browsing. Immutable; updates produce copies. */
@Serializable
data class DetectedVideo(
    /** Stable id derived from the normalized URL (see [app.snapcast.core.util.UrlUtils.stableId]). */
    val id: String,
    val tabId: String,
    val url: String,
    val type: StreamType,
    val mimeType: String? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    /** Best title from the <video> element / media session / og:title; falls back to [pageTitle]. */
    val title: String? = null,
    val headers: MediaHeaders = MediaHeaders.NONE,
    /** Cookie headers seen for other hosts the stream uses (segment CDNs), keyed by host. */
    val cookiesByHost: Map<String, String> = emptyMap(),
    val sizeBytes: Long? = null,
    val durationSec: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val posterUrl: String? = null,
    /**
     * Referer to send when fetching [posterUrl]: what the browser sent from the document that showed the
     * image (the player's frame for an element poster, the page for og:image). Null when none should be sent.
     */
    val posterReferer: String? = null,
    /**
     * A frame of the video captured in the page on request (a small `data:image/jpeg;base64,` URL), shown
     * when there is no poster or it cannot be loaded. Never sent anywhere.
     */
    @Transient val frameThumbnail: String? = null,
    val variants: List<VideoVariant> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val isLive: Boolean = false,
    /** For HLS: true when segments are fragmented MP4 (EXT-X-MAP present), false for MPEG-TS, null if unknown. */
    val hlsFragmentedMp4: Boolean? = null,
    /** True when this is an HLS master playlist (has variants) rather than a media playlist. */
    val isMasterPlaylist: Boolean = false,
    val drm: DrmInfo? = null,
    val source: DetectionSource = DetectionSource.NETWORK,
    val detectedAt: Long = 0L,
    val isPrivate: Boolean = false,
    /** Set on the video the page's player plays (or paused last), which the list shows first. */
    @Transient val pagePlayback: PagePlayback? = null,
) {
    val isDrmProtected: Boolean get() = drm != null

    /** Display title, never blank. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: pageTitle?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').substringBefore('?').ifBlank { url }

    /** Host of the media URL, without "www.". */
    val sourceDomain: String
        get() = app.snapcast.core.util.UrlUtils.hostOf(url)?.removePrefix("www.") ?: ""

    /**
     * Best quality label: for adaptive streams the best variant (see [hasQualityRange]), null while the
     * variants are unknown; the file's own size otherwise. The page player's size is never used for an
     * adaptive stream: it is only the rendition that player happens to be decoding.
     */
    val qualityLabel: String?
        get() = if (type.isAdaptive) bestVariant?.qualityLabel else VideoResolution.label(width, height)

    /**
     * True when an adaptive stream offers several qualities: players and receivers switch between them
     * on their own, so [qualityLabel] is the best one they can reach, not a fixed quality.
     */
    val hasQualityRange: Boolean
        get() = type.isAdaptive && variants.filter { it.isDescribed }.distinctBy { it.qualityLabel }.size > 1

    private val bestVariant: VideoVariant?
        get() = variants.filter { it.isDescribed }.maxWithOrNull(
            compareBy<VideoVariant>({ VideoResolution.classOf(it.width, it.height) ?: 0 }, { it.bandwidth ?: 0L }),
        )
}

/** Variant that [VideoVariant.qualityLabel] can describe by more than "Auto". */
private val VideoVariant.isDescribed: Boolean
    get() = VideoResolution.classOf(width, height) != null || (bandwidth ?: 0L) > 0L || name != null

/** Resolution classes ("1080p") from a frame size, whatever the orientation or aspect ratio. */
object VideoResolution {
    private val CLASSES = intArrayOf(4320, 2160, 1440, 1080, 720, 576, 480, 360, 240, 144)

    /**
     * The usual name of a [width] x [height] frame, snapped to a standard class when within 6 %. A frame up to 16:9
     * wide is named by its short side (1920x1080, 1080x1920, 1440x1080 and 1024x768 are 1080, 1080, 1080 and 768).
     * A wider one (scope, DCI) is named by its width: the height a 16:9 frame as wide would have, or for one at least
     * as wide as DCI (256:135) a DCI frame (1920x800, 2048x1080 and 2048x858 are all 1080; 1366x768 is 768). A frame
     * with only a height known uses that height.
     */
    fun classOf(width: Int?, height: Int?): Int? {
        val w = width?.takeIf { it > 0 }
        val h = height?.takeIf { it > 0 }
        if (w == null || h == null) return h?.let { snap(it) ?: it }
        val short = minOf(w, h)
        val long = maxOf(w, h)
        if (long * 9 <= short * 16) return snap(short) ?: short
        val sixteenNine = long * 9 / 16
        val dci = if (long * 135 >= short * 256) snap(long * 135 / 256) else null
        return snap(sixteenNine) ?: dci ?: snap(short) ?: sixteenNine
    }

    private fun snap(lines: Int): Int? = CLASSES.firstOrNull { kotlin.math.abs(lines - it) <= it * 6 / 100 }

    fun label(width: Int?, height: Int?): String? = classOf(width, height)?.let { "${it}p" }
}
