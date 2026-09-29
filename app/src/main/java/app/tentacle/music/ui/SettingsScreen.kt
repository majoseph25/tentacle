// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tentacle.music.Account
import app.tentacle.music.ArtworkProvider
import app.tentacle.music.BuildConfig
import app.tentacle.music.Prefs
import app.tentacle.music.StreamQuality

@Composable
fun SettingsScreen(
    account: Account,
    player: PlayerConnection,
    onSignedOut: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val prefs = account.prefs
    var quality by remember { mutableStateOf(prefs.streamQuality) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
    var clients by remember { mutableStateOf(prefs.clientLog()) }
    LaunchedEffect(Unit) { clients = prefs.clientLog() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Account") {
            Text("Signed in as ${prefs.userName}", style = MaterialTheme.typography.titleMedium)
            Text(prefs.serverUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                // Stop and forget the queue first so nothing from this account keeps playing.
                player.player?.run {
                    stop()
                    clearMediaItems()
                }
                account.signOut()
                onSignedOut()
            }) { Text("Sign out") }
        }

        Section("Streaming quality") {
            Text(
                "Lower quality uses less mobile data. Applies to songs you start from now on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StreamQuality.entries.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = q == quality, role = Role.RadioButton) {
                        quality = q
                        prefs.streamQuality = q
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = q == quality, onClick = null)
                    Text(q.label, Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                }
            }
        }

        Section("Storage") {
            OutlinedButton(onClick = {
                val freed = ArtworkProvider.cacheSize(context)
                ArtworkProvider.clearCache(context)
                cacheMessage = "Cleared %.1f MB of cached album art.".format(freed / 1_048_576.0)
            }) { Text("Clear cache") }
            cacheMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        Section("Android Auto connections") {
            Text(
                "Apps that recently asked to browse your library. If Tentacle is missing from your car, " +
                    "look for a rejected entry here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (clients.isEmpty()) {
                Text("None yet. Connect to your car or the Android Auto simulator.", style = MaterialTheme.typography.bodyMedium)
            }
            clients.forEach { ClientRow(it) }
            TextButton(onClick = { clients = prefs.clientLog() }) { Text("Refresh") }
        }

        Text(
            "Tentacle ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})" +
                (if (BuildConfig.DEBUG) " · debug" else "") +
                "\n© 2026 Mark Joseph. Open source under the Apache License 2.0." +
                "\nAn unofficial music player for Jellyfin servers. Not affiliated with the Jellyfin project.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ClientRow(entry: Prefs.ClientEntry) {
    val ago = DateUtils.getRelativeTimeSpanString(entry.timeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            (if (entry.allowed) "✓ Allowed" else "✗ Rejected") + " · $ago",
            style = MaterialTheme.typography.labelLarge,
            color = if (entry.allowed) MaterialTheme.colorScheme.primary else Color(0xFFD32F2F),
        )
        Text(entry.packageName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(entry.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
