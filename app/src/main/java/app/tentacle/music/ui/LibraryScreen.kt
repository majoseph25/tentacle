// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tentacle.music.BrowseEntry
import app.tentacle.music.ContentStyle
import app.tentacle.music.Library
import kotlinx.coroutines.delay
import app.tentacle.music.MediaItems
import app.tentacle.music.R
import app.tentacle.music.Tailscale

/** One screen in the library back stack, remembering how its children should be drawn. */
private data class Node(val id: String, val title: String, val grid: Boolean)

private const val SONGS_ID = "songs"
private const val ALBUMS_ID = "albums"

/** Items per page for the long A–Z lists (the service caps pages at Library.MAX_PAGE_SIZE). */
private const val LIST_PAGE = 100
private const val MIN_QUERY = 2
private const val SEARCH_DEBOUNCE_MS = 350L

/** The same library tree the car shows (plus a Songs tab), with the same sections and sort orders. */
@Composable
fun LibraryScreen(
    state: PlayerConnection.State,
    player: PlayerConnection,
    onPlayed: () -> Unit,
    contentPadding: PaddingValues,
) {
    var tabs by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var tabError by remember { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val stack = remember { mutableStateListOf<Node>() }

    LaunchedEffect(state.connected) {
        if (!state.connected) return@LaunchedEffect
        tabError = null
        tabs = try {
            val root = player.rootId() ?: throw IllegalStateException("no root")
            player.children(root)
        } catch (e: Exception) {
            tabError = "Couldn't load your library."
            emptyList()
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isEmpty() && query.isNotEmpty()) { query = "" }

    val open: (Node) -> Unit = { stack.add(it) }
    val play: (String) -> Unit = { id ->
        player.play(id)
        onPlayed()
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        if (stack.isEmpty()) {
            SearchField(query, onChange = { query = it })
        }
        if (stack.isEmpty() && query.trim().length >= MIN_QUERY) {
            SearchResults(query.trim(), state.connected, player, open, play, contentPadding)
        } else if (stack.isEmpty()) {
            if (tabs.isNotEmpty()) {
                val index = selectedTab.coerceIn(0, tabs.lastIndex)
                PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 8.dp) {
                    tabs.forEachIndexed { i, tab ->
                        Tab(selected = i == index, onClick = { selectedTab = i }, text = { Text(tab.mediaMetadata.title?.toString().orEmpty()) })
                    }
                }
                val tab = tabs[index]
                NodeContent(
                    Node(tab.mediaId, tab.mediaMetadata.title?.toString().orEmpty(), isGrid(tab)),
                    state.connected, player, open, play, contentPadding,
                )
            } else {
                Message(tabError ?: if (state.connected) "Loading…" else "Connecting…", loading = tabError == null)
            }
        } else {
            val node = stack.last()
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { stack.removeAt(stack.lastIndex) }) {
                    Icon(painterResource(R.drawable.ic_arrow_back), "Back")
                }
                Text(node.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            NodeContent(node, state.connected, player, open, play, contentPadding)
        }
    }
}

@Composable
private fun NodeContent(
    node: Node,
    connected: Boolean,
    player: PlayerConnection,
    onOpen: (Node) -> Unit,
    onPlay: (String) -> Unit,
    bottomPadding: PaddingValues,
) {
    // Counts Tailscale connections: when Tailscale comes up, reload what may have failed without it.
    val tailscaleConnections by Tailscale.connections.collectAsStateWithLifecycle()
    if (node.id == ALBUMS_ID) {
        key(tailscaleConnections) { AlbumsTab(connected, player, onOpen, onPlay, bottomPadding) }
        return
    }
    if (node.id in Library.PAGED_LISTS) {
        key(tailscaleConnections) { PagedList(node, connected, player, onOpen, onPlay, bottomPadding) }
        return
    }
    var items by remember(node.id, tailscaleConnections) { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember(node.id, tailscaleConnections) { mutableStateOf<String?>(null) }
    var attempt by remember(node.id) { mutableIntStateOf(0) }

    // Reloads after a reconnect too, so a page that failed while the app was in the background recovers.
    LaunchedEffect(node.id, attempt, connected, tailscaleConnections) {
        if (!connected) return@LaunchedEffect
        if (items != null && error == null) return@LaunchedEffect
        error = null
        items = try {
            player.children(node.id)
        } catch (e: Exception) {
            error = "Couldn't load this. Check your connection."
            null
        }
    }

    val list = items
    val onClick: (MediaItem) -> Unit = { item -> click(item, onOpen, onPlay) }
    val padding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 8.dp)
    when {
        error != null -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Text(error.orEmpty())
            TextButton(onClick = { attempt++ }) { Text("Try again") }
        }
        list == null -> Message("Loading…", loading = true)
        list.isEmpty() -> Message("Nothing here yet.", loading = false)
        // Grids suit album/artist covers; nodes that are mostly tracks stay a list.
        node.grid && list.count { it.mediaMetadata.isBrowsable == true } > list.size / 2 -> LazyVerticalGrid(
            columns = GridCells.Adaptive(148.dp),
            contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, padding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            list.forEachIndexed { i, item ->
                groupHeader(list, i)?.let { header ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$i") { SectionHeader(header) }
                }
                if (item.mediaMetadata.isBrowsable == true) {
                    item(key = "i$i") { MediaCard(item) { onClick(item) } }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "i$i") { ItemRow(item) { onClick(item) } }
                }
            }
        }
        else -> LazyColumn(contentPadding = padding) {
            list.forEachIndexed { i, item ->
                groupHeader(list, i)?.let { header -> item(key = "h$i") { SectionHeader(header) } }
                item(key = "i$i") { ItemRow(item) { onClick(item) } }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = { onChange(it.take(Library.MAX_QUERY_LENGTH)) },
        placeholder = { Text("Search songs, artists, albums") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(painterResource(R.drawable.ic_close), "Clear search") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Results already update as you type; the keyboard's search key just gets the keyboard out of the way.
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Songs, artists and albums matching [query], searched as you type (after a short pause). */
@Composable
private fun SearchResults(
    query: String,
    connected: Boolean,
    player: PlayerConnection,
    onOpen: (Node) -> Unit,
    onPlay: (String) -> Unit,
    bottomPadding: PaddingValues,
) {
    var results by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Restarting on every keystroke cancels the previous search, so this also debounces typing.
    LaunchedEffect(query, connected) {
        if (!connected) return@LaunchedEffect
        results = null
        error = null
        delay(SEARCH_DEBOUNCE_MS)
        results = try {
            player.search(query)
        } catch (e: Exception) {
            error = "Search failed. Check your connection."
            emptyList()
        }
    }

    val list = results
    when {
        error != null -> Message(error.orEmpty(), loading = false)
        list == null -> Message("Searching…", loading = true)
        list.isEmpty() -> Message("No songs, artists or albums match “$query”.", loading = false)
        else -> LazyColumn(contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 8.dp)) {
            list.forEachIndexed { i, item ->
                groupHeader(list, i)?.let { header -> item(key = "h$i") { SectionHeader(header) } }
                item(key = "i$i") { ItemRow(item) { click(item, onOpen, onPlay) } }
            }
        }
    }
}

/** The Albums tab: every album, A–Z by default, with the other sort orders as chips along the top. */
@Composable
private fun AlbumsTab(
    connected: Boolean,
    player: PlayerConnection,
    onOpen: (Node) -> Unit,
    onPlay: (String) -> Unit,
    bottomPadding: PaddingValues,
) {
    var order by rememberSaveable { mutableStateOf(Library.ALBUM_SORTS.first().first) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Library.ALBUM_SORTS.forEach { (key, title) ->
                FilterChip(selected = key == order, onClick = { order = key }, label = { Text(title) })
            }
        }
        val title = Library.ALBUM_SORTS.first { it.first == order }.second
        key(order) { PagedList(Node("sort:albums:$order", title, grid = true), connected, player, onOpen, onPlay, bottomPadding) }
    }
}

/**
 * A long list (every song, artist or album), loaded a page at a time as you scroll, under the
 * section headers the library sends (A–Z letters, years, artists).
 */
@Composable
private fun PagedList(
    node: Node,
    connected: Boolean,
    player: PlayerConnection,
    onOpen: (Node) -> Unit,
    onPlay: (String) -> Unit,
    bottomPadding: PaddingValues,
) {
    val items = remember { mutableStateListOf<MediaItem>() }
    var nextPage by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            if (node.grid) {
                (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= gridState.layoutInfo.totalItemsCount - 20
            } else {
                (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 20
            }
        }
    }

    LaunchedEffect(connected, nearEnd, nextPage, error) {
        if (!connected || done || loading || error != null || !nearEnd) return@LaunchedEffect
        loading = true
        try {
            val page = player.children(node.id, nextPage, LIST_PAGE)
            items += page
            if (page.size < LIST_PAGE) done = true
            nextPage++
        } catch (e: Exception) {
            error = "Couldn't load more. Check your connection."
        } finally {
            loading = false
        }
    }

    val onClick: (MediaItem) -> Unit = { item -> click(item, onOpen, onPlay) }
    val bottom = bottomPadding.calculateBottomPadding() + 8.dp
    val footer: @Composable () -> Unit = {
        when {
            error != null -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error.orEmpty(), Modifier.weight(1f))
                TextButton(onClick = { error = null }) { Text("Try again") }
            }
            !done -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Message("Nothing here yet.", loading = false)
        }
    }

    if (node.grid) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(148.dp),
            contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, bottom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEachIndexed { i, item ->
                groupHeader(items, i)?.let { header ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$i") { SectionHeader(header) }
                }
                item(key = "i$i") { MediaCard(item) { onClick(item) } }
            }
            // No key: a keyed footer would be kept in view as pages load above it, scrolling the list down.
            item(span = { GridItemSpan(maxLineSpan) }) { footer() }
        }
    } else {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottom)) {
            if (node.id == SONGS_ID) {
                item(key = "shuffle") {
                    MediaRow(
                        "Shuffle all songs", "Random songs from your whole library", null, R.drawable.ic_shuffle,
                        Modifier.clickable { onPlay("shuffle:library:all") },
                    )
                }
            }
            items.forEachIndexed { i, item ->
                groupHeader(items, i)?.let { header -> item(key = "h$i") { SectionHeader(header) } }
                item(key = "i$i") { ItemRow(item) { onClick(item) } }
            }
            // No key: a keyed footer would be kept in view as pages load above it, scrolling the list down.
            item { footer() }
        }
    }
}

private fun click(item: MediaItem, onOpen: (Node) -> Unit, onPlay: (String) -> Unit) {
    val md = item.mediaMetadata
    when {
        item.mediaId == BrowseEntry.NOOP_ID -> Unit
        md.isBrowsable == true -> onOpen(Node(item.mediaId, md.title?.toString().orEmpty(), isGrid(item)))
        md.isPlayable == true -> onPlay(item.mediaId)
    }
}

/** The section header to show before item [i], if its group differs from the previous item's. */
private fun groupHeader(list: List<MediaItem>, i: Int): String? {
    val group = list[i].mediaMetadata.extras?.getString(MediaItems.CONTENT_STYLE_GROUP_TITLE_HINT) ?: return null
    val previous = if (i > 0) list[i - 1].mediaMetadata.extras?.getString(MediaItems.CONTENT_STYLE_GROUP_TITLE_HINT) else null
    return group.takeIf { it != previous }
}

private fun isGrid(item: MediaItem): Boolean =
    item.mediaMetadata.extras?.getInt(MediaItems.CONTENT_STYLE_BROWSABLE_HINT, ContentStyle.LIST.value) == ContentStyle.GRID.value

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ItemRow(item: MediaItem, onClick: () -> Unit) {
    val md = item.mediaMetadata
    MediaRow(
        md.title?.toString().orEmpty(), md.subtitle?.toString(), md.artworkUri,
        if (md.isBrowsable == true) R.drawable.ic_album else R.drawable.ic_song,
        Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun MediaCard(item: MediaItem, onClick: () -> Unit) {
    val md = item.mediaMetadata
    Column(Modifier.clickable(onClick = onClick)) {
        Artwork(md.artworkUri, R.drawable.ic_album, RoundedCornerShape(12.dp), Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(
            md.title?.toString().orEmpty(), style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        md.subtitle?.toString()?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A list row: artwork, title and subtitle. Shared with "Up next". */
@Composable
fun MediaRow(title: String, subtitle: String?, icon: Uri?, fallback: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(icon, fallback, RoundedCornerShape(8.dp), Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, loading: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
            }
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
