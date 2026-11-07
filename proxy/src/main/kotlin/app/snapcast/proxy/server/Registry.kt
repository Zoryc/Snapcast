package app.snapcast.proxy.server

import app.snapcast.proxy.LocalSource
import app.snapcast.proxy.ProxyStreamRequest
import app.snapcast.proxy.SubtitleSource
import java.security.SecureRandom

/** Something the proxy serves under a random id. */
internal sealed interface Registration {
    class Stream(val request: ProxyStreamRequest, val policy: HeaderPolicy) : Registration {
        /** EXT-X-DEFINE variables of the stream's main playlist, which its media playlists may IMPORT. */
        @Volatile
        var mainVariables: Map<String, String> = emptyMap()
    }

    class Local(val source: LocalSource) : Registration

    /** [policy] holds the request headers of a [SubtitleSource.Remote]. */
    class Subtitle(val source: SubtitleSource, val policy: HeaderPolicy?) : Registration
}

/**
 * Thread-safe id → [Registration] map. Ids are 128-bit random values in hex. Registrations outlive casts (a
 * receiver may come back to a stream after the phone lost its session), so only the [MAX_ENTRIES] most recent
 * are kept.
 */
internal class Registry {
    private val entries = object : LinkedHashMap<String, Registration>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Registration>?): Boolean = size > MAX_ENTRIES
    }
    private val random = SecureRandom()

    /** Stores [registration] under a fresh id and returns it. */
    fun add(registration: Registration): String {
        while (true) {
            val id = newId()
            synchronized(entries) {
                if (id !in entries) {
                    entries[id] = registration
                    return id
                }
            }
        }
    }

    fun stream(id: String?): Registration.Stream? = get(id) as? Registration.Stream

    fun local(id: String?): Registration.Local? = get(id) as? Registration.Local

    fun subtitle(id: String?): Registration.Subtitle? = get(id) as? Registration.Subtitle

    val size: Int get() = synchronized(entries) { entries.size }

    fun remove(id: String) {
        synchronized(entries) { entries.remove(id) }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    private fun get(id: String?): Registration? = id?.let { synchronized(entries) { entries[it] } }

    private fun newId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return buildString(32) {
            for (byte in bytes) {
                val b = byte.toInt() and 0xff
                append(HEX[b ushr 4]).append(HEX[b and 0x0f])
            }
        }
    }

    companion object {
        const val MAX_ENTRIES = 256
        private const val HEX = "0123456789abcdef"
    }
}
