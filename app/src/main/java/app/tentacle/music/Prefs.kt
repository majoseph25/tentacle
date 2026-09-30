// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** App-private storage. The password is never stored, only the access token. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tentacle", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", "").orEmpty()
        set(v) = sp.edit { putString("serverUrl", v) }

    var userId: String
        get() = sp.getString("userId", "").orEmpty()
        set(v) = sp.edit { putString("userId", v) }

    var userName: String
        get() = sp.getString("userName", "").orEmpty()
        set(v) = sp.edit { putString("userName", v) }

    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit { putString("token", v) }

    var streamQuality: StreamQuality
        get() = StreamQuality.parse(sp.getString("streamQuality", null))
        set(v) = sp.edit { putString("streamQuality", v.name) }

    var tailscaleMode: TailscaleMode
        get() = TailscaleMode.parse(sp.getString("tailscaleMode", null))
        set(v) = sp.edit { putString("tailscaleMode", v.name) }

    /**
     * True while Tailscale is on because Tentacle turned it on, so Tentacle may turn it off again.
     * Stored (not just in memory) so it survives the app process being stopped.
     */
    var tailscaleStartedByTentacle: Boolean
        get() = sp.getBoolean("tailscaleStartedByTentacle", false)
        set(v) = sp.edit { putBoolean("tailscaleStartedByTentacle", v) }

    /** Stable id for this install. Locked so the service and the UI can't each create a different one. */
    val deviceId: String
        get() = synchronized(DEVICE_ID_LOCK) {
            sp.getString("deviceId", null) ?: UUID.randomUUID().toString().replace("-", "").also {
                sp.edit(commit = true) { putString("deviceId", it) }
            }
        }

    val isSignedIn: Boolean
        get() = serverUrl.isNotEmpty() && userId.isNotEmpty() && token.isNotEmpty()

    // ---- resume where you left off (e.g. pressing play in the car after a restart) ----

    data class Resume(val itemIds: List<String>, val index: Int, val positionMs: Long)

    fun saveResume(itemIds: List<String>, index: Int, positionMs: Long) = sp.edit {
        putString("resumeIds", itemIds.filter(::isSafeId).take(MAX_RESUME_ITEMS).joinToString(","))
        putInt("resumeIndex", index)
        putLong("resumePosition", positionMs.coerceAtLeast(0))
    }

    fun resume(): Resume? {
        val ids = sp.getString("resumeIds", "").orEmpty().split(',').filter(::isSafeId)
        if (ids.isEmpty()) return null
        return Resume(ids, sp.getInt("resumeIndex", 0).coerceIn(0, ids.lastIndex), sp.getLong("resumePosition", 0))
    }

    // ---- Android Auto connection log ----

    /**
     * Last few apps that asked to browse the library and whether they were let in, shown under
     * Settings > Android Auto connections so a failed car connection can be diagnosed without a computer.
     * Holds package names and times only.
     */
    fun recordClient(decision: ClientAccess.Decision): Unit = synchronized(CLIENT_LOG_LOCK) {
        val now = System.currentTimeMillis()
        val log = clientLog()
        // Any app can bind repeatedly; don't let that turn into a stream of disk writes.
        val same = log.firstOrNull { it.packageName == decision.packageName && it.allowed == decision.allowed }
        if (same != null && now - same.timeMs in 0 until CLIENT_LOG_MIN_INTERVAL_MS) return
        val line = "$now|${if (decision.allowed) 1 else 0}|${decision.packageName}|${decision.reason}"
        val kept = log.filterNot { it.packageName == decision.packageName && it.allowed == decision.allowed }
            .take(CLIENT_LOG_SIZE - 1)
            .map { "${it.timeMs}|${if (it.allowed) 1 else 0}|${it.packageName}|${it.reason}" }
        sp.edit { putString("clientLog", (listOf(line) + kept).joinToString("\n")) }
    }

    data class ClientEntry(val timeMs: Long, val allowed: Boolean, val packageName: String, val reason: String)

    fun clientLog(): List<ClientEntry> = sp.getString("clientLog", "").orEmpty().lines().mapNotNull { line ->
        val parts = line.split('|', limit = 4)
        if (parts.size != 4) return@mapNotNull null
        ClientEntry(parts[0].toLongOrNull() ?: return@mapNotNull null, parts[1] == "1", parts[2], parts[3])
    }

    /** Forgets the account and anything tied to it (the next account shouldn't resume its queue). */
    fun signOut() = sp.edit {
        remove("userId")
        remove("userName")
        remove("token")
        remove("resumeIds")
        remove("resumeIndex")
        remove("resumePosition")
    }

    private companion object {
        val DEVICE_ID_LOCK = Any()
        val CLIENT_LOG_LOCK = Any()
        const val CLIENT_LOG_SIZE = 8
        const val CLIENT_LOG_MIN_INTERVAL_MS = 60_000L
        const val MAX_RESUME_ITEMS = 200
    }
}
