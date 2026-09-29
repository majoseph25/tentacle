// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import org.json.JSONObject

/**
 * Builds the browse tree (shared by Android Auto and the phone app) and resolves what to play.
 *
 * The root's children are tabs: home | albums | artists | playlists, plus songs for the phone app
 * (Android Auto allows four tabs, so there "All songs" lives under Home instead).
 *
 * Other media ids:
 *   songs                                 every song A–Z (paged for the phone; A–Z picker in the car)
 *   sort:albums:<name|artist|year|added>  albums in one sort order (grid, with section headers)
 *   az:albums:A | az:artists:A | az:songs:A   one letter of the A–Z picker used for large libraries
 *   album:ID | artist:ID | playlist:ID    containers
 *   track:ID|<ctx>                        a track, where ctx is the queue it belongs to (album:ID, songs:N, ...)
 *   ctx:<ctx> | shuffle:<ctx>             "Play all" / "Shuffle" rows; ctx may also be recent:all or library:all
 */
class Library(private val api: JellyfinApi, private val prefs: Prefs) {

    /** Tracks to play, as full item JSON (title, artist, art, duration), starting at [start]. */
    data class Tracks(val items: List<JSONObject>, val start: Int)

    /**
     * Children of [parentId]. [pageSize] is Int.MAX_VALUE for clients that don't page (Android Auto),
     * which get A–Z pickers instead of very long lists.
     */
    fun children(parentId: String, page: Int = 0, pageSize: Int = Int.MAX_VALUE, withSongsTab: Boolean = false): List<BrowseEntry> {
        if (!prefs.isSignedIn) {
            return listOf(BrowseEntry.message("Open Tentacle on your phone and sign in"))
        }
        return when {
            parentId == ROOT -> root(withSongsTab)
            parentId == "home" -> home()
            parentId == "songs" -> songs(page, pageSize)
            parentId == "albums" -> albumSorts()
            parentId == "artists" -> {
                val page0 = api.albumArtists(artistQuery(emptyMap()))
                if (page0.total <= MAX_LIST) grouped(page0.items, ::letterOf, ::artistEntry) else letters("artists", ContentStyle.LIST)
            }
            parentId == "playlists" -> api.items(
                mapOf(
                    "IncludeItemTypes" to "Playlist", "MediaTypes" to "Audio", "SortBy" to "SortName",
                    "Fields" to "ChildCount", "Limit" to MAX_LIST.toString(),
                ),
            ).items.map(::playlistEntry)
            parentId.startsWith("sort:albums:") -> albumsSorted(parentId.substringAfterLast(':'))
            parentId.startsWith("az:albums:") ->
                api.items(albumQuery(letterFilter(parentId.substringAfterLast(':')) + ("Limit" to MAX_LIST.toString())))
                    .items.map { albumEntry(it) }
            parentId.startsWith("az:artists:") ->
                api.albumArtists(artistQuery(letterFilter(parentId.substringAfterLast(':')))).items.map(::artistEntry)
            parentId.startsWith("az:songs:") -> {
                val letter = parentId.substringAfterLast(':')
                trackEntries(api.items(songsQuery(0, MAX_LIST) + letterFilter(letter)).items, "songsaz:${letterKey(letter)}")
            }
            parentId.startsWith("album:") -> albumTracks(requireSafeId(parentId.removePrefix("album:")))
            parentId.startsWith("playlist:") -> {
                val id = requireSafeId(parentId.removePrefix("playlist:"))
                playRows("playlist:$id") + trackEntries(api.playlistItems(id, MAX_TRACKS).items, "playlist:$id", showAlbum = true)
            }
            parentId.startsWith("artist:") -> {
                val id = requireSafeId(parentId.removePrefix("artist:"))
                val albums = api.items(
                    albumQuery(mapOf("AlbumArtistIds" to id, "SortBy" to "ProductionYear,SortName", "SortOrder" to "Descending")),
                ).items
                playRows("artist:$id") + albums.map { albumEntry(it, subtitle = it.yearText(), group = "Albums") }
            }
            else -> emptyList()
        }
    }

    /** Resolves a browse id (a track row, "Play all", "Shuffle", or a container from voice) to tracks. */
    fun resolvePlayback(mediaId: String): Tracks? {
        val result = when {
            mediaId.startsWith("track:") -> {
                val body = mediaId.removePrefix("track:")
                val trackId = requireSafeId(body.substringBefore('|'))
                val ctx = body.substringAfter('|', "")
                val items = if (ctx.isEmpty()) api.itemsByIds(listOf(trackId)) else tracks(ctx)
                val index = items.indexOfFirst { it.str("Id") == trackId }
                if (index >= 0) window(items, index) else Tracks(api.itemsByIds(listOf(trackId)), 0)
            }
            mediaId.startsWith("ctx:") -> window(tracks(mediaId.removePrefix("ctx:")), 0)
            mediaId.startsWith("shuffle:") -> window(tracks(mediaId.removePrefix("shuffle:")).shuffled(), 0)
            mediaId.startsWith("album:") || mediaId.startsWith("artist:") || mediaId.startsWith("playlist:") ->
                window(tracks(mediaId), 0)
            else -> null
        }
        return result?.takeIf { it.items.isNotEmpty() }
    }

    /** Voice ("play <query>"): plays the best match. */
    fun searchPlay(query: String): Tracks? {
        val hit = api.items(
            mapOf("SearchTerm" to query, "IncludeItemTypes" to "MusicAlbum,MusicArtist,Playlist,Audio", "Limit" to "5"),
        ).items.firstOrNull() ?: return null
        val id = requireSafeId(hit.str("Id"))
        return when (hit.str("Type")) {
            "MusicAlbum" -> window(tracks("album:$id"), 0)
            "MusicArtist" -> window(tracks("artist:$id"), 0)
            "Playlist" -> window(tracks("playlist:$id"), 0)
            else -> Tracks(listOf(hit), 0)
        }.takeIf { it.items.isNotEmpty() }
    }

    /**
     * Search results (phone app and Android Auto): songs, artists and albums, under section headers.
     * A song plays followed by the rest of its album.
     */
    fun search(query: String): List<BrowseEntry> {
        val term = query.trim().take(MAX_QUERY_LENGTH)
        if (term.isEmpty()) return emptyList()
        return api.items(
            mapOf("SearchTerm" to term, "IncludeItemTypes" to "Audio,MusicArtist,MusicAlbum", "Limit" to SEARCH_LIMIT.toString()),
        ).items.mapNotNull { o ->
            when (o.str("Type")) {
                "MusicAlbum" -> albumEntry(o, group = "Albums")
                "MusicArtist" -> artistEntry(o).copy(group = "Artists")
                "Audio" -> {
                    val albumId = o.str("AlbumId").takeIf(::isSafeId)
                    trackEntries(listOf(o), albumId?.let { "album:$it" }.orEmpty(), showAlbum = true).single().copy(
                        mediaId = if (albumId != null) "track:${o.str("Id")}|album:$albumId" else "track:${o.str("Id")}",
                        group = "Songs",
                    )
                }
                else -> null
            }
        }.sortedBy { listOf("Songs", "Artists", "Albums").indexOf(it.group) }
    }

    // ---- tabs ----

    private fun root(withSongsTab: Boolean) = listOfNotNull(
        BrowseEntry(
            "home", "Home", browsable = true, icon = IconKind.HOME,
            browsableStyle = ContentStyle.GRID, playableStyle = ContentStyle.LIST,
        ),
        if (withSongsTab) BrowseEntry("songs", "Songs", browsable = true, icon = IconKind.SONG) else null,
        BrowseEntry("albums", "Albums", browsable = true, icon = IconKind.ALBUM, browsableStyle = ContentStyle.LIST),
        BrowseEntry("artists", "Artists", browsable = true, icon = IconKind.ARTIST, browsableStyle = ContentStyle.LIST),
        BrowseEntry(
            "playlists", "Playlists", browsable = true, icon = IconKind.PLAYLIST,
            browsableStyle = ContentStyle.GRID, playableStyle = ContentStyle.LIST,
        ),
    )

    private fun home(): List<BrowseEntry> {
        val recent = api.items(recentQuery(HOME_ROWS)).items
        val added = api.items(albumQuery(mapOf("SortBy" to "DateCreated", "SortOrder" to "Descending", "Limit" to HOME_ROWS.toString()))).items
        return listOf(
            BrowseEntry(
                "shuffle:library:all", "Shuffle my library", "Random songs from your whole library",
                playable = true, icon = IconKind.SHUFFLE,
            ),
            // Home draws its browsable rows as tiles (for the Recently added albums); keep this one a list row.
            BrowseEntry(
                "songs", "All songs", "A–Z", browsable = true, icon = IconKind.SONG,
                browsableStyle = ContentStyle.LIST, itemStyle = ContentStyle.LIST,
            ),
        ) +
            trackEntries(recent, "recent:all", showAlbum = true).map { it.copy(group = "Recently played") } +
            added.map { albumEntry(it, group = "Recently added") }
    }

    /**
     * Every song A–Z. Paging clients get exactly one page of songs (Media3 rejects a page with more
     * items than requested, so nothing else is added to it); others get the list, or an A–Z picker
     * when it's long.
     */
    private fun songs(page: Int, pageSize: Int): List<BrowseEntry> {
        if (pageSize != Int.MAX_VALUE) {
            val size = pageSize.coerceIn(1, MAX_PAGE_SIZE)
            val start = pageStart(page, size)
            val items = api.items(songsQuery(start, size)).items
            return items.take(size).mapIndexed { i, o -> songEntry(o, start + i) }
        }
        val first = api.items(songsQuery(0, MAX_LIST))
        if (first.total > MAX_LIST) return listOf(shuffleAllSongs()) + letters("songs", ContentStyle.LIST)
        return listOf(shuffleAllSongs()) + first.items.mapIndexed { i, o -> songEntry(o, i) }
    }

    private fun shuffleAllSongs() = BrowseEntry(
        "shuffle:library:all", "Shuffle all songs", playable = true, icon = IconKind.SHUFFLE,
    )

    private fun songEntry(o: JSONObject, index: Int): BrowseEntry {
        val artist = o.artistText()
        val album = o.str("Album")
        return BrowseEntry(
            "track:${o.str("Id")}|songs:$index", o.str("Name").ifEmpty { "Unknown" },
            if (album.isNotEmpty()) "$artist · $album" else artist, o.artItemId(),
            playable = true, group = letterOf(o),
        )
    }

    private fun albumSorts() = listOf(
        "name" to "A–Z", "artist" to "By artist", "year" to "Newest releases", "added" to "Recently added",
    ).map { (order, title) ->
        BrowseEntry("sort:albums:$order", title, browsable = true, icon = IconKind.SORT, browsableStyle = ContentStyle.GRID)
    }

    private fun albumsSorted(order: String): List<BrowseEntry> {
        val limit = "Limit" to MAX_LIST.toString()
        return when (order) {
            "name" -> {
                val page = api.items(albumQuery(mapOf(limit)))
                if (page.total <= MAX_LIST) grouped(page.items, ::letterOf) { albumEntry(it) } else letters("albums", ContentStyle.GRID)
            }
            "artist" -> grouped(
                api.items(albumQuery(mapOf("SortBy" to "AlbumArtist,ProductionYear,SortName", limit))).items,
                { it.str("AlbumArtist").ifEmpty { "Unknown artist" } },
            ) { albumEntry(it, subtitle = it.yearText()) }
            "year" -> grouped(
                api.items(albumQuery(mapOf("SortBy" to "ProductionYear,SortName", "SortOrder" to "Descending", limit))).items,
                { it.yearText().ifEmpty { "Unknown year" } },
            ) { albumEntry(it) }
            "added" -> api.items(albumQuery(mapOf("SortBy" to "DateCreated", "SortOrder" to "Descending", limit)))
                .items.map { albumEntry(it) }
            else -> emptyList()
        }
    }

    /** Numbered tracks with durations; multi-disc albums get a header per disc. */
    private fun albumTracks(albumId: String): List<BrowseEntry> {
        val tracks = api.items(albumTracksQuery(albumId, MAX_TRACKS)).items
        val multiDisc = tracks.map { it.optInt("ParentIndexNumber", 1) }.distinct().size > 1
        return playRows("album:$albumId") + tracks.map { o ->
            val number = o.optInt("IndexNumber", 0)
            val title = o.str("Name").ifEmpty { "Unknown" }
            BrowseEntry(
                mediaId = "track:${o.str("Id")}|album:$albumId",
                title = if (number > 0) "$number. $title" else title,
                subtitle = listOf(o.artistText(), o.durationText()).filter { it.isNotEmpty() }.joinToString(" · "),
                artItemId = o.artItemId(),
                playable = true,
                group = if (multiDisc) "Disc ${o.optInt("ParentIndexNumber", 1)}" else null,
            )
        }
    }

    // ---- queue building ----

    /** The tracks of a playback context, in play order. */
    private fun tracks(ctx: String): List<JSONObject> {
        val type = ctx.substringBefore(':')
        val arg = ctx.substringAfter(':', "")
        return when (type) {
            "album" -> api.items(albumTracksQuery(requireSafeId(arg), MAX_QUEUE_FETCH)).items
            "playlist" -> api.playlistItems(requireSafeId(arg), MAX_QUEUE_FETCH).items
            "artist" -> api.items(
                mapOf(
                    "ArtistIds" to requireSafeId(arg), "IncludeItemTypes" to "Audio",
                    "SortBy" to "Album,ParentIndexNumber,IndexNumber,SortName", "Limit" to MAX_QUEUE_FETCH.toString(),
                ),
            ).items
            "recent" -> api.items(recentQuery(50)).items
            "library" -> api.items(
                mapOf("IncludeItemTypes" to "Audio", "SortBy" to "Random", "Limit" to QUEUE_WINDOW.toString()),
            ).items
            // A window of the A–Z song list around position N, so "next" carries on alphabetically.
            "songs" -> {
                val index = arg.toIntOrNull()?.coerceAtLeast(0) ?: throw JellyfinException("Invalid position")
                api.items(songsQuery((index - WINDOW_BEFORE).coerceAtLeast(0), QUEUE_WINDOW)).items
            }
            "songsaz" -> api.items(songsQuery(0, MAX_QUEUE_FETCH) + letterFilter(letterFromKey(arg))).items
            else -> emptyList()
        }
    }

    /**
     * Keeps queues to a sensible size around the chosen track (a few tracks before it so "previous"
     * still works). Long queues are also what makes starting playback slow.
     */
    fun window(items: List<JSONObject>, start: Int): Tracks {
        if (items.isEmpty()) return Tracks(emptyList(), 0)
        val from = (start - WINDOW_BEFORE).coerceAtLeast(0)
        val to = minOf(items.size, from + QUEUE_WINDOW)
        return Tracks(items.subList(from, to), start - from)
    }

    // ---- queries ----

    private fun albumQuery(extra: Map<String, String>) = mapOf(
        "IncludeItemTypes" to "MusicAlbum", "SortBy" to "SortName", "SortOrder" to "Ascending", "Fields" to "SortName",
    ) + extra

    private fun artistQuery(extra: Map<String, String>) =
        mapOf("SortBy" to "SortName", "SortOrder" to "Ascending", "Fields" to "SortName", "Limit" to MAX_LIST.toString()) + extra

    private fun songsQuery(start: Int, limit: Int) = mapOf(
        "IncludeItemTypes" to "Audio", "SortBy" to "SortName", "SortOrder" to "Ascending", "Fields" to "SortName",
        "StartIndex" to start.toString(), "Limit" to limit.toString(),
    )

    private fun albumTracksQuery(albumId: String, limit: Int) = mapOf(
        "ParentId" to albumId, "IncludeItemTypes" to "Audio",
        "SortBy" to "ParentIndexNumber,IndexNumber,SortName", "Limit" to limit.toString(),
    )

    private fun recentQuery(limit: Int) = mapOf(
        "IncludeItemTypes" to "Audio", "Filters" to "IsPlayed",
        "SortBy" to "DatePlayed", "SortOrder" to "Descending", "Limit" to limit.toString(),
    )

    private fun letterFilter(letter: String): Map<String, String> =
        if (letter == "#") mapOf("NameLessThan" to "A") else mapOf("NameStartsWith" to letter.take(1).uppercase())

    /** "#" can't go in a media id segment cleanly; stored as "0". */
    private fun letterKey(letter: String) = if (letter == "#") "0" else letter.take(1).uppercase()

    private fun letterFromKey(key: String) = if (key == "0") "#" else key.take(1).uppercase()

    // ---- entries ----

    /** Maps items to rows under section headers. Items must already be sorted by [groupOf]. */
    private fun grouped(
        items: List<JSONObject>,
        groupOf: (JSONObject) -> String,
        entry: (JSONObject) -> BrowseEntry,
    ): List<BrowseEntry> = items.map { entry(it).copy(group = groupOf(it)) }

    /** Header letter, based on the same sort name the server orders by ("The Beatles" → B). */
    private fun letterOf(o: JSONObject): String {
        val c = o.str("SortName").ifEmpty { o.str("Name") }.firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    private fun letters(kind: String, childStyle: ContentStyle) =
        (listOf("#") + ('A'..'Z').map { it.toString() }).map {
            BrowseEntry(
                "az:$kind:$it", it, browsable = true, browsableStyle = childStyle,
                icon = when (kind) {
                    "artists" -> IconKind.ARTIST
                    "songs" -> IconKind.SONG
                    else -> IconKind.ALBUM
                },
            )
        }

    private fun playRows(ctx: String) = listOf(
        BrowseEntry("ctx:$ctx", "Play all", playable = true, icon = IconKind.PLAY),
        BrowseEntry("shuffle:$ctx", "Shuffle", playable = true, icon = IconKind.SHUFFLE),
    )

    private fun albumEntry(o: JSONObject, subtitle: String? = o.str("AlbumArtist"), group: String? = null) = BrowseEntry(
        "album:${o.str("Id")}", o.str("Name").ifEmpty { "Unknown album" }, subtitle, o.artItemId(),
        browsable = true, group = group, playableStyle = ContentStyle.LIST, icon = IconKind.ALBUM,
    )

    private fun artistEntry(o: JSONObject) = BrowseEntry(
        "artist:${o.str("Id")}", o.str("Name").ifEmpty { "Unknown artist" }, null, o.artItemId(),
        browsable = true, browsableStyle = ContentStyle.GRID, playableStyle = ContentStyle.LIST, icon = IconKind.ARTIST,
    )

    private fun playlistEntry(o: JSONObject): BrowseEntry {
        val count = o.optInt("ChildCount", -1)
        return BrowseEntry(
            "playlist:${o.str("Id")}", o.str("Name").ifEmpty { "Untitled playlist" },
            if (count >= 0) "$count song${if (count == 1) "" else "s"}" else null,
            o.artItemId(), browsable = true, playableStyle = ContentStyle.LIST, icon = IconKind.PLAYLIST,
        )
    }

    private fun trackEntries(items: List<JSONObject>, ctx: String, showAlbum: Boolean = false) = items.map { o ->
        val artist = o.artistText()
        val album = o.str("Album")
        val subtitle = if (showAlbum && album.isNotEmpty()) "$artist · $album" else artist
        BrowseEntry("track:${o.str("Id")}|$ctx", o.str("Name").ifEmpty { "Unknown" }, subtitle, o.artItemId(), playable = true)
    }

    companion object {
        const val ROOT = "root"

        /** Android Auto has no pagination, so keep lists short enough to load quickly. */
        const val MAX_LIST = 200
        const val MAX_TRACKS = 300
        const val MAX_QUEUE_FETCH = 1000
        const val QUEUE_WINDOW = 150
        const val MAX_PAGE_SIZE = 200
        const val MAX_QUERY_LENGTH = 100
        private const val SEARCH_LIMIT = 60
        private const val WINDOW_BEFORE = 10
        private const val HOME_ROWS = 12
    }
}

private fun JSONObject.yearText(): String = optInt("ProductionYear", 0).takeIf { it > 0 }?.toString().orEmpty()

private fun JSONObject.durationText(): String {
    val seconds = optLong("RunTimeTicks", 0L) / JellyfinApi.TICKS_PER_MS / 1000
    return if (seconds <= 0) "" else "%d:%02d".format(seconds / 60, seconds % 60)
}
