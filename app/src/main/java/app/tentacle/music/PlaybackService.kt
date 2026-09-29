// Copyright 2026 Mark Joseph
// SPDX-License-Identifier: Apache-2.0

package app.tentacle.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.tentacle.music.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Plays music streamed from the Jellyfin server and exposes it to everything that controls media:
 * this app's screens, Android Auto, the notification and lock screen, Bluetooth and steering-wheel
 * buttons, and Google Assistant.
 *
 * Security model:
 *  - Browsing the library, searching and choosing what to play are limited to trusted callers
 *    ([ClientAccess]: this app, Android Auto, Assistant, the system). Everyone else may only use
 *    transport controls (play/pause/skip/seek) on what's already playing.
 *  - Whatever a caller asks to play is resolved from its media id against the library; a caller can
 *    never supply a URL, so the player only ever streams from the signed-in server.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private lateinit var prefs: Prefs
    private lateinit var api: JellyfinApi
    private lateinit var library: Library
    private lateinit var player: ExoPlayer
    private lateinit var reporter: PlaybackReporter
    private var session: MediaLibrarySession? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Max tabs the car allows at the root (it may ask for fewer than our four). */
    private var rootLimit = DEFAULT_ROOT_LIMIT

    /** Results of the last search, for Android Auto's search screen. */
    private var searchResults: Pair<String, List<BrowseEntry>>? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        api = JellyfinApi(prefs)
        library = Library(api, prefs)

        // HTTP(S) only: the player can't be pointed at local files or content:// URIs, only at streams,
        // and the streaming client adds the token only for the signed-in server.
        val dataSource = OkHttpDataSource.Factory(JellyfinApi.streamingClient(prefs))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones/Bluetooth disconnect
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)
        reporter = PlaybackReporter(player, api, prefs, scope)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(modeButtons())
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )
        // Started by the car, Bluetooth or the phone app: connect Tailscale now if the settings ask for it.
        if (prefs.isSignedIn) Tailscale.ensureAsync(this, prefs)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        reporter.release()
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- shuffle / repeat buttons (Android Auto, notification, phone app) ----

    private fun modeButtons(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (player.shuffleModeEnabled) "Shuffle is on" else "Shuffle is off")
            .setSessionCommand(SHUFFLE)
            .build(),
        CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeating this song"
                    Player.REPEAT_MODE_ALL -> "Repeating all"
                    else -> "Repeat is off"
                },
            )
            .setSessionCommand(REPEAT)
            .build(),
    )

    private val playerListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            session?.setMediaButtonPreferences(modeButtons())
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            session?.setMediaButtonPreferences(modeButtons())
        }

        /** Files the phone can't decode (e.g. some lossless or legacy formats) are retried as transcoded MP3 once. */
        override fun onPlayerError(error: PlaybackException) {
            // Couldn't reach the server (e.g. left home Wi-Fi): connect Tailscale if allowed, then retry once.
            if (error.errorCode in NETWORK_ERRORS) {
                reconnectViaTailscale {
                    if (player.playerError != null) {
                        player.prepare()
                        player.play()
                    }
                }
                return
            }
            if (error.errorCode !in FORMAT_ERRORS) return
            val item = player.currentMediaItem ?: return
            if (MediaItems.isTranscoded(item) || !isSafeId(item.mediaId)) return
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            player.replaceMediaItem(index, MediaItems.transcoded(prefs, item))
            player.seekTo(index, position)
            player.prepare()
            player.play()
        }
    }

    // ---- session callback: access control, browsing, search, resolving what to play ----

    private val callback = object : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val trusted = isTrusted(controller, record = false)
            // The service must be exported for Android Auto, so any app can try to connect. An app that
            // isn't trusted is refused outright: otherwise it could read what you're playing and your queue.
            // The one exception is the system's combined "legacy controller" (Android 8 lock screen,
            // Bluetooth and headset buttons, whose callers can't be told apart); it gets transport controls only.
            if (!trusted && controller.packageName != MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME) {
                isTrusted(controller, record = true) // shows up under Settings > Android Auto connections
                return MediaSession.ConnectionResult.reject()
            }
            val sessionCommands = (
                if (trusted) {
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                } else {
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                }
                ).buildUpon().add(SHUFFLE).add(REPEAT).build()
            // The legacy controller gets transport controls only: it can't load items or browse the library.
            val playerCommands = if (trusted) {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .build()
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setMediaButtonPreferences(modeButtons())
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (!isTrusted(browser, record = true)) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            }
            // The car (or the phone app) is about to browse: start Tailscale now if the settings ask for it.
            if (prefs.isSignedIn) Tailscale.ensureAsync(this@PlaybackService, prefs)
            // Per the Android for Cars docs, assume 4 tabs when the host sends no hint.
            rootLimit = params?.extras?.getInt(ROOT_CHILDREN_LIMIT, DEFAULT_ROOT_LIMIT)?.takeIf { it > 0 } ?: DEFAULT_ROOT_LIMIT
            val extras = Bundle().apply {
                putBoolean(MediaItems.CONTENT_STYLE_SUPPORTED, true)
                putInt(MediaItems.CONTENT_STYLE_BROWSABLE_HINT, ContentStyle.LIST.value)
                putInt(MediaItems.CONTENT_STYLE_PLAYABLE_HINT, ContentStyle.LIST.value)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(MediaItems.root(), LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!isTrusted(browser, record = false)) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            }
            val ownApp = isOwnApp(browser)
            return scope.future(Dispatchers.IO) {
                Tailscale.awaitInFlight(TAILSCALE_WAIT_MS)
                val serverPaged = ownApp && parentId == SONGS
                val entries = try {
                    // Only this app's Songs tab pages on the server; everything else is built whole
                    // (Android Auto gets whole lists or A–Z pickers) and sliced below if a page was asked for.
                    library.children(parentId, page, if (serverPaged) pageSize else Int.MAX_VALUE, withSongsTab = ownApp)
                        .let { if (parentId == Library.ROOT && !ownApp) it.take(rootLimit) else it }
                        .let { if (serverPaged) it else pageOf(it, page, pageSize) }
                } catch (e: Exception) {
                    JellyfinApi.warn("browse failed", e)
                    reconnectViaTailscale() // lists reload by themselves if Tailscale comes up
                    listOf(BrowseEntry.message("Couldn't load. Check your connection."))
                }
                // Media3 treats a page larger than requested as a fatal error, so never return one.
                val safe = if (pageSize in 1 until Int.MAX_VALUE) entries.take(pageSize) else entries
                LibraryResult.ofItemList(safe.map { MediaItems.browse(this@PlaybackService, it) }, params)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            if (!isTrusted(browser, record = false)) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            }
            return scope.future(Dispatchers.IO) {
                Tailscale.awaitInFlight(TAILSCALE_WAIT_MS)
                val results = runSearch(query, fresh = true)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    session.notifySearchResultChanged(browser, query, results.size, params)
                }
                LibraryResult.ofVoid()
            }
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (!isTrusted(browser, record = false)) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
            }
            // Normally answered from the search that just ran; searched again if another caller's search replaced it.
            return scope.future(Dispatchers.IO) {
                val results = pageOf(runSearch(query), page, pageSize)
                LibraryResult.ofItemList(results.map { MediaItems.browse(this@PlaybackService, it) }, params)
            }
        }

        /** Replaces the queue: resolves browse ids (or a voice query) into streamable tracks. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            Tailscale.awaitInFlight(TAILSCALE_WAIT_MS)
            val single = mediaItems.singleOrNull()
            val query = single?.requestMetadata?.searchQuery
            when {
                query != null || (single != null && !isSafeId(single.mediaId)) -> {
                    val tracks = if (query != null) {
                        if (query.isBlank()) library.resolvePlayback("ctx:recent:all") else library.searchPlay(query)
                    } else {
                        library.resolvePlayback(single!!.mediaId)
                    } ?: throw JellyfinException("Nothing to play")
                    MediaSession.MediaItemsWithStartPosition(toQueue(tracks.items), tracks.start, C.TIME_UNSET)
                }
                else -> {
                    // A list of Jellyfin item ids (the phone app's queue actions, playback resumption).
                    val items = toQueue(api.itemsByIds(mediaItems.take(MAX_QUEUE_ITEMS).map { it.mediaId }))
                    if (items.isEmpty()) throw JellyfinException("Nothing to play")
                    MediaSession.MediaItemsWithStartPosition(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs)
                }
            }
        }

        /** Adds to the queue (e.g. "play next"): each id is resolved the same way. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future(Dispatchers.IO) {
            mediaItems.take(MAX_QUEUE_ITEMS).flatMap { item ->
                val query = item.requestMetadata.searchQuery
                val tracks: List<JSONObject> = when {
                    query != null -> library.searchPlay(query)?.items.orEmpty()
                    isSafeId(item.mediaId) -> api.itemsByIds(listOf(item.mediaId))
                    else -> library.resolvePlayback(item.mediaId)?.items.orEmpty()
                }
                toQueue(tracks)
            }.toMutableList()
        }

        /** "Play" from the car, Bluetooth or the notification after a restart: pick up where we left off. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            Tailscale.awaitInFlight(TAILSCALE_WAIT_MS)
            val resume = prefs.resume() ?: throw JellyfinException("Nothing to resume")
            val items = toQueue(api.itemsByIds(resume.itemIds))
            if (items.isEmpty()) throw JellyfinException("Nothing to resume")
            MediaSession.MediaItemsWithStartPosition(items, resume.index.coerceIn(0, items.lastIndex), resume.positionMs)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SHUFFLE.customAction -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                REPEAT.customAction -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun toQueue(items: List<JSONObject>): List<MediaItem> {
        if (!prefs.isSignedIn) throw JellyfinException("Not signed in")
        val quality = prefs.streamQuality
        return items.filter { isSafeId(it.str("Id")) }.map { MediaItems.track(this, prefs, it, quality) }
    }

    private fun isOwnApp(controller: MediaSession.ControllerInfo) = controller.uid == Process.myUid()

    /**
     * The server couldn't be reached. If the Tailscale setting is on, asks Tailscale to connect; once it
     * does, tells every browser (the car, the phone app) to reload, then runs [onConnected].
     */
    private fun reconnectViaTailscale(onConnected: () -> Unit = {}) {
        if (prefs.tailscaleMode == TailscaleMode.OFF || !prefs.isSignedIn) return
        scope.launch {
            if (Tailscale.ensure(this@PlaybackService, prefs) != Tailscale.Outcome.CONNECTED) return@launch
            session?.let { s -> (listOf(Library.ROOT) + ROOT_TABS).forEach { s.notifyChildrenChanged(it, Int.MAX_VALUE, null) } }
            onConnected()
        }
    }

    /** Runs a search, reusing the last result for the same query (search, then fetch results, is one request). */
    private fun runSearch(query: String, fresh: Boolean = false): List<BrowseEntry> {
        if (!fresh) synchronized(this) { searchResults?.takeIf { it.first == query }?.let { return it.second } }
        val results = try {
            library.search(query)
        } catch (e: Exception) {
            JellyfinApi.warn("search failed", e)
            emptyList()
        }
        synchronized(this) { searchResults = query to results }
        return results
    }


    /** This app, Android Auto, Assistant or the system; see [ClientAccess]. Optionally logged for Settings. */
    private fun isTrusted(controller: MediaSession.ControllerInfo, record: Boolean): Boolean {
        if (isOwnApp(controller)) return true
        // Decide by the caller's UID, which the system vouches for, not by the package name it reports.
        val decision = if (controller.uid > 0) {
            ClientAccess.check(this, controller.uid)
        } else {
            ClientAccess.Decision(false, controller.packageName, "caller couldn't be identified")
        }
        if (record) {
            prefs.recordClient(decision)
            if (!decision.allowed) Log.w(JellyfinApi.TAG, "Rejected media browser client ${decision.packageName}")
        }
        return decision.allowed
    }

    private companion object {
        val SHUFFLE = SessionCommand("app.tentacle.music.SHUFFLE", Bundle.EMPTY)
        val REPEAT = SessionCommand("app.tentacle.music.REPEAT", Bundle.EMPTY)

        const val DEFAULT_ROOT_LIMIT = 4
        const val SONGS = "songs"
        val ROOT_TABS = listOf("home", SONGS, "albums", "artists", "playlists")

        /**
         * How long a library or play request waits for a Tailscale connection already under way. Short
         * enough to leave room for the request itself within Android Auto's 10-second limit (DR-3); if
         * Tailscale comes up later, the lists reload by themselves.
         */
        const val TAILSCALE_WAIT_MS = 7_000L

        val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        /** Upper bound on items one request may add, so a caller can't trigger an unbounded fetch. */
        const val MAX_QUEUE_ITEMS = 500
        const val ROOT_CHILDREN_LIMIT = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"

        val FORMAT_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }
}
