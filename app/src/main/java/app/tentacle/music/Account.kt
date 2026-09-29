// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import java.io.IOException

/** Signing in and out of the Jellyfin server. The password is only held for the sign-in request. */
class Account(context: Context) {
    private val appContext = context.applicationContext
    val prefs = Prefs(appContext)
    val api = JellyfinApi(prefs)

    sealed interface SignInResult {
        data object SignedIn : SignInResult
        /** [unreachable]: the server didn't answer at all (worth offering Tailscale, if installed). */
        data class Failed(val message: String, val unreachable: Boolean = false) : SignInResult

        /** Plain HTTP to a public address: the caller must warn and ask again with [allowInsecure]. */
        data class InsecureWarning(val host: String) : SignInResult
    }

    suspend fun signIn(serverInput: String, userName: String, password: String, allowInsecure: Boolean): SignInResult {
        preflight(serverInput, userName, allowInsecure)?.let { return it }
        val url = JellyfinApi.normalizeUrl(serverInput)
        return try {
            val auth = withContext(Dispatchers.IO) { api.authenticate(url, userName.trim(), password) }
            val oldServer = prefs.serverUrl
            val oldToken = prefs.token
            prefs.serverUrl = url
            prefs.userId = auth.userId
            prefs.userName = auth.userName
            prefs.token = auth.token
            if (oldServer != url) ArtworkProvider.clearCache(appContext)
            if (oldToken.isNotEmpty() && oldToken != auth.token) revoke(oldServer, oldToken)
            SignInResult.SignedIn
        } catch (e: JSONException) {
            // Don't echo the server's raw reply (it may be an HTML page from something else entirely).
            SignInResult.Failed("The server's reply wasn't understood. Check that this is your Jellyfin server's address.")
        } catch (e: JellyfinException) {
            SignInResult.Failed("Sign-in failed: ${e.message}")
        } catch (e: IOException) {
            SignInResult.Failed("Can't reach the server. Check the address and your connection.", unreachable = true)
        } catch (e: Exception) {
            SignInResult.Failed("Sign-in failed (${e.javaClass.simpleName}).")
        }
    }

    /** Forgets the login locally right away, then revokes the token on the server in the background. */
    fun signOut() {
        val server = prefs.serverUrl
        val token = prefs.token
        prefs.signOut()
        ArtworkProvider.clearCache(appContext)
        if (token.isNotEmpty()) revoke(server, token)
    }

    fun clearUserName() {
        prefs.userName = ""
    }

    /** In [AppScope] so leaving the screen doesn't cancel the revoke. */
    private fun revoke(server: String, token: String) {
        AppScope.launch {
            try {
                api.logout(server, token)
            } catch (e: Exception) {
                // Offline or server gone: the token is already deleted locally.
            }
        }
    }

    companion object {
        /**
         * Checks that don't need the network: required fields, a parseable address, and the warning
         * for plain HTTP to a public address. Returns null when sign-in may proceed.
         */
        fun preflight(serverInput: String, userName: String, allowInsecure: Boolean): SignInResult? {
            if (serverInput.isBlank() || userName.isBlank()) return SignInResult.Failed("Enter the server address and username.")
            val url = JellyfinApi.normalizeUrl(serverInput)
            val host = url.toHttpUrlOrNull()?.host ?: return SignInResult.Failed("That server address isn't valid.")
            if (url.startsWith("http://", ignoreCase = true) && !JellyfinApi.isLocalHost(host) && !allowInsecure) {
                return SignInResult.InsecureWarning(host)
            }
            return null
        }
    }
}
