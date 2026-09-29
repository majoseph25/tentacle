// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Optional helper for servers reached through Tailscale: asks the user's installed Tailscale app to
 * connect, using the public intent Tailscale provides for automation apps (the same one Tasker uses).
 *
 * Tentacle never sees the user's Tailscale account, keys or traffic, never turns Tailscale off, and
 * sends nothing with the request. If Tailscale isn't installed, isn't signed in, or doesn't come up,
 * nothing else happens: Tentacle just reports it, and the user can open Tailscale themselves.
 */
object Tailscale {
    const val PACKAGE = "com.tailscale.ipn"
    private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"

    /** How long a connect attempt waits for the VPN before sending the request again, then in total. */
    private const val FIRST_WAIT_MS = 3_000L
    private const val TOTAL_WAIT_MS = 10_000L

    /** Quick check of whether the server answers without Tailscale (TCP connect only; nothing is sent). */
    private const val PROBE_TIMEOUT_MS = 1_500

    /** After a failed attempt, automatic triggers wait this long before trying again (manual ones don't). */
    private const val RETRY_AFTER_FAILURE_MS = 60_000L

    enum class Outcome(val message: String) {
        OFF("Tailscale is off in Tentacle's settings."),
        NOT_INSTALLED("The Tailscale app isn't installed."),
        ALREADY_CONNECTED("A VPN is already connected."),
        SERVER_REACHABLE("Your server is reachable without Tailscale."),
        CONNECTED("Tailscale connected."),
        WAITING("Tailscale didn't connect a moment ago. Tentacle will try again shortly."),
        FAILED("Tailscale didn't connect. Open the Tailscale app, check you're signed in, and connect there."),
    }

    private val lock = Any()
    private var inFlight: Deferred<Outcome>? = null
    @Volatile private var lastFailureMs = 0L

    private val _connections = MutableStateFlow(0)

    /** Goes up each time Tentacle brings Tailscale up, so screens can reload what failed without it. */
    val connections: StateFlow<Int> = _connections.asStateFlow()

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** True when the phone's traffic currently goes through a VPN (Tailscale or any other). */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Opens the Tailscale app (for signing in or connecting by hand). Returns false if it isn't installed. */
    fun openApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * Makes sure the server can be reached, connecting Tailscale first if the settings call for it.
     * Concurrent callers (the phone app, Android Auto, the player) share one attempt.
     *
     * @param force the user asked explicitly ("Connect now"): skip the setting, the reachability check
     *   and the wait after a failure.
     * @param serverUrl the server to check; defaults to the signed-in one.
     */
    fun ensureAsync(context: Context, prefs: Prefs, force: Boolean = false, serverUrl: String = prefs.serverUrl): Deferred<Outcome> =
        synchronized(lock) {
            inFlight?.takeIf { it.isActive }?.let { return it }
            val app = context.applicationContext
            AppScope.async { attempt(app, prefs.tailscaleMode, force, serverUrl) }.also { inFlight = it }
        }

    suspend fun ensure(context: Context, prefs: Prefs, force: Boolean = false, serverUrl: String = prefs.serverUrl): Outcome =
        ensureAsync(context, prefs, force, serverUrl).await()

    /** Waits (up to [maxMs]) for an attempt already under way, so a library request doesn't race it. */
    suspend fun awaitInFlight(maxMs: Long) {
        val job = synchronized(lock) { inFlight?.takeIf { it.isActive } } ?: return
        withTimeoutOrNull(maxMs) { job.await() }
    }

    private suspend fun attempt(context: Context, mode: TailscaleMode, force: Boolean, serverUrl: String): Outcome {
        val coolingDown = System.currentTimeMillis() - lastFailureMs in 0 until RETRY_AFTER_FAILURE_MS
        precheck(mode, force, isInstalled(context), isVpnActive(context), coolingDown)?.let { return it }
        if (!force && mode == TailscaleMode.WHEN_NEEDED && isReachable(serverUrl)) return Outcome.SERVER_REACHABLE

        // Tailscale's own advice for Android 16+: the first request may only start its service, so
        // ask again if the VPN isn't up after a few seconds.
        requestConnect(context)
        val up = awaitVpn(context, FIRST_WAIT_MS) || run {
            requestConnect(context)
            awaitVpn(context, TOTAL_WAIT_MS - FIRST_WAIT_MS)
        }
        if (up) {
            _connections.value++
            return Outcome.CONNECTED
        }
        lastFailureMs = System.currentTimeMillis()
        return Outcome.FAILED
    }

    /**
     * The decisions that don't need the network, in order. Returns the outcome to report, or null to
     * go on (check the server, then connect).
     */
    internal fun precheck(mode: TailscaleMode, force: Boolean, installed: Boolean, vpnActive: Boolean, coolingDown: Boolean): Outcome? = when {
        !force && mode == TailscaleMode.OFF -> Outcome.OFF
        !installed -> Outcome.NOT_INSTALLED
        vpnActive -> Outcome.ALREADY_CONNECTED
        !force && coolingDown -> Outcome.WAITING
        else -> null
    }

    /** True for addresses that only work through Tailscale: its 100.64.0.0/10 range and MagicDNS names. */
    fun isTailnetAddress(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.endsWith(".ts.net")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
        return octets[0] == 100 && octets[1] in 64..127
    }

    private fun requestConnect(context: Context) {
        // Explicit component: only the Tailscale app can receive this, and it carries no data.
        context.sendBroadcast(Intent(ACTION_CONNECT).setComponent(ComponentName(PACKAGE, RECEIVER)))
    }

    private suspend fun isReachable(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = serverUrl.toHttpUrlOrNull() ?: return@withContext false
        try {
            Socket().use { it.connect(InetSocketAddress(url.host, url.port), PROBE_TIMEOUT_MS) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Waits for a VPN network to become available, up to [timeoutMs]. */
    private suspend fun awaitVpn(context: Context, timeoutMs: Long): Boolean {
        if (isVpnActive(context)) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (cont.isActive) cont.resume(true)
                        }
                    }.also { cm.registerNetworkCallback(request, it) }
                }
            } ?: false
        } finally {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }
}
