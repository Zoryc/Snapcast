package app.snapcast.proxy.server

import app.snapcast.proxy.http.Base64Url
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs sub-resource routes so the proxy only fetches URLs it produced itself (it would otherwise be an open
 * relay carrying the user's cookies and headers): `sig = base64url(HMAC-SHA256(secret, id + "|" + encodedUrl))`
 * truncated to 16 bytes, without padding. The route kind is signed too (`route + "|" + id + "|" + encodedUrl`), so the
 * signature of one exact URL cannot be replayed as a base, which would open every file next to that URL.
 */
internal class UrlSigner(secret: ByteArray) {
    private val key: SecretKeySpec

    /** Mac instances are not thread-safe; a playlist rewrite signs every URI on the same connection thread. */
    private val mac: ThreadLocal<Mac>

    init {
        require(secret.isNotEmpty()) { "The signing secret must not be empty" }
        key = SecretKeySpec(secret.copyOf(), ALGORITHM)
        mac = ThreadLocal.withInitial { Mac.getInstance(ALGORITHM).apply { init(key) } }
    }

    fun sign(route: String, id: String, encodedUrl: String): String {
        // doFinal resets the Mac for the next use.
        val digest = mac.get().doFinal("$route|$id|$encodedUrl".toByteArray(Charsets.UTF_8))
        return Base64Url.encode(digest.copyOf(SIGNATURE_BYTES))
    }

    /** Constant-time check of [signature] for [route], [id] and [encodedUrl]. */
    fun verify(route: String, id: String, encodedUrl: String, signature: String?): Boolean {
        if (signature.isNullOrEmpty()) return false
        val expected = sign(route, id, encodedUrl).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, signature.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val SIGNATURE_BYTES = 16
    }
}
