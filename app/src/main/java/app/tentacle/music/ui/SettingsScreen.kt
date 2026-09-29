// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.tentacle.music.Account
import app.tentacle.music.ArtworkProvider
import app.tentacle.music.BuildConfig
import app.tentacle.music.Prefs
import app.tentacle.music.StreamQuality
import app.tentacle.music.Tailscale
import app.tentacle.music.TailscaleMode
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

        Section("Remote access with Tailscale") { TailscaleSettings(prefs) }

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

/**
 * Optional: have Tentacle ask the Tailscale app to connect, for servers that are only reachable
 * through Tailscale away from home. See [Tailscale] for what is (and isn't) done.
 */
@Composable
private fun TailscaleSettings(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { Tailscale.isInstalled(context) }
    var mode by remember { mutableStateOf(prefs.tailscaleMode) }
    var vpnUp by remember { mutableStateOf(Tailscale.isVpnActive(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val tailnetServer = remember(prefs.serverUrl) {
        prefs.serverUrl.toHttpUrlOrNull()?.host?.let(Tailscale::isTailnetAddress) == true
    }
    val hint = MaterialTheme.colorScheme.onSurfaceVariant

    Text(
        "Reach your server away from home through Tailscale. Tentacle asks the Tailscale app to connect; " +
            "it never sees your Tailscale account and never turns Tailscale off.",
        style = MaterialTheme.typography.bodySmall, color = hint,
    )
    Spacer(Modifier.height(4.dp))
    if (!installed) {
        Text("The Tailscale app isn't installed on this phone.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${Tailscale.PACKAGE}".toUri()))
            } catch (e: ActivityNotFoundException) {
                status = "No app store or browser found."
            }
        }) { Text("Get Tailscale") }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        return
    }
    if (tailnetServer && mode == TailscaleMode.OFF) {
        Text(
            "Your server's address is a Tailscale address, so this is recommended.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
        )
    }
    TailscaleMode.entries.forEach { m ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = m == mode, role = Role.RadioButton) {
                mode = m
                prefs.tailscaleMode = m
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = m == mode, onClick = null)
            Column(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Text(m.label)
                Text(m.detail, style = MaterialTheme.typography.bodySmall, color = hint)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("VPN: " + if (vpnUp) "connected" else "not connected", style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true
            status = null
            scope.launch {
                status = Tailscale.ensure(context, prefs, force = true).message
                vpnUp = Tailscale.isVpnActive(context)
                busy = false
            }
        }) { Text("Connect now") }
        TextButton(onClick = { Tailscale.openApp(context) }) { Text("Open Tailscale") }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Spacer(Modifier.height(4.dp))
    Text(
        "Most reliable: make Tailscale your always-on VPN (Android Settings → Network & internet → VPN → " +
            "Tailscale). Then Tentacle doesn't need to connect it at all.",
        style = MaterialTheme.typography.bodySmall, color = hint,
    )
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
