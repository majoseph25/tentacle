// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Android Auto loads artwork from content:// URIs. This downloads the image from Jellyfin
 * on first request and serves it from a small on-disk cache.
 *
 * The provider has to be exported for Android Auto to reach it, so every call checks the caller
 * against [ClientAccess]. Other apps can't use it to pull your library's images or make requests.
 */
class ArtworkProvider : ContentProvider() {

    private val api by lazy { JellyfinApi(Prefs(context!!)) }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        if (!ClientAccess.isAllowed(ctx, Binder.getCallingUid())) throw SecurityException("Not allowed")
        if (mode != "r") throw FileNotFoundException("read-only")
        // The id comes from the requesting app. Only a Jellyfin GUID is accepted, rebuilt from its numeric
        // value, so none of the caller's text reaches the cache file name or the server URL.
        // Called directly, not via `?.let(::canonicalItemId)`: code scanning treats `let` as passing its
        // input straight through and wouldn't see the rebuild.
        val raw = uri.lastPathSegment ?: throw FileNotFoundException("bad id")
        val id = canonicalItemId(raw) ?: throw FileNotFoundException("bad id")

        val dir = cacheDir(ctx).apply { mkdirs() }
        val file = File(dir, "$id.jpg")
        if (!file.exists()) {
            if ((dir.list()?.size ?: 0) > MAX_CACHED) dir.listFiles()?.forEach { it.delete() }
            // Unique temp file, so two simultaneous requests for the same image can't corrupt each other.
            var tmp: File? = null
            try {
                tmp = File.createTempFile("art-", ".tmp", dir)
                tmp.writeBytes(api.downloadPrimaryImage(id))
                if (!tmp.renameTo(file) && !file.exists()) throw FileNotFoundException("cache write failed")
            } catch (e: Exception) {
                throw FileNotFoundException("artwork unavailable")
            } finally {
                tmp?.delete()
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        private const val MAX_CACHED = 300

        private fun cacheDir(context: Context) = File(context.cacheDir, "art")

        fun uriFor(context: Context, itemId: String): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.artwork").appendPath(itemId).build()

        fun cacheSize(context: Context): Long =
            cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L

        /** Removes cached artwork, e.g. on sign-out so the next account doesn't see it. */
        fun clearCache(context: Context) {
            cacheDir(context).deleteRecursively()
        }
    }
}
