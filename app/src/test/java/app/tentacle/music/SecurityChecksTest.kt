// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityChecksTest {

    // ---- id validation (path traversal) ----

    @Test
    fun acceptsJellyfinIds() {
        requireSafeId("0123456789abcdef0123456789abcdef")
        requireSafeId("01234567-89ab-cdef-0123-456789abcdef")
    }

    @Test
    fun rejectsTraversalAndInjection() {
        listOf("", "..", "../Users", "a/b", "a%2Fb", "abc?x=1", "a,b", "a b", "x".repeat(65)).forEach {
            assertThrows(it) { requireSafeId(it) }
        }
    }

    @Test
    fun isSafeIdMatchesRequireSafeId() {
        assertTrue(isSafeId("0123456789abcdef0123456789abcdef"))
        assertFalse(isSafeId("../x"))
        assertFalse(isSafeId(""))
    }

    // ---- LAN detection (cleartext warning, default scheme) ----

    @Test
    fun localHosts() {
        listOf(
            "192.168.1.10", "10.0.0.5", "172.16.0.1", "172.31.255.255", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "localhost", "jellyfin", "nas.local", "media.lan", "box.home.arpa",
            "pi.tail1234.ts.net", "fd12:3456::1", "::1",
        ).forEach { assertTrue(it, JellyfinApi.isLocalHost(it)) }
    }

    @Test
    fun publicHosts() {
        listOf(
            "jellyfin.example.com", "8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1",
            "local.example.com", "2001:4860::8888", "",
        ).forEach { assertFalse(it, JellyfinApi.isLocalHost(it)) }
    }

    @Test
    fun defaultSchemeIsHttpsForPublicHosts() {
        assertEquals("http://192.168.1.10:8096", JellyfinApi.normalizeUrl("192.168.1.10:8096"))
        assertEquals("https://jellyfin.example.com", JellyfinApi.normalizeUrl("jellyfin.example.com/"))
        assertEquals("http://jellyfin.example.com", JellyfinApi.normalizeUrl("http://jellyfin.example.com"))
    }

    // ---- streaming: URLs never carry the token; the token only goes to the signed-in server ----

    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun originalStreamIsStaticAndTokenFree() {
        val url = JellyfinApi.streamUrl("http://192.168.1.10:8096", id, StreamQuality.ORIGINAL)
        assertEquals("http://192.168.1.10:8096/Audio/$id/stream?static=true", url)
    }

    @Test
    fun transcodedStreamAsksForMp3AtTheChosenBitrate() {
        val url = JellyfinApi.streamUrl("https://music.example.com/jellyfin", id, StreamQuality.MEDIUM).toHttpUrl()
        assertEquals("/jellyfin/Audio/$id/stream.mp3", url.encodedPath)
        assertEquals("mp3", url.queryParameter("audioCodec"))
        assertEquals("192000", url.queryParameter("audioBitRate"))
        assertEquals("false", url.queryParameter("static"))
    }

    @Test
    fun streamUrlsNeverContainCredentials() {
        StreamQuality.entries.forEach { q ->
            val url = JellyfinApi.streamUrl("https://music.example.com", id, q).lowercase()
            listOf("api_key", "token", "apikey", "authorization").forEach { assertFalse("$q: $it", url.contains(it)) }
        }
    }

    @Test
    fun streamUrlRejectsUnsafeIds() {
        assertThrows("traversal") { JellyfinApi.streamUrl("https://music.example.com", "../Users", StreamQuality.ORIGINAL) }
    }

    @Test
    fun authHeaderOnlyForTheSignedInServer() {
        val server = "https://music.example.com/jellyfin"
        assertTrue(JellyfinApi.isServerRequest("https://music.example.com/jellyfin/Audio/x/stream".toHttpUrl(), server))
        assertTrue(JellyfinApi.isServerRequest("https://music.example.com/jellyfin".toHttpUrl(), server))
        // Different host, scheme, port, or a path outside the server's base path: no token.
        assertFalse(JellyfinApi.isServerRequest("https://evil.example.com/jellyfin/Audio".toHttpUrl(), server))
        assertFalse(JellyfinApi.isServerRequest("http://music.example.com/jellyfin/Audio".toHttpUrl(), server))
        assertFalse(JellyfinApi.isServerRequest("https://music.example.com:8443/jellyfin/Audio".toHttpUrl(), server))
        assertFalse(JellyfinApi.isServerRequest("https://music.example.com/jellyfin-other/x".toHttpUrl(), server))
        assertFalse(JellyfinApi.isServerRequest("https://music.example.com/other".toHttpUrl(), server))
        assertFalse(JellyfinApi.isServerRequest("https://music.example.com/x".toHttpUrl(), ""))
    }

    @Test
    fun streamQualityParsesSafely() {
        assertEquals(StreamQuality.LOW, StreamQuality.parse("LOW"))
        assertEquals(StreamQuality.ORIGINAL, StreamQuality.parse(null))
        assertEquals(StreamQuality.ORIGINAL, StreamQuality.parse("garbage"))
    }

    private fun assertThrows(input: String, block: () -> Unit) {
        try {
            block()
        } catch (e: JellyfinException) {
            return
        }
        throw AssertionError("expected rejection of \"$input\"")
    }
}
