package app.snapcast.proxy.server

import app.snapcast.proxy.http.Base64Url
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

class UrlSignerTest {
    private val signer = UrlSigner("test-secret".toByteArray())
    private val encoded = Base64Url.encode("https://cdn.example/video/seg-1.ts?token=abc")

    @Test
    fun `signature round trip verifies`() {
        val signature = signer.sign("u", "abc123", encoded)
        assertThat(signer.verify("u", "abc123", encoded, signature)).isTrue()
    }

    @Test
    fun `signature is 16 bytes of unpadded base64url`() {
        val signature = signer.sign("u", "abc123", encoded)
        assertThat(signature).hasLength(22)
        assertThat(signature).matches("[A-Za-z0-9_-]{22}")
    }

    @Test
    fun `tampering with any part fails verification`() {
        val signature = signer.sign("u", "abc123", encoded)
        val flipped = (if (signature[0] == 'A') 'B' else 'A') + signature.substring(1)
        assertThat(signer.verify("u", "abc123", encoded, flipped)).isFalse()
        assertThat(signer.verify("u", "abc124", encoded, signature)).isFalse()
        assertThat(signer.verify("u", "abc123", Base64Url.encode("https://evil.example/"), signature)).isFalse()
        assertThat(signer.verify("u", "abc123", encoded, signature.dropLast(1))).isFalse()
        assertThat(signer.verify("u", "abc123", encoded, null)).isFalse()
        assertThat(signer.verify("u", "abc123", encoded, "")).isFalse()
    }

    @Test
    fun `signatures depend on the secret`() {
        val other = UrlSigner("another-secret".toByteArray())
        val signature = signer.sign("u", "abc123", encoded)
        assertThat(other.sign("u", "abc123", encoded)).isNotEqualTo(signature)
        assertThat(other.verify("u", "abc123", encoded, signature)).isFalse()
    }

    @Test
    fun `empty secret is rejected`() {
        assertFailsWith<IllegalArgumentException> { UrlSigner(ByteArray(0)) }
    }

    @Test
    fun `exact routes carry a verifiable signature and the encoded url`() {
        val routes = ProxyRoutes(signer)
        val url = "https://cdn.example/a b/seg-1.ts?x=1"
        val path = routes.exact("id1", url)
        val parts = path.split('/')
        assertThat(parts[1]).isEqualTo("u")
        assertThat(parts[2]).isEqualTo("id1")
        assertThat(signer.verify("u", "id1", parts[4], parts[3])).isTrue()
        assertThat(signer.verify("b", "id1", parts[4], parts[3])).isFalse()
        assertThat(Base64Url.decodeToString(parts[4])).isEqualTo(url)
        assertThat(parts[5]).isEqualTo("seg-1.ts")
    }

    @Test
    fun `base routes end with a slash and verify`() {
        val routes = ProxyRoutes(signer)
        val path = routes.base("id1", "https://cdn.example/dash/")
        assertThat(path).endsWith("/")
        val parts = path.split('/')
        assertThat(parts[1]).isEqualTo("b")
        assertThat(signer.verify("b", "id1", parts[4], parts[3])).isTrue()
        assertThat(signer.verify("u", "id1", parts[4], parts[3])).isFalse()
        assertThat(Base64Url.decodeToString(parts[4])).isEqualTo("https://cdn.example/dash/")
    }
}
