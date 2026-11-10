package app.snapcast.detection.inspect

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.util.UrlUtils
import java.util.TreeMap

/** The browser headers replayed when the app fetches a detected stream itself. */
internal object InspectionHeaders {
    /** Hop-by-hop and request-specific headers OkHttp manages itself. */
    private val NOT_REPLAYED = setOf(
        "host",
        "connection",
        "content-length",
        "transfer-encoding",
        "accept-encoding",
        "range",
        "if-range",
        "if-none-match",
        "if-modified-since",
        "te",
        "upgrade",
        "keep-alive",
        "expect",
        "proxy-authorization",
    )

    /**
     * Headers for fetching [targetUrl] on behalf of [video] (its own URL by default): Referer,
     * User-Agent, Origin and extra headers as captured; the Cookie seen for the target's host (the
     * video's own cookie only on its own host); Authorization only on the video's host. Neither goes
     * over plain http when the video or its page was loaded over https: the browser would not send them.
     */
    fun forRequest(video: DetectedVideo, targetUrl: String = video.url): Map<String, String> {
        val targetHost = UrlUtils.hostOf(targetUrl)
        val sameHost = targetHost == UrlUtils.hostOf(video.url)
        val cleartext = !isHttps(targetUrl) && (isHttps(video.url) || isHttps(video.pageUrl))
        val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for ((name, value) in video.headers.toMap()) {
            val lower = name.lowercase()
            if (lower in NOT_REPLAYED || lower == "cookie" || value.isBlank()) continue
            if (lower == "authorization" && (!sameHost || cleartext)) continue
            headers[name] = value
        }
        val cookie = targetHost?.let { video.cookiesByHost[it] } ?: video.headers.cookie.takeIf { sameHost }
        if (!cookie.isNullOrBlank() && !cleartext) headers["Cookie"] = cookie
        return headers
    }

    private fun isHttps(url: String?): Boolean = url?.startsWith("https://", ignoreCase = true) == true
}
