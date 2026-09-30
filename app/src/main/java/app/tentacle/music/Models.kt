// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import java.io.IOException

data class Auth(val token: String, val userId: String, val userName: String)

/** Android Auto content styles, applied to the children of a browsable node. */
enum class ContentStyle(val value: Int) { LIST(1), GRID(2) }

/** Built-in icon shown when a row has no artwork (Android Auto requires a thumbnail for every item). */
enum class IconKind { HOME, ALBUM, ARTIST, PLAYLIST, PLAY, SHUFFLE, SONG, SORT, INFO }

/**
 * Streaming quality. ORIGINAL streams the file as stored on the server; the others ask the server
 * to transcode to MP3 at that bitrate (useful on mobile data).
 */
enum class StreamQuality(val kbps: Int, val label: String) {
    ORIGINAL(0, "Original (no transcoding)"),
    HIGH(320, "High (320 kbps)"),
    MEDIUM(192, "Medium (192 kbps)"),
    LOW(128, "Low (128 kbps)"),
    ;

    companion object {
        fun parse(name: String?): StreamQuality = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/** Whether Tentacle may ask the Tailscale app to connect (see [Tailscale]). */
enum class TailscaleMode {
    OFF,

    /** Only when the server doesn't answer without it (for example away from home). */
    WHEN_NEEDED,
    ;

    companion object {
        /** 0.6.0 also had ALWAYS; it connected even at home, so it now means WHEN_NEEDED. */
        fun parse(name: String?): TailscaleMode = when (name) {
            "ALWAYS" -> WHEN_NEEDED
            else -> entries.firstOrNull { it.name == name } ?: OFF
        }
    }
}

data class BrowseEntry(
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    val artItemId: String? = null,
    val browsable: Boolean = false,
    val playable: Boolean = false,
    /** Section header this row sits under. Rows sharing a header must be contiguous. */
    val group: String? = null,
    /** How this node's browsable children are drawn (overrides the root default). */
    val browsableStyle: ContentStyle? = null,
    /** How this node's playable children are drawn. */
    val playableStyle: ContentStyle? = null,
    /** How this row itself is drawn, overriding its parent's hint (e.g. a folder row among album tiles). */
    val itemStyle: ContentStyle? = null,
    /** Shown when [artItemId] is null or its image can't be loaded. */
    val icon: IconKind = IconKind.SONG,
) {
    companion object {
        const val NOOP_ID = "noop"

        /** A non-actionable row used to show a message inside a browse list. */
        fun message(text: String) = BrowseEntry(NOOP_ID, text, playable = true, icon = IconKind.INFO)
    }
}

class JellyfinException(message: String, val httpCode: Int = 0) : IOException(message)

private val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")

/**
 * Ids end up in URL paths and file names, so only allow the characters Jellyfin ids use.
 * Rejects '/', '.', '%' and friends, which rules out path traversal to other API endpoints or files.
 */
fun requireSafeId(id: String): String {
    if (!isSafeId(id)) throw JellyfinException("Invalid id")
    return id
}

fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)
