// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tentacle.music.Account
import app.tentacle.music.BuildConfig
import app.tentacle.music.R
import kotlinx.coroutines.launch

@Composable
fun SignInScreen(account: Account, onSignedIn: () -> Unit, contentPadding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var server by rememberSaveable { mutableStateOf(account.prefs.serverUrl) }
    var user by rememberSaveable { mutableStateOf(account.prefs.userName) }
    // The password is deliberately not saveable: it isn't written to saved instance state.
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var insecureConfirmedFor by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        focus.clearFocus()
        busy = true
        status = null
        scope.launch {
            when (val result = account.signIn(server, user, password, allowInsecure = insecureConfirmedFor == server)) {
                Account.SignInResult.SignedIn -> {
                    password = ""
                    onSignedIn()
                }
                is Account.SignInResult.Failed -> status = result.message
                is Account.SignInResult.InsecureWarning -> {
                    insecureConfirmedFor = server
                    status = "Warning: ${result.host} is not on your home network, and http:// is unencrypted. " +
                        "Your password and access token could be read by anyone on the network path " +
                        "(for example public Wi-Fi or your mobile carrier). Use an https:// address if your server " +
                        "has one.\n\nTap Sign in again to continue anyway."
                }
            }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        // The full logo already includes the "TENTACLE" wordmark.
        Image(
            painterResource(R.drawable.logo_full), contentDescription = "Tentacle",
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(0.75f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your Jellyfin music, streamed to your phone and to Android Auto. Sign in to your server to start.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = server, onValueChange = { server = it },
                label = { Text("Jellyfin server") }, placeholder = { Text("192.168.1.10:8096") },
                singleLine = true,
                // No autocorrect: keeps the keyboard from learning and suggesting your server address.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Username") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password (not stored)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { submit() }, enabled = !busy) { Text("Sign in") }
                TextButton(onClick = {
                    user = ""
                    account.clearUserName()
                    status = "Saved username cleared."
                }) { Text("Clear username") }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " · debug" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
