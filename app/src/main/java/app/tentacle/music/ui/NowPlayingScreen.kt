// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import app.tentacle.music.R
import kotlinx.coroutines.delay

/** The player: artwork, song details, seek bar, controls, shuffle/repeat and "Up next". */
@Composable
fun NowPlayingScreen(
    state: PlayerConnection.State,
    player: PlayerConnection,
    onBrowse: () -> Unit,
    contentPadding: PaddingValues,
) {
    val item = state.item
    val p = player.player

    if (item == null || p == null) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.logo_emblem), contentDescription = null, modifier = Modifier.size(140.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                if (state.connected) "Nothing playing" else "Connecting…",
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pick something from your library, or let Tentacle choose.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { player.play("shuffle:library:all") }, enabled = state.connected) {
                    Text("Shuffle my library")
                }
                OutlinedButton(onClick = onBrowse) { Text("Browse library") }
            }
        }
        return
    }

    val md = item.mediaMetadata
    val duration = state.durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: md.durationMs ?: 0L

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding, horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(
                Modifier.widthIn(max = 480.dp).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(md.artworkUri, R.drawable.ic_song, RoundedCornerShape(16.dp), Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(24.dp))
                Text(
                    md.title?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Text(
                    listOfNotNull(md.artist?.toString(), md.albumTitle?.toString()).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(16.dp))
                SeekBar(p, duration, state.isPlaying)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ModeButton(if (state.shuffle) "Shuffle on" else "Shuffle off", R.drawable.ic_shuffle, state.shuffle) {
                        p.shuffleModeEnabled = !p.shuffleModeEnabled
                    }
                    IconButton(onClick = { p.seekToPrevious() }, Modifier.size(56.dp)) {
                        Icon(painterResource(R.drawable.ic_skip_previous), "Previous", Modifier.size(36.dp))
                    }
                    FilledIconButton(
                        onClick = {
                            when {
                                state.isPlaying -> p.pause()
                                state.playbackState == Player.STATE_ENDED -> { p.seekTo(0, 0); p.play() }
                                state.playbackState == Player.STATE_IDLE -> { p.prepare(); p.play() }
                                else -> p.play()
                            }
                        },
                        modifier = Modifier.size(72.dp),
                    ) {
                        Icon(
                            painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            if (state.isPlaying) "Pause" else "Play", Modifier.size(40.dp),
                        )
                    }
                    IconButton(onClick = { p.seekToNext() }, Modifier.size(56.dp)) {
                        Icon(painterResource(R.drawable.ic_skip_next), "Next", Modifier.size(36.dp))
                    }
                    val (repeatLabel, repeatIcon) = when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repeating this song" to R.drawable.ic_repeat_one_plain
                        Player.REPEAT_MODE_ALL -> "Repeating all" to R.drawable.ic_repeat
                        else -> "Repeat off" to R.drawable.ic_repeat
                    }
                    ModeButton(repeatLabel, repeatIcon, state.repeatMode != Player.REPEAT_MODE_OFF) {
                        p.repeatMode = when (p.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    }
                }
            }
        }

        if (state.upNext.isNotEmpty()) {
            item {
                Text(
                    "Up next", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(state.upNext, key = { it.first }) { (index, queued) ->
                MediaRow(
                    title = queued.mediaMetadata.title?.toString().orEmpty(),
                    subtitle = queued.mediaMetadata.artist?.toString(),
                    icon = queued.mediaMetadata.artworkUri,
                    fallback = R.drawable.ic_song,
                    modifier = Modifier.widthIn(max = 520.dp).clickable { p.seekTo(index, 0) },
                )
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, icon: Int, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, Modifier.size(48.dp)) {
        Icon(
            painterResource(icon), label,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/** Progress from the player itself, refreshed twice a second; drag to seek. */
@Composable
private fun SeekBar(player: Player, durationMs: Long, playing: Boolean) {
    val position by produceState(player.currentPosition, player, playing) {
        while (true) {
            value = player.currentPosition
            delay(if (playing) 500 else 1_000)
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val shown = dragging ?: position.toFloat().coerceIn(0f, max)
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..max,
            enabled = durationMs > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelMedium)
            Text(if (durationMs > 0) formatTime(durationMs) else "", style = MaterialTheme.typography.labelMedium)
        }
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
