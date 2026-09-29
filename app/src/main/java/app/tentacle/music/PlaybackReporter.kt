// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Tells the Jellyfin server what's playing (start, progress every 10 s, pause/seek, stop), so
 * "Recently played", play counts, "played" marks and the dashboard stay accurate. Also saves the
 * queue and position so playback can resume after the app restarts.
 *
 * Reports are best-effort: a failed report never interrupts playback.
 */
class PlaybackReporter(
    private val player: Player,
    private val api: JellyfinApi,
    private val prefs: Prefs,
    private val mainScope: CoroutineScope,
) : Player.Listener {

    private data class Current(val itemId: String, val playSessionId: String, val transcoding: Boolean, val durationMs: Long?)

    private var current: Current? = null
    private var ticker: Job? = null
    /** Written from the background report coroutine, read on the main thread. */
    @Volatile
    private var capabilitiesSent = false

    init {
        player.addListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A song that played to the end is reported at its full length so the server marks it played.
        val finished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        stopCurrent(if (finished) current?.durationMs ?: lastPosition else lastPosition)
        lastPosition = 0L
        if (player.isPlaying) start()
        saveResume()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && current?.itemId != player.currentMediaItem?.mediaId) {
            start()
        } else {
            progress(if (isPlaying) "unpause" else "pause")
        }
        tick(isPlaying)
        saveResume()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) progress("timeupdate")
        lastPosition = player.currentPosition
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) stopCurrent(player.currentPosition)
    }

    /** Sends the final "stopped" report; call when the service shuts down. */
    fun release() {
        ticker?.cancel()
        saveResume()
        stopCurrent(player.currentPosition)
        player.removeListener(this)
    }

    // Last known position of the current item, for the "stopped" report after a transition.
    private var lastPosition = 0L

    private fun start() {
        val item = player.currentMediaItem ?: return
        if (!prefs.isSignedIn || !isSafeId(item.mediaId)) return
        val c = Current(
            item.mediaId, UUID.randomUUID().toString().replace("-", ""), MediaItems.isTranscoded(item),
            item.mediaMetadata.durationMs,
        )
        current = c
        val report = report(c, player.currentPosition, paused = false, event = null)
        AppScope.launch {
            try {
                if (!capabilitiesSent) {
                    api.reportCapabilities()
                    capabilitiesSent = true
                }
                api.reportStart(report)
            } catch (e: Exception) {
                JellyfinApi.warn("start report failed", e)
            }
        }
    }

    private fun progress(event: String) {
        val c = current ?: return
        if (!prefs.isSignedIn) return // signed out: the server already ended this session
        lastPosition = player.currentPosition
        val report = report(c, lastPosition, paused = !player.isPlaying, event = event)
        AppScope.launch {
            try {
                api.reportProgress(report)
            } catch (e: Exception) {
                JellyfinApi.warn("progress report failed", e)
            }
        }
    }

    private fun stopCurrent(positionMs: Long) {
        val c = current ?: return
        current = null
        // After sign-out the token is revoked and the server has already dropped this session.
        if (!prefs.isSignedIn) return
        val report = report(c, positionMs, paused = true, event = null)
        AppScope.launch {
            try {
                api.reportStopped(report)
            } catch (e: Exception) {
                JellyfinApi.warn("stop report failed", e)
            }
        }
    }

    private fun tick(playing: Boolean) {
        ticker?.cancel()
        if (!playing) return
        ticker = mainScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                progress("timeupdate")
                saveResume()
            }
        }
    }

    private fun report(c: Current, positionMs: Long, paused: Boolean, event: String?) = JellyfinApi.PlaybackReport(
        itemId = c.itemId, positionMs = positionMs, isPaused = paused,
        playSessionId = c.playSessionId, transcoding = c.transcoding, eventName = event,
    )

    private fun saveResume() {
        if (!prefs.isSignedIn || player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        prefs.saveResume(ids, player.currentMediaItemIndex, player.currentPosition)
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 10_000L
    }
}
