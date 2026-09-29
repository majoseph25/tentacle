// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import app.tentacle.music.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import java.io.IOException

/**
 * The phone app's link to [PlaybackService]. It connects the same way Android Auto does, so the
 * phone and the car always show the same player, queue and library. Use from the main thread.
 */
class PlayerConnection(private val context: Context) {

    data class State(
        val connected: Boolean = false,
        val item: MediaItem? = null,
        val isPlaying: Boolean = false,
        val playbackState: Int = Player.STATE_IDLE,
        val durationMs: Long = C.TIME_UNSET,
        val shuffle: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        /** Queue positions and items after the current one, in play order (respects shuffle). */
        val upNext: List<Pair<Int, MediaItem>> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var future: ListenableFuture<MediaBrowser>? = null
    private var browser: MediaBrowser? = null

    /** The shared player (null until connected). */
    val player: Player? get() = browser

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    /** True between connect() and disconnect(): the screen wants a connection. */
    private var wanted = false

    fun connect() {
        wanted = true
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaBrowser.Builder(context, token)
            .setListener(object : MediaBrowser.Listener {
                // Called for every disconnect, including our own release in disconnect(), which clears
                // `browser` first so it's ignored here. Otherwise the service went away (e.g. killed for
                // memory): Media3 has already released this controller, so only drop our references
                // (releasing it again crashes) and connect afresh.
                override fun onDisconnected(controller: androidx.media3.session.MediaController) {
                    if (browser !== controller) return
                    controller.removeListener(listener)
                    browser = null
                    future = null
                    _state.value = State()
                    if (wanted) connect()
                }
            })
            .buildAsync()
        future = f
        f.addListener({
            val b = try {
                f.get()
            } catch (e: Exception) {
                future = null
                return@addListener
            }
            if (future !== f) {
                // Disconnected while connecting.
                b.release()
                return@addListener
            }
            browser = b
            b.addListener(listener)
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        wanted = false
        // Clear our references before releasing: release() calls onDisconnected synchronously.
        val f = future
        val b = browser
        future = null
        browser = null
        b?.removeListener(listener)
        f?.let { MediaBrowser.releaseFuture(it) }
        _state.value = State()
    }

    private fun publish() {
        val b = browser ?: return
        _state.value = State(
            connected = true,
            item = b.currentMediaItem,
            isPlaying = b.isPlaying,
            playbackState = b.playbackState,
            durationMs = b.duration,
            shuffle = b.shuffleModeEnabled,
            repeatMode = b.repeatMode,
            upNext = upNext(b),
            error = b.playerError?.let { "Couldn't play this song (${it.errorCodeName})." },
        )
    }

    private fun upNext(p: Player): List<Pair<Int, MediaItem>> {
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<Pair<Int, MediaItem>>()
        var i = p.currentMediaItemIndex
        while (out.size < MAX_UP_NEXT) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
            out += i to p.getMediaItemAt(i)
        }
        return out
    }

    /** Plays a library row (a track, "Play all", "Shuffle", ...). The service resolves what it means. */
    fun play(mediaId: String) {
        val p = browser ?: return
        p.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
        p.prepare()
        p.play()
    }

    suspend fun rootId(): String? = browser?.getLibraryRoot(null)?.await()?.value?.mediaId

    /** One level (or one page) of the library, exactly as Android Auto sees it. */
    suspend fun children(parentId: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE): List<MediaItem> {
        val b = browser ?: throw IOException("Not connected")
        val result = b.getChildren(parentId, page, pageSize, null).await()
        return result.value ?: throw IOException("Couldn't load (${result.resultCode})")
    }

    /** Searches songs, artists and albums, the same way Android Auto's search does. */
    suspend fun search(query: String): List<MediaItem> {
        val b = browser ?: throw IOException("Not connected")
        b.search(query, null).await()
        val result = b.getSearchResult(query, 0, MAX_SEARCH_RESULTS, null).await()
        return result.value ?: throw IOException("Search failed (${result.resultCode})")
    }

    private companion object {
        const val MAX_UP_NEXT = 100
        const val MAX_SEARCH_RESULTS = 100
    }
}
