// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tentacle.music.Account
import app.tentacle.music.R
import app.tentacle.music.Tailscale

/**
 * The phone app: Now playing, Library (the car's browse tree plus a Songs tab) and Settings.
 * It drives the same player Android Auto uses, so both always show the same thing.
 */
class MainActivity : ComponentActivity() {

    private lateinit var player: PlayerConnection
    private lateinit var account: Account

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent system bars so the green theme runs behind them; icon colours follow light/dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Without this the system tints the 3-button navigation area with its own (non-green) scrim.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        player = PlayerConnection(this)
        account = Account(this)
        setContent {
            AppTheme { App(account, player) }
        }
    }

    override fun onStart() {
        super.onStart()
        // First, so the library waits for Tailscale (if the settings use it) instead of failing without it.
        if (account.prefs.isSignedIn) Tailscale.ensureAsync(this, account.prefs)
        player.connect()
    }

    override fun onStop() {
        // Only disconnects this screen: playback carries on in the service.
        player.disconnect()
        super.onStop()
    }
}

private enum class Tab(val label: String, val icon: Int) {
    NOW_PLAYING("Now playing", R.drawable.ic_song),
    LIBRARY("Library", R.drawable.ic_library),
    SETTINGS("Settings", R.drawable.ic_settings),
}

@Composable
private fun App(account: Account, player: PlayerConnection) {
    // Ignore taps while another app's window covers this one (tapjacking on the sign-in form and controls).
    val view = LocalView.current
    DisposableEffect(view) {
        view.filterTouchesWhenObscured = true
        onDispose { }
    }

    var signedIn by rememberSaveable { mutableStateOf(account.prefs.isSignedIn) }
    var tab by rememberSaveable { mutableIntStateOf(Tab.NOW_PLAYING.ordinal) }
    val state by player.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (signedIn) {
                Column {
                    if (tab != Tab.NOW_PLAYING.ordinal) MiniPlayer(state, player) { tab = Tab.NOW_PLAYING.ordinal }
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t.ordinal,
                                onClick = { tab = t.ordinal },
                                icon = { Icon(painterResource(t.icon), null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (!signedIn) {
            SignInScreen(account, onSignedIn = {
                signedIn = true
                tab = Tab.LIBRARY.ordinal
            }, contentPadding = padding)
            return@Scaffold
        }
        when (Tab.entries[tab]) {
            Tab.NOW_PLAYING -> NowPlayingScreen(state, player, onBrowse = { tab = Tab.LIBRARY.ordinal }, contentPadding = padding)
            Tab.LIBRARY -> LibraryScreen(state, player, onPlayed = { tab = Tab.NOW_PLAYING.ordinal }, contentPadding = padding)
            Tab.SETTINGS -> SettingsScreen(account, player, onSignedOut = { signedIn = false }, contentPadding = padding)
        }
    }
}

/** Compact player shown above the tabs while browsing: artwork, title, play/pause; tap to open. */
@Composable
private fun MiniPlayer(state: PlayerConnection.State, player: PlayerConnection, onOpen: () -> Unit) {
    val item = state.item ?: return
    val md = item.mediaMetadata
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(md.artworkUri, R.drawable.ic_song, RoundedCornerShape(6.dp), Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(md.title?.toString().orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                md.artist?.toString()?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { player.player?.run { if (isPlaying) pause() else play() } }) {
                Icon(
                    painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    if (state.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = { player.player?.seekToNext() }) {
                Icon(painterResource(R.drawable.ic_skip_next), "Next")
            }
        }
    }
}
