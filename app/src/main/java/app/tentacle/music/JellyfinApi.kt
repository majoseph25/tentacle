// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import android.util.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Blocking Jellyfin REST client. Call from a background dispatcher. */
class JellyfinApi(private val prefs: Prefs) {

    class Page(val items: List<JSONObject>, val total: Int)

    // ---- auth ----

    fun authenticate(serverUrl: String, user: String, password: String): Auth {
        val body = JSONObject().put("Username", user).put("Pw", password).toString()
        val text = try {
            String(
                requestBytes(
                    "POST", "Users/AuthenticateByName",
                    body = body.toRequestBody(JSON),
                    base = serverUrl, token = null,
                ),
            )
        } catch (e: JellyfinException) {
            if (e.httpCode == 401) throw JellyfinException("Wrong username or password", 401)
            throw e
        }
        val o = JSONObject(text)
        val u = o.getJSONObject("User")
        val token = o.getString("AccessToken")
        // The token goes into every request's Authorization header: reject anything that isn't a plain token.
        if (!TOKEN_PATTERN.matches(token)) throw JellyfinException("Server returned an invalid access token")
        return Auth(token, requireSafeId(u.getString("Id")), u.getString("Name"))
    }

    /** Revokes [token] on the server so a signed-out token can't be reused. */
    fun logout(serverUrl: String, token: String) {
        requestBytes("POST", "Sessions/Logout", base = serverUrl, token = token)
    }

    // ---- library ----

    /** GET /Items with sensible defaults; [query] overrides/extends them. */
    fun items(query: Map<String, String>): Page = parsePage(
        request(
            "GET", "Items",
            mapOf(
                "userId" to prefs.userId,
                "Recursive" to "true",
                "EnableImageTypes" to "Primary",
                "ImageTypeLimit" to "1",
            ) + query,
        ),
    )

    fun albumArtists(query: Map<String, String>): Page = parsePage(
        request(
            "GET", "Artists/AlbumArtists",
            mapOf(
                "userId" to prefs.userId,
                "EnableImageTypes" to "Primary",
                "ImageTypeLimit" to "1",
            ) + query,
        ),
    )

    fun playlistItems(playlistId: String, limit: Int): Page = parsePage(
        request(
            "GET", "Playlists/${requireSafeId(playlistId)}/Items",
            mapOf("userId" to prefs.userId, "limit" to limit.toString(), "EnableImageTypes" to "Primary"),
        ),
    )

    /**
     * Items by id, returned in the order requested (the server doesn't preserve it).
     * Malformed ids are skipped rather than failing the whole lookup.
     */
    fun itemsByIds(ids: List<String>): List<JSONObject> {
        val safe = ids.filter(::isSafeId).distinct()
        if (safe.isEmpty()) return emptyList()
        val found = safe.chunked(IDS_PER_REQUEST)
            .flatMap { items(mapOf("Ids" to it.joinToString(","))).items }
            .associateBy { it.str("Id") }
        return ids.mapNotNull { found[it] }
    }

    fun downloadPrimaryImage(itemId: String): ByteArray = requestBytes(
        "GET", "Items/${requireSafeId(itemId)}/Images/Primary",
        mapOf("maxHeight" to "512", "maxWidth" to "512", "quality" to "90", "format" to "jpg"),
        maxBytes = MAX_IMAGE_BYTES,
    )

    // ---- playback reporting (keeps "Recently played", play counts and the dashboard accurate) ----

    /** Registers this app as an audio player so the server lists it under active devices. */
    fun reportCapabilities() {
        val body = JSONObject()
            .put("PlayableMediaTypes", JSONArray().put("Audio"))
            .put("SupportedCommands", JSONArray())
            .put("SupportsMediaControl", false)
            .put("SupportsPersistentIdentifier", true)
        requestBytes("POST", "Sessions/Capabilities/Full", body = body.toString().toRequestBody(JSON))
    }

    fun reportStart(report: PlaybackReport) = postReport("Sessions/Playing", report)

    fun reportProgress(report: PlaybackReport) = postReport("Sessions/Playing/Progress", report)

    fun reportStopped(report: PlaybackReport) = postReport("Sessions/Playing/Stopped", report)

    data class PlaybackReport(
        val itemId: String,
        val positionMs: Long,
        val isPaused: Boolean,
        val playSessionId: String,
        val transcoding: Boolean,
        val eventName: String? = null,
    )

    private fun postReport(path: String, r: PlaybackReport) {
        val body = JSONObject()
            .put("ItemId", requireSafeId(r.itemId))
            .put("PositionTicks", r.positionMs.coerceAtLeast(0) * TICKS_PER_MS)
            .put("IsPaused", r.isPaused)
            .put("CanSeek", true)
            .put("PlaySessionId", r.playSessionId)
            .put("PlayMethod", if (r.transcoding) "Transcode" else "DirectStream")
            .apply { r.eventName?.let { put("EventName", it) } }
        requestBytes("POST", path, body = body.toString().toRequestBody(JSON))
    }

    // ---- plumbing ----

    private fun request(method: String, path: String, query: Map<String, String> = emptyMap()): String =
        String(requestBytes(method, path, query))

    private fun requestBytes(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        base: String = prefs.serverUrl,
        token: String? = prefs.token,
        maxBytes: Long = MAX_RESPONSE_BYTES,
    ): ByteArray {
        val root = base.trimEnd('/').toHttpUrlOrNull() ?: throw JellyfinException("Invalid server URL")
        val url = root.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(prefs.deviceId, token))
            .method(method, if (method == "GET") null else body ?: EMPTY_BODY)
            .build()
        return http.newCall(req).execute().use { resp ->
            if (resp.isRedirect) {
                throw JellyfinException("Server redirected to ${shownLocation(resp.header("Location"))}. Use that address instead.", resp.code)
            }
            if (!resp.isSuccessful) throw JellyfinException("Server returned ${resp.code}", resp.code)
            val source = resp.body?.source() ?: return@use ByteArray(0)
            if (source.request(maxBytes + 1)) throw JellyfinException("Server response too large")
            source.buffer.readByteArray()
        }
    }

    private fun parsePage(text: String): Page {
        val o = JSONObject(text)
        val arr = o.optJSONArray("Items") ?: JSONArray()
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
        return Page(list, o.optInt("TotalRecordCount", list.size))
    }

    /**
     * Adds the Authorization header to requests for the signed-in server only. Streams go through
     * ExoPlayer, which never sees the token: the header is added here, and never to any other host.
     */
    class AuthInterceptor(private val prefs: Prefs) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val token = prefs.token
            if (token.isEmpty() || !isServerRequest(request.url, prefs.serverUrl)) return chain.proceed(request)
            return chain.proceed(request.newBuilder().header("Authorization", authHeader(prefs.deviceId, token)).build())
        }
    }

    companion object {
        const val TAG = "Tentacle"
        const val CLIENT_NAME = "Tentacle"

        /**
         * Diagnostics exist only in debug builds, and even there stay off until enabled with
         * `adb shell setprop log.tag.Tentacle DEBUG`. Release builds never log them.
         */
        fun debugLogging(): Boolean = BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)

        /** Release builds log only the error type: messages can contain server addresses. */
        fun warn(message: String, e: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, message, e) else Log.w(TAG, "$message (${e.javaClass.simpleName})")
        }

        const val TICKS_PER_MS = 10_000L
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9._~-]{16,512}")
        private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 5L * 1024 * 1024
        private const val IDS_PER_REQUEST = 100
        private const val MAX_SHOWN_LOCATION = 120
        private val JSON = "application/json".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        fun authHeader(deviceId: String, token: String?): String = buildString {
            append("MediaBrowser Client=\"$CLIENT_NAME\", Device=\"Android\", ")
            append("DeviceId=\"$deviceId\", Version=\"${BuildConfig.VERSION_NAME}\"")
            if (!token.isNullOrEmpty()) append(", Token=\"$token\"")
        }

        /**
         * One client for API calls so connections and threads are shared.
         * Redirects are not followed: a redirect could re-send the password (307/308) or the
         * token to another address, or downgrade HTTPS to HTTP.
         */
        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            // Hard cap per request: Android Auto requires content to load within 10 seconds.
            .callTimeout(9, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /** Client for audio streams: same pool and no-redirect rule, but no overall time limit on a song. */
        fun streamingClient(prefs: Prefs): OkHttpClient = http.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(prefs))
            .build()

        /** True if [url] is on the signed-in server (same scheme, host and port, under its base path). */
        fun isServerRequest(url: HttpUrl, serverUrl: String): Boolean {
            val server = serverUrl.trimEnd('/').toHttpUrlOrNull() ?: return false
            if (url.scheme != server.scheme || url.host != server.host || url.port != server.port) return false
            val base = server.encodedPath.trimEnd('/')
            return base.isEmpty() || url.encodedPath == base || url.encodedPath.startsWith("$base/")
        }

        /**
         * Stream URL for an item. It carries no token (the [AuthInterceptor] adds the header), so it's
         * safe to hand to the player and to appear in logs. ORIGINAL streams the file untouched; other
         * qualities ask the server for MP3 at that bitrate.
         */
        fun streamUrl(serverUrl: String, itemId: String, quality: StreamQuality): String {
            val root = serverUrl.trimEnd('/').toHttpUrlOrNull() ?: throw JellyfinException("Invalid server URL")
            val id = requireSafeId(itemId)
            val builder = root.newBuilder()
            return if (quality == StreamQuality.ORIGINAL) {
                builder.addPathSegments("Audio/$id/stream").addQueryParameter("static", "true").build().toString()
            } else {
                builder.addPathSegments("Audio/$id/stream.mp3")
                    .addQueryParameter("static", "false")
                    .addQueryParameter("audioCodec", "mp3")
                    .addQueryParameter("audioBitRate", (quality.kbps * 1000).toString())
                    .addQueryParameter("maxAudioChannels", "2")
                    .build().toString()
            }
        }

        /**
         * A redirect's Location header as shown in the sign-in error. It's the server's text, so only a short,
         * printable form is shown.
         */
        fun shownLocation(header: String?): String =
            header?.filter { it in ' '..'~' }?.trim()?.take(MAX_SHOWN_LOCATION)?.takeIf { it.isNotEmpty() } ?: "another address"

        /** Adds a scheme if missing: http for LAN addresses, https for everything else. */
        fun normalizeUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            if (t.contains("://")) return t
            val host = "http://$t".toHttpUrlOrNull()?.host.orEmpty()
            return if (isLocalHost(host)) "http://$t" else "https://$t"
        }

        /** True for addresses that normally never leave the home network (or are tunnelled, like Tailscale). */
        fun isLocalHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h.isEmpty()) return false
            if (h == "localhost" || (!h.contains('.') && !h.contains(':'))) return true
            if (listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net").any { h.endsWith(it) }) return true
            if (h.contains(':')) return h == "::1" || h.startsWith("fd") || h.startsWith("fc") || h.startsWith("fe80")
            val parts = h.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4) return false
            val (a, b) = parts
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
    }
}

/** org.json returns the string "null" for JSON nulls; treat null/missing as empty. */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

fun JSONObject.artistText(): String {
    val arr = optJSONArray("Artists")
    val joined = if (arr == null) "" else (0 until arr.length()).joinToString(", ") { arr.getString(it) }
    return joined.ifEmpty { str("AlbumArtist") }
}

/** The item id to request a Primary image from: the item itself, else its album. */
fun JSONObject.artItemId(): String? {
    val id = str("Id")
    if (optJSONObject("ImageTags")?.has("Primary") == true) return id
    val albumId = str("AlbumId")
    if (albumId.isNotEmpty() && str("AlbumPrimaryImageTag").isNotEmpty()) return albumId
    return null
}
