// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.tentacle.music.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small in-memory cache so scrolling back through a list doesn't reload covers. */
private object ArtworkCache : LruCache<String, ImageBitmap>(120)

/**
 * Shows an item's artwork. content:// URIs are album art from ArtworkProvider (loaded off the main
 * thread); android.resource:// URIs are the built-in white icons, drawn tinted on a tile.
 */
@Composable
fun Artwork(uri: Uri?, fallback: Int, shape: Shape, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val iconRes = uri?.takeIf { it.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE }?.let { iconResource(context, it) }
    val bitmap by produceState<ImageBitmap?>(uri?.let { ArtworkCache.get(it.toString()) }, uri) {
        // Reset on every new uri so a previous track's cover never lingers.
        val cached = uri?.let { ArtworkCache.get(it.toString()) }
        value = cached
        if (cached != null || uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT) return@produceState
        value = withContext(Dispatchers.IO) { load(context, uri) }?.also { ArtworkCache.put(uri.toString(), it) }
    }
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                painterResource(iconRes ?: fallback),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(0.45f),
            )
        }
    }
}

/**
 * Decodes album art at a bounded size. A cover can come from any file in the library (or a server that
 * skips resizing), so reading the dimensions first keeps a huge image from exhausting memory and taking
 * down this process, which also hosts the Android Auto service.
 */
private fun load(context: Context, uri: Uri): ImageBitmap? {
    // Only this app's own artwork provider; never an arbitrary content:// URI.
    if (uri.authority != "${context.packageName}.artwork") return null
    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = artworkSampleSize(bounds.outWidth, bounds.outHeight, MAX_ARTWORK_PX) ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

private const val MAX_ARTWORK_PX = 1024
private const val MAX_SOURCE_PX = 20_000

/**
 * Power-of-two downsampling factor that brings the longest side to at most [maxPx],
 * or null if the dimensions are missing or absurd (not an image worth decoding).
 */
internal fun artworkSampleSize(width: Int, height: Int, maxPx: Int): Int? {
    if (width <= 0 || height <= 0 || width > MAX_SOURCE_PX || height > MAX_SOURCE_PX) return null
    var sample = 1
    while (maxOf(width, height) / sample > maxPx) sample *= 2
    return sample
}

/** Maps android.resource://pkg/drawable/name to a drawable id, only for this app's own drawables. */
private fun iconResource(context: Context, uri: Uri): Int? {
    if (uri.authority != context.packageName || uri.pathSegments.size != 2 || uri.pathSegments[0] != "drawable") return null
    return ICONS[uri.pathSegments[1]]
}

/** Explicit table (rather than getIdentifier) so resource shrinking keeps them and lookups can't stray. */
private val ICONS = mapOf(
    "ic_tab_home" to R.drawable.ic_tab_home,
    "ic_album" to R.drawable.ic_album,
    "ic_artist" to R.drawable.ic_artist,
    "ic_playlist" to R.drawable.ic_playlist,
    "ic_play" to R.drawable.ic_play,
    "ic_shuffle" to R.drawable.ic_shuffle,
    "ic_song" to R.drawable.ic_song,
    "ic_sort" to R.drawable.ic_sort,
    "ic_info" to R.drawable.ic_info,
)
