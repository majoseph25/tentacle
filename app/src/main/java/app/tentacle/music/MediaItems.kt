// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONObject

/** Converts library rows and Jellyfin items into Media3 items for Android Auto, the phone app and the player. */
object MediaItems {

    // Android Auto content-style hints (carried in MediaMetadata extras).
    const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val CONTENT_STYLE_GROUP_TITLE_HINT = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
    const val CONTENT_STYLE_SINGLE_ITEM_HINT = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"

    /** Marks a queue item that is already being transcoded (so a format error isn't retried forever). */
    const val EXTRA_TRANSCODED = "app.tentacle.music.TRANSCODED"

    fun root(): MediaItem = MediaItem.Builder()
        .setMediaId(Library.ROOT)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Tentacle")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    /** A browse row (tab, folder, album, track row, "Play all", ...). Not playable by itself: its id is resolved on play. */
    fun browse(context: Context, e: BrowseEntry): MediaItem {
        val extras = Bundle().apply {
            e.group?.let { putString(CONTENT_STYLE_GROUP_TITLE_HINT, it) }
            e.browsableStyle?.let { putInt(CONTENT_STYLE_BROWSABLE_HINT, it.value) }
            e.playableStyle?.let { putInt(CONTENT_STYLE_PLAYABLE_HINT, it.value) }
            e.itemStyle?.let { putInt(CONTENT_STYLE_SINGLE_ITEM_HINT, it.value) }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(e.title)
            .setSubtitle(e.subtitle)
            .setArtworkUri(e.artItemId?.let { ArtworkProvider.uriFor(context, it) } ?: iconUri(context, e.icon))
            .setIsBrowsable(e.browsable)
            .setIsPlayable(e.playable)
            .setMediaType(
                when {
                    e.playable && !e.browsable -> MediaMetadata.MEDIA_TYPE_MUSIC
                    e.icon == IconKind.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
                    e.icon == IconKind.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
                    e.icon == IconKind.PLAYLIST -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                    else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                },
            )
            .setExtras(extras.takeUnless { it.isEmpty })
            .build()
        return MediaItem.Builder().setMediaId(e.mediaId).setMediaMetadata(metadata).build()
    }

    /** A playable queue item for a Jellyfin track: its media id is the Jellyfin item id, its URI the stream. */
    fun track(context: Context, prefs: Prefs, o: JSONObject, quality: StreamQuality = prefs.streamQuality): MediaItem {
        val id = requireSafeId(o.str("Id"))
        val artist = o.artistText()
        val metadata = MediaMetadata.Builder()
            .setTitle(o.str("Name").ifEmpty { "Unknown" })
            .setArtist(artist)
            .setSubtitle(artist)
            .setAlbumTitle(o.str("Album").ifEmpty { null })
            .setAlbumArtist(o.str("AlbumArtist").ifEmpty { null })
            .setArtworkUri(o.artItemId()?.let { ArtworkProvider.uriFor(context, it) } ?: iconUri(context, IconKind.SONG))
            .setDurationMs((o.optLong("RunTimeTicks", 0L) / JellyfinApi.TICKS_PER_MS).takeIf { it > 0 })
            .setTrackNumber(o.optInt("IndexNumber", 0).takeIf { it > 0 })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putBoolean(EXTRA_TRANSCODED, quality != StreamQuality.ORIGINAL) })
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(JellyfinApi.streamUrl(prefs.serverUrl, id, quality))
            .setMimeType(if (quality == StreamQuality.ORIGINAL) null else MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(metadata)
            .build()
    }

    /** The same queue item, re-pointed at a transcoded MP3 stream (for files the phone can't decode). */
    fun transcoded(prefs: Prefs, item: MediaItem): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { putBoolean(EXTRA_TRANSCODED, true) }
        return item.buildUpon()
            .setUri(JellyfinApi.streamUrl(prefs.serverUrl, item.mediaId, StreamQuality.HIGH))
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    fun isTranscoded(item: MediaItem): Boolean = item.mediaMetadata.extras?.getBoolean(EXTRA_TRANSCODED) == true

    /** android.resource:// URI for a built-in white vector icon, as the Android for Cars docs recommend. */
    fun iconUri(context: Context, kind: IconKind): Uri {
        val res = when (kind) {
            IconKind.HOME -> R.drawable.ic_tab_home
            IconKind.ALBUM -> R.drawable.ic_album
            IconKind.ARTIST -> R.drawable.ic_artist
            IconKind.PLAYLIST -> R.drawable.ic_playlist
            IconKind.PLAY -> R.drawable.ic_play
            IconKind.SHUFFLE -> R.drawable.ic_shuffle
            IconKind.SONG -> R.drawable.ic_song
            IconKind.SORT -> R.drawable.ic_sort
            IconKind.INFO -> R.drawable.ic_info
        }
        val r = context.resources
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(r.getResourcePackageName(res))
            .appendPath(r.getResourceTypeName(res))
            .appendPath(r.getResourceEntryName(res))
            .build()
    }
}
