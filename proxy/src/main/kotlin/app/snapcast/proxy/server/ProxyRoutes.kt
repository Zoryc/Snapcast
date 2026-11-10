package app.snapcast.proxy.server

import app.snapcast.core.model.StreamType
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.util.UrlUtils
import app.snapcast.proxy.http.Base64Url
import app.snapcast.proxy.rewrite.ProxyUrlMapper

/** Builds the root-relative, signed proxy paths. */
internal class ProxyRoutes(private val signer: UrlSigner) {

    fun stream(id: String, name: String): String = "/$STREAM/$id/$name"

    fun local(id: String, name: String): String = "/$LOCAL/$id/$name"

    fun subtitle(id: String): String = "/$SUBTITLE/$id.vtt"

    /** `/u/{id}/{sig}/{b64url}/{name}`: serves exactly [absoluteUrl]; the name only helps receivers guess the type. */
    fun exact(id: String, absoluteUrl: String): String {
        val encoded = Base64Url.encode(absoluteUrl)
        return "/$EXACT/$id/${signer.sign(EXACT, id, encoded)}/$encoded/${fileHint(absoluteUrl)}"
    }

    /** `/b/{id}/{sig}/{b64base}/`: references appended to it are resolved against [baseDir]. */
    fun base(id: String, baseDir: String): String {
        val encoded = Base64Url.encode(baseDir)
        return "/$BASE/$id/${signer.sign(BASE, id, encoded)}/$encoded/"
    }

    fun mapperFor(id: String): ProxyUrlMapper = object : ProxyUrlMapper {
        override fun exact(absoluteUrl: String): String = this@ProxyRoutes.exact(id, absoluteUrl)

        override fun base(baseDir: String): String = this@ProxyRoutes.base(id, baseDir)
    }

    companion object {
        const val STREAM = "stream"
        const val EXACT = "u"
        const val BASE = "b"
        const val LOCAL = "local"
        const val SUBTITLE = "sub"
        const val HEALTH = "health"

        private const val MAX_NAME_LENGTH = 80
        private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]+")

        /** File name for the main resource of a stream: index.m3u8, manifest.mpd, the URL's name or video.<ext>. */
        fun streamName(url: String, type: StreamType?): String = when (type) {
            StreamType.HLS -> "index.m3u8"
            StreamType.DASH -> "manifest.mpd"
            else -> sanitizeName(UrlUtils.fileNameOf(url))
                ?: "video." + (UrlUtils.extensionOf(url) ?: defaultExtension(type))
        }

        /** Last path segment of [url], sanitized, used as the trailing name of exact routes. */
        fun fileHint(url: String): String = sanitizeName(UrlUtils.fileNameOf(url)) ?: "r"

        /** A path-safe file name: only `[A-Za-z0-9._-]`, at most 80 chars (keeping the extension); null if empty. */
        fun sanitizeName(name: String?): String? {
            val cleaned = name?.replace(UNSAFE_NAME_CHARS, "_")?.trim('_')?.takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
                ?: return null
            if (cleaned == "." || cleaned == "..") return null
            return if (cleaned.length <= MAX_NAME_LENGTH) cleaned else cleaned.takeLast(MAX_NAME_LENGTH)
        }

        private fun defaultExtension(type: StreamType?): String = when (type) {
            StreamType.WEBM -> "webm"
            StreamType.MKV -> "mkv"
            StreamType.TS -> "ts"
            else -> "mp4"
        }

        /** Stream type of a registered URL: the hint, else what the URL says. */
        fun typeOf(url: String, hint: StreamType?): StreamType? = hint ?: MediaClassifier.typeFromUrl(url)
    }
}
