package com.extrumz.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sort
import com.extrumz.app.ui.components.ExtrumzDropdownMenu
import com.extrumz.app.ui.components.ExtrumzDropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.extrumz.app.data.LocalMediaRepository
import com.extrumz.app.data.NerdStats
import com.extrumz.app.data.TrackLog
import com.extrumz.app.data.telegram.TelegramAuthController
import com.extrumz.app.data.telegram.TelegramLoginLink
import com.extrumz.app.data.telegram.TelegramUi
import com.extrumz.app.data.model.BrowseType
import com.extrumz.app.data.model.HomeShelf
import com.extrumz.app.data.model.LikeStatus
import com.extrumz.app.data.model.PlaybackSourceType
import com.extrumz.app.data.model.SearchFilter
import com.extrumz.app.data.model.SearchResult
import com.extrumz.app.data.model.ShelfItem
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.model.UiState
import com.extrumz.app.data.model.EntityType
import com.extrumz.app.data.model.SearchHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.extrumz.app.data.model.durationMillis
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.settings.LibrarySort
import com.extrumz.app.data.settings.ThemeMode
import com.extrumz.app.ui.screens.ChannelsScreen
import com.extrumz.app.ui.screens.EqualizerScreen
import com.extrumz.app.ui.screens.HistoryScreen
import com.extrumz.app.ui.screens.ProxyScreen
import com.extrumz.app.ui.screens.SettingsScreen
import com.extrumz.app.ui.screens.SongsScreen
import com.extrumz.app.playback.AudioCache
import com.extrumz.app.playback.PlayerDeepLink
import com.extrumz.app.playback.QueueBuilder
import com.extrumz.app.playback.QueueCoordinator
import com.extrumz.app.playback.QueueCoordinator.asQueueEntry
import com.extrumz.app.playback.QueueShuffle
import com.extrumz.app.playback.QueueSource
import com.extrumz.app.data.model.QueueTier
import com.extrumz.app.playback.autoplayEnabledFor
import com.extrumz.app.playback.autoplaySectionStart
import com.extrumz.app.playback.beginRadioQueue
import com.extrumz.app.playback.commitRadioQueue
import com.extrumz.app.playback.fromAutoplay
import com.extrumz.app.playback.loadAutoplayTracks
import com.extrumz.app.playback.playSongs
import com.extrumz.app.playback.toMediaItem
import com.extrumz.app.playback.toSong
import com.extrumz.app.playback.toggleAutoplay
import com.extrumz.app.playback.toggleShuffle
import com.extrumz.app.playback.upgradeQuality
import com.extrumz.app.download.DownloadSession
import com.extrumz.app.download.DownloadStore
import com.extrumz.app.download.MediaTagger
import com.extrumz.app.download.DownloadTarget
import com.extrumz.app.download.Downloads
import com.extrumz.app.ui.components.BrowseActionsSheet
import com.extrumz.app.ui.components.BrowseTarget
import com.extrumz.app.ui.components.DownloadManagerSheet
import com.extrumz.app.ui.components.SongActionsSheet
import androidx.media3.session.MediaController
import com.extrumz.app.playback.rememberMediaController
import com.extrumz.app.playback.rememberPlayerState
import com.extrumz.app.ui.MainViewModel
import com.extrumz.app.ui.components.BottomFadeScrim
import com.extrumz.app.ui.components.BottomTab
import com.extrumz.app.ui.components.FLOATING_BAR_MAX_WIDTH
import com.extrumz.app.ui.components.FloatingBottomBar
import com.extrumz.app.ui.components.PAGE_GUTTER
import com.extrumz.app.ui.components.GlassNavBar
import com.extrumz.app.ui.components.floatingtabbar.rememberFloatingTabBarScrollConnection
import com.extrumz.app.ui.components.FrostedTopBar
import com.extrumz.app.ui.components.LocalAppBackdrop
import com.extrumz.app.ui.components.LocalLiquidGlassEnabled
import com.extrumz.app.ui.components.backdrop.backdrops.LayerBackdrop
import com.extrumz.app.ui.components.backdrop.backdrops.layerBackdrop
import com.extrumz.app.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.extrumz.app.ui.components.isGlassSupported
import com.extrumz.app.ui.components.MiniPlayer
import com.extrumz.app.ui.components.QueueActionNotice
import com.extrumz.app.ui.components.QueueActionNoticeHost
import com.extrumz.app.ui.components.TopBarAccountButton
import com.extrumz.app.ui.components.TopBarBlur
import com.extrumz.app.ui.components.TopBarDownloadButton
import com.extrumz.app.ui.components.optimizedHazeEffect
import com.extrumz.app.ui.components.topBarContentPadding
import com.extrumz.app.ui.components.TranslationLanguageDialog
import com.extrumz.app.ui.components.AppLanguageDialog
import com.extrumz.app.ui.components.LyricsSourcesDialog
import com.extrumz.app.ui.icons.ExtrumzIcons
import androidx.media3.common.Player
import com.extrumz.app.ui.player.NowPlayingScreen
import com.extrumz.app.ui.screens.DetailScreen
import com.extrumz.app.ui.screens.LocalMusicScreen
import com.extrumz.app.ui.screens.LibraryGridPage
import com.extrumz.app.ui.screens.LibraryScreen
import com.extrumz.app.ui.screens.SearchScreen
import com.extrumz.app.ui.screens.WelcomeScreen
import com.extrumz.app.ui.screens.PhoneLoginScreen
import com.extrumz.app.ui.screens.QrLoginScreen
import com.extrumz.app.ui.screens.ProfileScreen
import com.extrumz.app.ui.components.MainFloatingActions
import com.extrumz.app.ui.components.isMainContentTab
import com.extrumz.app.ui.components.shouldShowJumpToTop
import com.extrumz.app.data.telegram.ProxyStore
import com.extrumz.app.data.telegram.shouldShowWelcome
import com.extrumz.app.data.settings.SongSort
import com.extrumz.app.ui.util.ProvideLocaleDirection
import com.extrumz.app.ui.util.isRtlReading
import com.extrumz.app.ui.replay.ReplayScreen
import com.extrumz.app.ui.replay.cards
import com.extrumz.app.ui.replay.ReplayShareSheet
import com.extrumz.app.ui.replay.ReplayStories
import com.extrumz.app.ui.replay.ReplayStoryPage
import com.extrumz.app.ui.replay.rememberReplayState
import com.extrumz.app.ui.theme.ExtrumzTheme
import com.extrumz.app.ui.theme.rememberArtworkPalette
import com.extrumz.app.ui.theme.SystemBarIcons
import com.extrumz.app.ui.utils.guardSheetFromContentTouches
import com.extrumz.app.ui.utils.rememberIosOverscrollFactory
import com.extrumz.app.ui.performance.resolvePerformanceRefreshRate
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
import com.extrumz.app.ui.screens.PlaylistsScreen
import com.extrumz.app.ui.screens.PlaylistDetailScreen
import androidx.compose.runtime.produceState
import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.ui.components.AddToPlaylistSheet
import com.extrumz.app.data.model.toSong
import com.extrumz.app.ui.screens.LikedScreen
import com.extrumz.app.data.model.telegramCoordsOf
import com.extrumz.app.data.db.entity.LikedVideo
import com.extrumz.app.ui.components.ExtrumzSplash
import com.extrumz.app.data.telegram.Telegram
import com.extrumz.app.data.telegram.TelegramAuthState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow

/** A full first screen of a native YouTube Music radio before AutoPlay tops it up. */
private const val INITIAL_RADIO_TRACKS = 24

internal fun shouldSkipAfterDislike(
    previousStatus: LikeStatus,
    targetVideoId: String,
    currentVideoId: String?,
): Boolean = previousStatus != LikeStatus.DISLIKE && targetVideoId == currentVideoId

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Before the composition, so a cold launch from a widget's artwork has
        // the request already standing by the time ExtrumzApp first reads it.
        PlayerDeepLink.consume(intent)
        // A `tg://login?token=` link the QR confirm opened here.
        TelegramLoginLink.consume(intent)
        setContent {
            val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
            val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
            val liquidGlassEnabled by AppSettings.liquidGlass.collectAsStateWithLifecycle()
            val iosOverscrollFactory = rememberIosOverscrollFactory()
            val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
            val composeView = LocalView.current
            LaunchedEffect(highPerformance, performanceRefreshRate, composeView) {
                applyPerformanceMode(highPerformance, performanceRefreshRate, composeView)
            }
            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            ExtrumzTheme(darkTheme = darkTheme) {
                // The glass surfaces sample this layer, and a layer records only
                // what is drawn into it — which, for Extrumz, is a page that
                // paints no background of its own. Everywhere a page is not
                // showing artwork the recording is transparent, so the glass had
                // nothing to blur there and you saw straight through it to the
                // sharp page underneath: album art came through the bar blurred
                // and text came through it untouched. The window's background is
                // the floor the pages have always been drawn against, so it is
                // laid down here too and the recording is opaque like the screen.
                val windowBackground = MaterialTheme.colorScheme.background
                val paintBackdrop: ContentDrawScope.() -> Unit = remember(windowBackground) {
                    {
                        drawRect(windowBackground)
                        drawContent()
                    }
                }
                val appBackdrop = rememberLayerBackdrop(onDraw = paintBackdrop)
                CompositionLocalProvider(
                    LocalOverscrollFactory provides iosOverscrollFactory,
                    LocalLiquidGlassEnabled provides liquidGlassEnabled,
                    LocalAppBackdrop provides appBackdrop,
                ) {
                // The window's width, measured rather than asked for.
                //
                // `Configuration.screenWidthDp` is the wrong question here: in a
                // freeform or desktop window it can report the display rather
                // than the window it is actually in, and it lands a beat late
                // when that window is dragged. The layout downstream splits in
                // two on the strength of this number and sizes both halves from
                // it, so a stale one is a player pane sized for a window that no
                // longer exists and a page squeezed to a sliver to pay for it.
                // A measured constraint cannot be stale — it is the very width
                // the split is about to be laid out in.
                Box(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        ExtrumzApp(
                            darkTheme = darkTheme,
                            windowWidth = maxWidth,
                            windowHeight = maxHeight,
                            appBackdrop = appBackdrop,
                        )
                    }

                    // The splash covers the cold start, mounted above the whole app
                    // so it draws on top of every page and sheet on the first frame.
                    var coldStartDone by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(COLD_START_COVER_MS)
                        coldStartDone = true
                    }
                    val telegramState by Telegram.client.authState.collectAsStateWithLifecycle()
                    val isAuthInitializing = telegramState is TelegramAuthState.Uninitialized ||
                        telegramState is TelegramAuthState.Initializing
                    ExtrumzSplash(visible = !coldStartDone || isAuthInitializing)
                }
                }
            }
        }
    }

    /**
     * Requests a window refresh rate without forcing a display mode or
     * resolution. Android may still lower it for temperature, battery state or
     * hardware limits, which is why Settings describes this as a preference.
     */
    private fun applyPerformanceMode(enabled: Boolean, refreshRate: Int, composeView: View) {
        val supportedRefreshRate = composeView.display.resolvePerformanceRefreshRate(refreshRate)
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (enabled) supportedRefreshRate.toFloat() else 0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setFrameRatePowerSavingsBalanced(!enabled)
            composeView.requestedFrameRate = if (enabled) {
                supportedRefreshRate.toFloat()
            } else {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }
    }

    /**
     * The other half of the relay. This activity is `singleTask`, so once it is
     * running a second tap on the widget does not rebuild anything — it arrives
     * here, and [onCreate] never runs again.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replaces what getIntent() returns, so the extra this consumes is the
        // one that just arrived and not the one the task was started with.
        setIntent(intent)
        PlayerDeepLink.consume(intent)
        TelegramLoginLink.consume(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtrumzApp(
    darkTheme: Boolean,
    /** The width of the window this is laid out in — see the call site. */
    windowWidth: Dp,
    /**
     * The window's height, measured the same way and for the same reason as
     * [windowWidth] — and needed alongside it for exactly one thing: telling
     * a portrait window apart from a landscape one. Width alone can't; a
     * big tablet's portrait width comfortably clears a phone's landscape
     * width, so the two-column player (see [landscapePlayerAvailable])
     * would fire in portrait too if it only ever asked about width.
     */
    windowHeight: Dp,
    appBackdrop: LayerBackdrop,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val hazeState = remember { HazeState() }
    // Recording the backdrop layer costs a draw pass, so it only runs when a
    // liquid-glass surface (the nav bar or artwork-page back button) can sample it.
    val glassActive = LocalLiquidGlassEnabled.current && isGlassSupported()
    // "Reduce dynamic blur" keeps the glass bar's *shape* — the folding
    // now-playing-and-tabs component is a layout, not an effect, and dropping
    // back to the two stacked bars would be answering a question about material
    // with a different screen. What it drops is the sampling: the surfaces fill
    // solid (see [Modifier.liquidGlass]) and the whole-page layer recording
    // below goes with them, which is the part that costs a draw pass.
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val glassSamplesBackdrop = glassActive && !reduceDynamicBlur
    // What folds [GlassNavBar] between its expanded and inline shapes. Held here
    // rather than inside the bar because the page's scroll is what drives it,
    // and the page is a sibling of the bar rather than a child.
    val navBarScroll = rememberFloatingTabBarScrollConnection()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    /**
     * Whether the player's sheet is up. The player is always a full-screen
     * take-over raised over the page, on every window size — the library
     * stays full-screen behind a mini player rather than losing a lane to a
     * permanent pane.
     */
    var showNowPlaying by remember { mutableStateOf(false) }
    // The far end of the relay from a widget's artwork. Cleared here rather than
    // where it was set, so the request is spent by being served — see
    // [PlayerDeepLink.handled]. The sheet itself is gated on there being a track,
    // so on a cold launch this simply arms it and it opens as the controller
    // connects.
    val openPlayerRequested by PlayerDeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            showNowPlaying = true
            PlayerDeepLink.handled()
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Replay: the page, the stories over it, and the share sheet over those.
    // Three states rather than one enum because they stack — the stories are
    // opened from the page and the share sheet from either, and closing one
    // has to reveal what it was opened from.
    var showReplay by remember { mutableStateOf(false) }
    // Library cards use the same Replay page as every other entry point, but
    // category cards ask it to start at their matching ranked section.
    var replayLandingPage by remember { mutableStateOf(ReplayStoryPage.INTRO) }
    var replayStory by remember { mutableStateOf<ReplayStoryPage?>(null) }
    var showReplayShare by remember { mutableStateOf(false) }
    /** Which story card the share sheet is for, or null for the whole Replay. */
    var replaySharePage by remember { mutableStateOf<ReplayStoryPage?>(null) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showProxyScreen by remember { mutableStateOf(false) }
    var showPhoneLoginScreen by remember { mutableStateOf(false) }
    var showQrLoginScreen by remember { mutableStateOf(false) }
    var showProfileScreen by remember { mutableStateOf(false) }

    var showHistory by remember { mutableStateOf(false) }
    // The full-screen channel picker (see [ChannelsScreen]), reached from the
    // Library shelf and — with the other landing — from Search's pill.
    var showChannels by rememberSaveable { mutableStateOf(false) }
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var showLiked by rememberSaveable { mutableStateOf(false) }
    val playlistListState = remember { LazyListState() }
    val likedListState = remember { LazyListState() }
    // Which playlist's own page is open, if any. Null is the list.
    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    // The bar's title, read from the same row the page reads. Observation
    // rather than a one-shot so a rename shows up in the bar as well.
    val likedLabel = stringResource(R.string.liked)
    val openPlaylistTitle by produceState<String?>(null, openPlaylistId) {
        value = openPlaylistId?.let { TelegramDb.playlistDao.getById(it)?.name }
    }
    val playlistDetailListState = remember { LazyListState() }
    // Where closing that picker lands. The Library shelf hands the listener to
    // the filtered Songs tab; the Search pill keeps them where they were so
    // the results re-filter in place.
    var channelsReturnToSongs by rememberSaveable { mutableStateOf(true) }
    // A Library shelf's "Show all" — the shelf it was opened from, so its own
    // cards can be laid out again as a full-screen grid. See [LibraryGridPage].
    var libraryShowAll by remember { mutableStateOf<HomeShelf?>(null) }
    var detailActiveShelf by remember { mutableStateOf<HomeShelf?>(null) }
    var librarySortMenuOpen by remember { mutableStateOf(false) }
    var showLyricsSources by remember { mutableStateOf(false) }
    var showAppLanguage by remember { mutableStateOf(false) }
    var showTranslationLanguage by remember { mutableStateOf(false) }
    /**
     * Whether the download manager is open.
     *
     * Not [rememberSaveable]: the list behind it does not survive the process
     * either (see [com.extrumz.app.download.DownloadSession]), and a sheet
     * restored over an empty one would be a manager with nothing to manage.
     */
    var showDownloadManager by remember { mutableStateOf(false) }
    var songActions by remember { mutableStateOf<Song?>(null) }
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var showLyricsOffset by remember { mutableStateOf(false) }
    /**
     * Whether the track menu that is up was opened from the player.
     *
     * Its copy of the menu carries rows nothing else offers — a sleep timer,
     * the track log, share — and until now "opened from the player" and "the
     * player is on screen" were the same sentence, because the player was a
     * sheet and nothing else could be up behind it. On a tablet the player is
     * never *the* thing on screen: it is always beside whatever is, so the
     * question has to be answered by whoever opened the menu.
     */
    var menuFromPlayer by remember { mutableStateOf(false) }
    /** Holding a row anywhere but the player — the menu without the player's rows. */
    val openSongMenu: (Song) -> Unit = { song ->
        menuFromPlayer = false
        songActions = song
    }
    // Which album or playlist the collection menu is open on, or null when it
    // is shut. One slot for every surface that can open it — the shelves on
    // three tabs, the search rows, the artist page's carousels, the release
    // page's own overflow — because only one of them can be held at a time.
    var browseActions by remember { mutableStateOf<BrowseTarget?>(null) }
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    // The same answer the playback service acts on, so the label and the
    // behaviour behind it can never disagree.
    val autoplayEnabled = autoplayEnabledFor(autoplay)
    // Set each time the search tab is tapped, which SearchScreen uses as a
    // signal to focus the input field.
    var searchFocusRequested by remember { mutableStateOf(false) }
    // Invalidates an in-flight radio lookup when a later play request wins.
    var playRequestGeneration by remember { mutableIntStateOf(0) }
    // Starting radio from the item already playing must not replace that media
    // item just to add UI metadata. This temporary label covers that seed; all
    // following radio items carry radioName in their MediaItem extras.
    var activeRadioSeed by remember { mutableStateOf<Pair<String, String>?>(null) }

    // The modal player owns light status glyphs and its own contrast scrim.
    // Every other surface follows the theme; Replay's page and stories remain
    // dark artwork either way.
    SystemBarIcons(dark = !darkTheme && !showNowPlaying && !showReplay && replayStory == null)

    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val historyState by viewModel.history.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsSource by viewModel.lyricsSource.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val lyricsProviderStates by viewModel.lyricsProviderStates.collectAsStateWithLifecycle()
    val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val searchLoadingMore by viewModel.searchLoadingMore.collectAsStateWithLifecycle()
    val searchScrollReset by viewModel.searchScrollReset.collectAsStateWithLifecycle()
    val detailStack by viewModel.detailStack.collectAsStateWithLifecycle()
    val detail = detailStack.lastOrNull()
    // Local Music has no artwork to wash the top inset in, so it renders with
    // the ordinary bounded status bar rather than the artwork gradient used by
    // album/artist/playlist pages. Downloads is the same page, and the tab row
    // it now carries sits directly under the bar, so it needs that same plain
    // treatment rather than a release-style colour wash over its tabs.
    //
    // A downloaded playlist's page is under `local:` too and is none of that: it
    // has a cover and a track list, so it takes the bar every other release page
    // takes. Hence the folder question rather than the prefix.
    val isLocalDetail = detail?.browseId.isDeviceFolder()
    // Not keyed on the browse id and not remembered here: each page's choice
    // lives in AppSettings keyed by that page — Spotify-style, one playlist's
    // order never imposes itself on another, and every page keeps its own
    // across visits.
    val detailSongSorts by AppSettings.detailSongSorts.collectAsStateWithLifecycle()
    val songSort = detail?.browseId?.let { detailSongSorts[it] } ?: SongSort.DEFAULT
    var songSortMenuOpen by remember { mutableStateOf(false) }
    val likeStatuses by viewModel.likeStatuses.collectAsStateWithLifecycle()
    val qualityUpgradesInFlight by NerdStats.racingLossless.collectAsStateWithLifecycle()

    // Settings has no tab of its own — it sits on top of whatever tab was
    // selected. A pushed album/artist page (from the player, search, etc.)
    // should surface above it rather than being hidden behind it.
    LaunchedEffect(detail) { if (detail != null) showSettings = false }
    LaunchedEffect(detail?.browseId) { detailActiveShelf = null }

    // The Downloads page is a snapshot of the folder, taken when it was opened.
    // Saving a track or deleting one while it is on screen changes what belongs
    // on it — and now that the page groups by artist and album, a stale list is
    // stale counts and a missing row in three places rather than one. So it is
    // taken again whenever the record of what's on disk changes.
    val savedDownloads by Downloads.saved.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val librarySort by AppSettings.librarySort.collectAsStateWithLifecycle()
    // The releases those files were asked for as — read here rather than in the
    // page so the Downloads folder recomposes when one is added, the same way it
    // does when a file is.
    val savedCollections by Downloads.collections.collectAsStateWithLifecycle()
    // The playlists among them, for the Library page's On Device shelf. Read off
    // both records: the collection record is what says a playlist was downloaded
    // as a playlist, and what is on disk is what says it still has anything left
    // to open.
    val downloadedPlaylists = remember(savedCollections, savedDownloads) {
        Downloads.savedPlaylists()
    }
    // What a browse id is recorded under in Downloads.collections, when it names
    // a release downloaded whole — see BrowseTarget.downloadId. A downloaded
    // playlist's own page and its card both carry the id under the
    // `local:playlist:` prefix; a release still reachable by its real id (an
    // album's own page, a search hit) is looked up directly under that instead.
    val downloadIdFor: (String?) -> String? = { id ->
        id?.let { Downloads.recordIdOf(it) ?: it }?.takeIf { it in savedCollections }
    }
    LaunchedEffect(savedDownloads, savedCollections, detail?.browseId) {
        val openPage = detail ?: return@LaunchedEffect
        val open = openPage.browseId
        // A downloaded playlist's page is a snapshot of the same folder and goes
        // stale for the same reasons — and it is the one page a delete can empty
        // out entirely, which is worth saying rather than leaving rows behind
        // that play nothing.
        // openDetail is already taking the initial snapshot while the page is
        // Loading. Starting reloadLocalDetail at the same time used to perform
        // the same disk work twice on every open, which was especially visible
        // for large download libraries and slow content providers.
        if (openPage.songs !is UiState.Loading &&
            (open == "local:downloads" || Downloads.recordIdOf(open) != null)
        ) {
            viewModel.reloadLocalDetail(open)
        }
    }
    LaunchedEffect(localMusicFolderUri, filterNonMusicAudio) {
        if (detail?.browseId == "local:all") {
            viewModel.reloadLocalDetail("local:all")
        }
    }
    val controller = rememberMediaController()
    val player = rememberPlayerState(controller)
    val playPauseBusy = player.isLoading
    var queueNotice by remember { mutableStateOf<QueueActionNotice?>(null) }
    var queueNoticeId by remember { mutableIntStateOf(0) }
    val showQueueNotice: (String) -> Unit = { message ->
        queueNoticeId += 1
        queueNotice = QueueActionNotice(queueNoticeId, message)
    }
    LaunchedEffect(queueNotice?.id) {
        val shown = queueNotice ?: return@LaunchedEffect
        delay(3_000)
        if (queueNotice?.id == shown.id) queueNotice = null
    }
    /** The one play/pause every surface presses. */
    val togglePlayPause: () -> Unit = {
        controller?.let { c ->
            if (c.isPlaying) {
                c.pause()
            } else {
                c.play()
            }
        }
    }
    val shuffleEnabled by QueueShuffle.enabled.collectAsStateWithLifecycle()
    // Lyrics follow whatever is playing; duration lands a beat after the track.
    // Keyed on the lyric settings too, so turning a source on or off applies to
    // the track already playing rather than only the next one.
    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    LaunchedEffect(player.song?.videoId, player.durationMs, syncedLyricsEnabled, lyricsSources) {
        player.song?.let {
            viewModel.loadLyrics(
                it.videoId,
                it.title,
                it.artist,
                // The player's own length, and the catalogue's where it has
                // none yet. Paused, ExoPlayer never finishes preparing the
                // track it was skipped to, so it reports no duration at all —
                // and a lookup that waits for one waits for ever, which left
                // the lyrics of a paused track loading until it was played.
                player.durationMs.takeIf { ms -> ms > 0L } ?: it.durationMillis(),
                it.albumName,
                it.localUri,
            )
        }
    }

    val songsListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    val libraryShowAllGridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()
    val idleListState = rememberLazyListState()
    val currentListState = when (selectedTab) {
        TAB_LIBRARY -> libraryListState
        TAB_SEARCH -> searchListState
        else -> idleListState
    }

    val scrolled by remember(currentListState) {
        derivedStateOf {
            currentListState.firstVisibleItemIndex > 0 ||
                currentListState.firstVisibleItemScrollOffset > 24
        }
    }

    // A pushed album/artist/playlist page has a large header of its own — the
    // sleeve, or an artist's photo running edge to edge — which owns the title
    // until it is scrolled away, exactly as a tab's big heading does. The state
    // is hoisted because the bar lives beside that page rather than inside it,
    // and is rebuilt per page: pushing a second one must not inherit the
    // first's scroll offset.
    // As [detailListState], for Replay: its own large heading owns the title
    // until it is scrolled away, and the bar lives out here rather than on the
    // page. Rebuilt per opening so reopening starts at the top.
    val replayListState = remember(showReplay, replayLandingPage) { LazyListState() }
    val replayScrolled by remember(replayListState) {
        derivedStateOf {
            replayListState.firstVisibleItemIndex > 0 ||
                replayListState.firstVisibleItemScrollOffset > 24
        }
    }

    val detailListState = remember(detail?.browseId) { LazyListState() }
    val detailTitleDrop = with(LocalDensity.current) { DETAIL_TITLE_DROP.toPx() }
    val detailScrolled by remember(detailListState, detailTitleDrop) {
        derivedStateOf {
            detailListState.firstVisibleItemIndex > 0 ||
                detailListState.firstVisibleItemScrollOffset > detailTitleDrop
        }
    }

    // Held, not rebuilt. `listOf` hands back a new instance on every pass, and a
    // List is not a type the compiler can call stable, so under strong skipping
    // the bar this is handed to compares it by identity, never matches, and so
    // can never skip. This composable re-runs on every frame of a scroll — it
    // reads [scrolled] — which made the whole floating bar, both of its states
    // and every glass surface on them recompose once per frame for the length of
    // a fold. Keyed on the labels so a locale change still rebuilds it.
    val searchLabel = stringResource(R.string.search)
    val historyLabel = stringResource(R.string.history)
    val replayLabel = stringResource(R.string.replay)
    val queueLabel = stringResource(R.string.queue)
    val songsLabel = stringResource(R.string.tab_songs)
    val searchTabLabel = stringResource(R.string.tab_search)
    val libraryTabLabel = stringResource(R.string.tab_library)
    val tabs = remember(songsLabel, searchTabLabel, libraryTabLabel) {
        listOf(
            BottomTab(songsLabel, ExtrumzIcons.MusicNote),
            BottomTab(searchTabLabel, ExtrumzIcons.Search),
            BottomTab(libraryTabLabel, ExtrumzIcons.Library),
        )
    }

    val scope = rememberCoroutineScope()

    val playFrom: (List<Song>, Int, QueueSource) -> Unit = { songs, index, source ->
        playRequestGeneration++
        activeRadioSeed = null
        scope.launch {
            val c = controller ?: return@launch
            val currentTimeline = player.queue.takeIf { it.size == c.mediaItemCount }
                ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }
            val currentIndex = c.currentMediaItemIndex

            val result = QueueCoordinator.buildContextQueue(
                currentTimeline = currentTimeline,
                currentIndex = currentIndex,
                newContextSongs = songs,
                selectedIndex = index,
                contextSource = source,
            )
            c.playSongs(result.timeline, result.startIndex)
            // Start playback in the mini-player; the user opens the full view by tapping it.
        }
    }
    // Kept for entry points whose rows already carry their origin (notably a
    // collection action fetched before this callback). The explicit wrappers
    // below are preferred because a track's album is not necessarily where it
    // was played from.
    val play: (List<Song>, Int) -> Unit = { songs, index ->
        val first = songs.getOrNull(index)
        val source = QueueSource(
            title = first?.playbackSource ?: first?.albumName ?: queueLabel,
            type = first?.playbackSourceType ?: PlaybackSourceType.QUEUE,
            id = first?.playbackSourceId,
        )
        playFrom(songs, index, source)
    }
    LaunchedEffect(player.song?.videoId) {
        if (activeRadioSeed?.first != player.song?.videoId) activeRadioSeed = null
    }

    /**
     * A song picked on its own — off a home card or a search hit — starts a
     * station rather than queueing the list it was shown in. Searching
     * "Perfect" and tapping the top hit otherwise queues twenty covers and
     * remixes of the same song. Album, artist and playlist pages keep [play],
     * where the surrounding list *is* the thing the user asked for.
     */
    val playRadio: (Song, QueueSource) -> Unit = { song, source ->
        playRequestGeneration++
        activeRadioSeed = null
        scope.launch {
            val c = controller ?: return@launch
            val currentTimeline = player.queue.takeIf { it.size == c.mediaItemCount }
                ?: (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toSong() }
            val currentIndex = c.currentMediaItemIndex
            val oneOffQueue = QueueCoordinator.buildOneOffQueue(
                currentTimeline = currentTimeline,
                currentIndex = currentIndex,
                tappedSong = song,
                source = source,
            )
            c.playSongs(oneOffQueue, 0)
            // Start radio in the mini-player; the user opens the full view by tapping it.
        }
    }

    /**
     * Starts the explicit station offered by every song overflow menu.
     *
     * The related tracks come from YouTube Music's own RDAMVM watch queue.
     * Loading happens before the player is touched so a failed request cannot
     * destroy the queue already playing. Once ready, the whole old queue is
     * replaced in one Media3 operation.
     */
    val startRadio: (Song) -> Unit = { song ->
        val originalController = controller
        if (originalController != null) {
            val request = ++playRequestGeneration
            // Ignore AutoPlay's tail: it may legitimately grow while the
            // request is in flight and does not mean the listener chose a
            // different queue. A new album/song queue does.
            val originalManualQueue = (0 until originalController.mediaItemCount)
                .map { originalController.getMediaItemAt(it) }
                .filterNot { it.fromAutoplay }
                .map { it.mediaId }
            scope.launch {
                val seed = song.copy(
                    radioName = song.title,
                    playbackSource = song.title,
                    playbackSourceType = PlaybackSourceType.SHARED_LINK,
                    playbackSourceId = song.videoId,
                )
                val related = loadAutoplayTracks(
                    existing = listOf(seed),
                    seedSong = seed,
                    limit = INITIAL_RADIO_TRACKS,
                ).getOrElse {
                    if (request == playRequestGeneration) {
                        Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (related.isEmpty()) {
                    if (request == playRequestGeneration) {
                        Toast.makeText(context, R.string.couldnt_load_tracks, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val activeController = controller
                val activeManualQueue = (0 until activeController.mediaItemCount)
                    .map { activeController.getMediaItemAt(it) }
                    .filterNot { it.fromAutoplay }
                    .map { it.mediaId }
                if (request != playRequestGeneration || activeManualQueue != originalManualQueue) {
                    return@launch
                }
                // Cancel any armed crossfade/AutoPlay work and erase the old
                // cold-start snapshot before the visible queue is replaced.
                activeController.beginRadioQueue()
                val currentIndex = activeController.currentMediaItemIndex
                val currentItem = activeController.currentMediaItem
                if (currentIndex >= 0 && currentItem?.mediaId == song.videoId) {
                    // Keep the current MediaItem itself untouched. Replacing it,
                    // even with the same song, reparses the source at position
                    // zero and audibly stops/restarts the track.
                    if (currentIndex + 1 < activeController.mediaItemCount) {
                        activeController.removeMediaItems(currentIndex + 1, activeController.mediaItemCount)
                    }
                    if (currentIndex > 0) activeController.removeMediaItems(0, currentIndex)
                    activeController.addMediaItems(1, related.map { it.toMediaItem() })
                    activeRadioSeed = song.videoId to song.title
                } else {
                    activeRadioSeed = null
                    activeController.playSongs(listOf(seed) + related, 0)
                }
                // Make this station — never the queue from before it — what a
                // fresh process restores, even if it is killed immediately.
                activeController.commitRadioQueue()
                Toast.makeText(
                    context,
                    context.getString(R.string.radio_started, song.title),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val addToQueue: (Song) -> Unit = { song ->
        scope.launch {
            // The end of what the user queued, not the end of the queue: a song
            // asked for by name outranks whatever AutoPlay lined up behind it.
            controller?.let {
                val current = it.currentMediaItem?.toSong()
                val timeline = player.queue.takeIf { q -> q.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { idx -> it.getMediaItemAt(idx).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = false,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_added_to_queue))
            }
        }
    }
    val playNext: (Song) -> Unit = { song ->
        scope.launch {
            controller?.let {
                val current = it.currentMediaItem?.toSong()
                val timeline = player.queue.takeIf { q -> q.size == it.mediaItemCount }
                    ?: (0 until it.mediaItemCount).map { idx -> it.getMediaItemAt(idx).toSong() }
                val at = QueueCoordinator.findUserQueueInsertionIndex(
                    timeline = timeline,
                    currentIndex = it.currentMediaItemIndex,
                    isNext = true,
                )
                val queued = song.copy(
                    radioName = current?.radioName,
                    playbackSource = current?.playbackSource ?: queueLabel,
                    playbackSourceType = current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
                    playbackSourceId = current?.playbackSourceId,
                ).asQueueEntry(QueueTier.USER_QUEUE)
                it.addMediaItem(at, queued.toMediaItem())
                showQueueNotice(context.getString(R.string.song_will_play_next))
            }
        }
    }
    val onSongSwipe: (Song) -> Unit = { song ->
        if (AppSettings.swipeToPlayNext.value) playNext(song) else addToQueue(song)
    }

    /**
     * A whole album, playlist or library list onto the queue in one go.
     *
     * [next] picks which of the two positions the single-track menu already
     * offers it lands in — straight after the current track, or behind
     * everything else the user queued but still ahead of AutoPlay (see
     * [addToQueue]). The list keeps its own running order either way: this
     * *adds* a release, it doesn't start one, so [QueueShuffle] has no say here.
     *
     * There is usually nothing on screen to show for it — the queue panel is
     * shut, the current track carries on — so the count is said out loud, the
     * same way a batch download is.
     */
    val queueSongs: (List<Song>, Boolean) -> Unit = { songs, next ->
        if (songs.isNotEmpty()) {
            scope.launch {
                val c = controller
                if (c == null || c.mediaItemCount == 0) {
                    // Nothing to queue behind. "Add to queue" on a silent
                    // player can only mean start here — and adding without
                    // preparing would leave the list sitting in a player that
                    // never gets round to it.
                    play(songs, 0)
                } else {
                    val toAdd = songs
                    val timeline = player.queue.takeIf { q -> q.size == c.mediaItemCount }
                        ?: (0 until c.mediaItemCount).map { idx -> c.getMediaItemAt(idx).toSong() }
                    val at = QueueCoordinator.findUserQueueInsertionIndex(
                        timeline = timeline,
                        currentIndex = c.currentMediaItemIndex,
                        isNext = next,
                    )
                    val current = c.currentMediaItem?.toSong()
                    c.addMediaItems(
                        at,
                        toAdd.map {
                            it.copy(
                                radioName = current?.radioName,
                                playbackSource = current?.playbackSource ?: queueLabel,
                                playbackSourceType = current?.playbackSourceType
                                    ?: PlaybackSourceType.QUEUE,
                                playbackSourceId = current?.playbackSourceId,
                            ).asQueueEntry(QueueTier.USER_QUEUE).toMediaItem()
                        },
                    )
                    val message = context.resources.getQuantityString(
                        if (next) R.plurals.songs_will_play_next else R.plurals.songs_added_to_queue,
                        toAdd.size,
                        toAdd.size,
                    )
                    showQueueNotice(message)
                }
            }
        }
    }
    val addSongsToQueue: (List<Song>) -> Unit = { songs -> queueSongs(songs, false) }
    val playSongsNext: (List<Song>) -> Unit = { songs -> queueSongs(songs, true) }

    // ---- Album / playlist menu ----

    /**
     * Holding an album or playlist card, wherever one is drawn.
     *
     * Artists are left out. An artist page is a selection of their work rather
     * than a running order, and "add Radiohead to the queue" has no answer that
     * isn't a guess — so holding an artist card does nothing, as it did before.
     */
    val onBrowseLongPress: (ShelfItem) -> Unit = { item ->
        val id = item.browseId
        val type = id?.let { viewModel.browseTypeOf(it) }
        // Cards the manage screens own — a channel row, a playlist row — get
        // their menus there rather than here. A Library card of either kind
        // would be a second list of the same thing, and the two would drift.
        if (id != null && !id.startsWith("tg:") && type != BrowseType.ARTIST) {
            browseActions = BrowseTarget(
                browseId = id,
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = item.thumbnailUrl,
                type = type ?: BrowseType.OTHER,
                downloadId = downloadIdFor(id),
            )
        }
    }

    /**
     * Hands [action] the target's whole track list.
     *
     * A card has no tracks behind it — its page was never opened — so the
     * listing is fetched first, all of it, and the menu that asked has already
     * closed by the time it lands. A release page's overflow passes the rows it
     * is already showing and this is immediate.
     *
     * Album rows arrive with no album name of their own, the same way they do on
     * the page (see `withAlbum` below), so the title is stamped on here too —
     * otherwise a track queued from an album card reaches the player and the
     * download folder with nothing to file it under.
     */
    val withBrowseSongs: (BrowseTarget, (List<Song>) -> Unit) -> Unit = { target, action ->
        val stamp: (List<Song>) -> Unit = { songs ->
            action(
                songs.map { song ->
                    song.copy(
                        albumName = if (target.type == BrowseType.ALBUM) {
                            song.albumName ?: target.title
                        } else {
                            song.albumName
                        },
                        playbackSource = target.title,
                        playbackSourceType = PlaybackSourceType.BROWSE,
                        playbackSourceId = target.browseId,
                    )
                },
            )
        }
        when {
            target.songs.isNotEmpty() -> stamp(target.songs)
            target.browseId == null ->
                Toast.makeText(context, context.getString(R.string.no_tracks_here), Toast.LENGTH_SHORT).show()
            else -> viewModel.collectSongs(target.browseId, target.thumbnailUrl) { result ->
                result.fold(
                    onSuccess = stamp,
                    onFailure = {
                        val message = it.message ?: context.getString(R.string.couldnt_load_tracks)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    // ---- Downloads ----
    // Two permissions, and never both on one device: writing to the shared
    // Music folder needs storage access below API 29 and none at all from
    // 29 on, where MediaStore grants an app its own rows; notifications are
    // only asked for from API 33. So the branches below are mutually exclusive
    // by SDK level, and nothing here can stack two dialogs on each other.
    var downloadPending by remember { mutableStateOf<List<Song>>(emptyList()) }
    /**
     * What the pending batch was asked for as, held alongside it for the same
     * reason: the storage-permission dialog is a round trip through another
     * process, and the release has to survive it or a whole album granted
     * permission arrives as forty loose tracks.
     */
    var downloadPendingFrom by remember { mutableStateOf<DownloadTarget?>(null) }
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Refusing costs the progress notification, not the download. */ }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val songs = downloadPending
        val from = downloadPendingFrom
        downloadPending = emptyList()
        downloadPendingFrom = null
        when {
            songs.isEmpty() -> Unit
            granted -> {
                songs.forEach { Downloads.enqueue(context, it, from?.title) }
                if (from != null) Downloads.markRequested(from.id, songs.map { it.videoId })
            }
            // The one case where refusing is fatal: below API 29 there is no
            // other way to reach the Music folder.
            else -> Toast
                .makeText(context, context.getString(R.string.storage_required_save), Toast.LENGTH_SHORT)
                .show()
        }
    }
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.reloadLocalDetail("local:all")
        } else {
            Toast.makeText(context, context.getString(R.string.storage_required_read), Toast.LENGTH_SHORT).show()
        }
    }
    // Shared by the Library tab itself and by a shelf's "Show all" page, so a
    // card opens the same way from either.
    val onLibraryItemClick: (ShelfItem) -> Unit = { item ->
        item.browseId?.let { id ->
            when {
                // The full-screen picker, from the manage card (shelf or grid).
                id == "tg:channels" -> {
                    channelsReturnToSongs = true
                    showChannels = true
                }
                // The manage row. A full page of its own rather than a
                // subtree, for the same reason the channel picker is one.
                id == "playlists:manage" -> showPlaylists = true
                id == "liked" -> showLiked = true
                // A channel card picks straight away — same toggle the
                // picker's own rows use — and Songs is where the filter chip
                // shows the choice. `libraryShowAll` goes with it: the grid's
                // key would otherwise outrank the tab's in the switch above.
                id.startsWith("tg:chat:") -> id.removePrefix("tg:chat:").toLongOrNull()
                    ?.let { chatId ->
                        val current = TelegramUi.selectedChatId.value
                        TelegramUi.selectedChatId.value =
                            if (current == chatId) null else chatId
                        selectedTab = TAB_HOME
                        libraryShowAll = null
                    }
                else -> {
                    if (id == "local:all" && !LocalMediaRepository.hasStoragePermission(context)) {
                        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_AUDIO
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        mediaPermissionLauncher.launch(perm)
                    }
                    // Left set rather than cleared: a card opened from a shelf's
                    // "Show all" page stacks a detail page over it exactly as one
                    // opened from the Library tab stacks over that, so back from the
                    // release lands on the grid rather than skipping past it. Every
                    // place that reads `libraryShowAll` alongside `detail` favours
                    // `detail` while both are set — see the AnimatedContent below.
                    viewModel.openDetail(
                        browseId = id,
                        title = item.title,
                        subtitle = item.subtitle,
                        thumbnailUrl = item.thumbnailUrl,
                    )
                }
            }
        }
    }
    // Takes a list so a single tap on an album/playlist header can queue the
    // whole thing — the permission dance only needs to happen once for the
    // batch, not once per track.
    //
    // [from] is what the list *is*, when it is a release rather than a
    // selection: an album or a playlist. It is recorded whole, so the Downloads
    // page can offer the thing that was tapped back rather than the forty rows
    // it decomposed into — see [Downloads.rememberCollection]. Null for a single
    // track, which is not a release however many of them are asked for one at a
    // time.
    val startDownload: (List<Song>, DownloadTarget?) -> Unit = { requested, from ->
        val saved = Downloads.saved.value
        // Already on disk, and already queued or running: neither needs asking
        // again. What's left is what a tap on "Download" actually means.
        //
        // The release's cover is stamped onto any row that hasn't got one, as a
        // last check before the tap becomes a file.
        //
        // An album page bills its artwork once, in the header — its track rows
        // can reach here with no thumbnail at all — and a row that reaches
        // [MediaTagger.artworkFor] with a null url is a
        // track saved with no cover in the file and none in [SavedSongMetadata]
        // either, so nothing downstream can draw one afterwards.
        // `MainViewModel.withArtwork` normally fills those in as a page loads and
        // covers the usual route here; this is the backstop for a list that
        // reached this function some other way, and it is worth having precisely
        // because the failure is silent and permanent — the file is written
        // without a cover, and re-downloading adopts the untagged copy rather
        // than replacing it.
        val songs = requested
            .filter { it.videoId !in saved }
            .map { song ->
                val cover = from?.thumbnailUrl
                if (song.thumbnailUrl.isNullOrBlank() && !cover.isNullOrBlank()) {
                    song.copy(thumbnailUrl = cover)
                } else {
                    song
                }
            }
        // Asked here as well as inside [Downloads.enqueue] — not instead of it.
        // Enqueue is the invariant and has to refuse whoever calls it, including
        // the storage-permission continuation below, which resumes long enough
        // after this check for the connection to have changed under it. This is
        // the one place that knows the tap was for forty tracks and can say so
        // once, rather than leaving forty identical failed rows to be read.
        val blocked = songs.isNotEmpty() && !AppSettings.downloadsAllowedNow
        if (songs.isNotEmpty() && !blocked) {
            val needsStorage = AppSettings.exportDownloads.value && DownloadStore.needsLegacyPermission() &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED

            // Asked for here rather than at launch because here is where it means
            // something: a download is the first thing this app does that the user
            // is expected to walk away from.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            if (needsStorage) {
                downloadPending = songs
                downloadPendingFrom = from
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                songs.forEach { Downloads.enqueue(context, it, from?.title) }
                if (from != null) Downloads.markRequested(from.id, songs.map { it.videoId })
            }
        }
        // Recorded for everything that was asked for, not just what still has to
        // be fetched: a release whose tracks are already on the device is still
        // that release, and the point of the record is to group them. Skipped
        // only when the whole batch was refused, since then there will be
        // nothing on disk for it to group.
        if (from != null && !blocked) {
            Downloads.rememberCollection(from, requested)
            // A collection cover is not part of any audio file. Cache the
            // header image separately so its Downloads card still has artwork
            // with no connection, then atomically replace the remote URL in
            // the persisted collection record.
            if (!from.thumbnailUrl.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) {
                    MediaTagger.cacheArtwork(context.applicationContext, from.thumbnailUrl)
                        ?.let { Downloads.rememberCollectionArtwork(from.id, it) }
                }
            }
        }
        when {
            // The row's own icon reports a queued download, so a single tap
            // normally needs no toast — but a refused one leaves the row exactly
            // as it was, and a button that visibly does nothing is worse than a
            // long message. So this one is said whatever the count.
            blocked -> Toast.makeText(
                context,
                context.getString(R.string.wifi_only_download_refusal),
                Toast.LENGTH_LONG,
            ).show()
            requested.size > 1 -> {
                val message = if (songs.isEmpty()) {
                    context.getString(R.string.already_downloaded)
                } else {
                    context.resources.getQuantityString(
                        R.plurals.downloading_song_count,
                        songs.size,
                        songs.size,
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    /**
     * One track on its own, which is never a release.
     *
     * A track reached through AutoPlay or a radio queue often has no duration
     * string at all — YouTube's watch-queue rows don't always send
     * `lengthText` — while the player itself knows exactly how long the same
     * track runs once it has loaded. Backfilled from there when it's the song
     * on screen, so downloading it isn't handed a duration of zero and
     * silently skipped for lyrics — see [LyricsTag.forTrack].
     */
    val downloadSong: (Song) -> Unit = { song ->
        val withDuration = if (song.durationMillis() <= 0L &&
            player.song?.videoId == song.videoId && player.durationMs > 0L
        ) {
            song.copy(durationText = formatDurationText(player.durationMs))
        } else {
            song
        }
        val coords = com.extrumz.app.data.model.telegramCoordsOf(song.videoId)
        if (coords != null) {
            scope.launch(Dispatchers.IO) {
                val isProtected = runCatching {
                    com.extrumz.app.data.telegram.Telegram.client.isProtectedContent(coords.first, coords.second)
                }.getOrDefault(false)
                if (isProtected) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            R.string.tg_protected_content_refusal,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        startDownload(listOf(withDuration), null)
                    }
                }
            }
        } else {
            startDownload(listOf(withDuration), null)
        }
    }

    // Content padding leaves room for the frosted bar above and the tab bar
    // (plus mini player) below, so nothing is ever trapped under the glass.
    //
    // The top is measured off the bar rather than guessed at: the bar is pinned
    // under the status bar inset, and that inset varies by device and by window,
    // so a fixed number only ever lines up on the one device it was picked on.
    // See [topBarContentPadding].
    val listPadding = PaddingValues(
        top = topBarContentPadding(),
        bottom = if (player.song != null) 210.dp else 140.dp,
    )

    // What colour the page currently under the bars is. The fades either end
    // of the screen are flat colour wherever their blur has least to say, so
    // handing them the theme's background puts a black band on a page that is
    // washed in an artwork's colour instead. Off a detail page this resolves
    // to the theme's background anyway, which is exactly right there.
    val detailPalette = rememberArtworkPalette(detail?.thumbnailUrl)

    // One set of numbers for the cards, the page, the stories and the shared
    // picture, so they cannot disagree. Read while any of them is on screen —
    // which includes the Library tab, since the cards live at the top of it.
    // See [rememberReplayState].
    val replayOpen = showReplay || replayStory != null || showReplayShare ||
        (selectedTab == TAB_LIBRARY && detail == null && !showSettings)
    val (replay, setReplayPeriod) = rememberReplayState(replayOpen)
    val replayCards = remember(replay.summary) {
        replay.summary?.takeUnless { it.isEmpty }?.cards(context).orEmpty()
    }

    // ---- The track in the player ----
    // Whatever started this track knew its title and its artwork, but rarely
    // which album or artist page it belongs to — and the only answer to that
    // was a remote lookup, which is gone. The row is whatever the service is
    // playing, taken as it is.
    val playerShowing = showNowPlaying
    val playerSong = player.song
    // The three-dot menu snapshots the track into songActions when it's opened,
    // so a menu opened on an older row would otherwise stay on it. Keep it in
    // sync while it's showing this track.
    LaunchedEffect(playerSong) {
        if (playerSong != null && songActions?.videoId == playerSong.videoId) {
            songActions = playerSong
        }
    }

    // The player's whole parameter list, kept apart from the sheet that
    // mounts it so the sheet's own setup reads on its own.
    val nowPlaying: @Composable (Song) -> Unit = { song ->
        val effectiveSong = song
        val displayedSong = activeRadioSeed
            ?.takeIf { (videoId, _) -> effectiveSong.radioName == null && videoId == effectiveSong.videoId }
            ?.let { (videoId, name) ->
                effectiveSong.copy(
                    radioName = name,
                    playbackSource = name,
                    playbackSourceType = PlaybackSourceType.SHARED_LINK,
                    playbackSourceId = videoId,
                )
            }
            ?: effectiveSong
        NowPlayingScreen(
            song = displayedSong,
            accountName = account?.name,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            isPlaying = player.isPlaying,
            isLoading = playPauseBusy,
            position = player.position,
            durationMs = player.durationMs,
            // No conversion is on offer from the player any more, so nothing
            // is ever fetching a second cut of the record.
            audioVersionSwitching = false,
            onPlayPause = {
                togglePlayPause()
            },
            onNext = { controller?.seekToNextMediaItem() },
            onPrevious = { controller?.seekToPrevious() },
            onSeekFraction = { fraction ->
                controller?.let { player ->
                    // Read at the moment of the seek, not from the
                    // polled snapshot the screen draws with: a track
                    // change updates the current item before it updates
                    // the duration, so a fraction dropped seconds after
                    // a transition would otherwise be scaled by the
                    // previous song's length.
                    val duration = player.duration
                    if (duration > 0) {
                        player.seekTo(
                            (fraction * duration).toLong()
                                .coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L)),
                        )
                    }
                }
            },
            onSeek = { target ->
                controller?.let { player ->
                    // Clamped here rather than at each caller because
                    // not every caller can clamp. The scrubber's target
                    // is a fraction of the duration and cannot overrun,
                    // but a tapped lyric line seeks to a timestamp from
                    // whichever transcription matched on title, artist
                    // and duration — and a match against a slightly
                    // longer master puts every line late, so a tap near
                    // the end asks for a position past the end of this
                    // stream. Media3 answers that by clamping to the
                    // final millisecond, which ends the track and starts
                    // the next one: tapping the last line of a song
                    // skipped it.
                    val duration = player.duration
                    player.seekTo(
                        if (duration > 0) {
                            target.coerceIn(0L, (duration - SEEK_END_GUARD_MS).coerceAtLeast(0L))
                        } else {
                            target.coerceAtLeast(0L)
                        },
                    )
                }
            },
            queue = player.queue,
            queueIndex = player.queueIndex,
            hasPrevious = player.hasPrevious,
            hasNext = player.hasNext,
            repeatMode = player.repeatMode,
            shuffleEnabled = shuffleEnabled,
            autoplayEnabled = autoplayEnabled,
            signedIn = signedIn,
            likeStatus = likeStatuses[song.videoId] ?: LikeStatus.INDIFFERENT,
            onToggleLike = { viewModel.toggleLike(song) },
            // The service owns both the queue and the Shuffle state. Keeping
            // the toggle on that side prevents the UI from changing the icon
            // before its asynchronous reorder command has actually landed.
            onToggleShuffle = { controller?.toggleShuffle() },
            onCycleRepeat = {
                controller?.let {
                    val next = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    // Persist the new repeat mode so it survives app restarts.
                    AppSettings.setRepeatMode(next)
                    // Nothing else to do here: PlaybackService watches the
                    // repeat mode itself and takes AutoPlay's tracks out of
                    // the queue for the duration of repeat-all — and, unlike
                    // this screen, is still around to put them back when the
                    // loop ends.
                    it.repeatMode = next
                }
            },
            onToggleAutoplay = {
                // PlaybackService owns the setting and queue extension so
                // this path and the notification use exactly one loader.
                controller?.toggleAutoplay()
            },
            onJumpTo = { index ->
                controller?.let { c ->
                    QueueCoordinator.jumpToQueueItem(c, index, player.queue)
                }
            },
            onRemoveFromQueue = { controller?.removeMediaItem(it) },
            onMoveInQueue = { from, to -> controller?.moveMediaItem(from, to) },
            // The enriched copy, not player.song — otherwise the menu
            // hides the album and artist rows even once their browse
            // ids have been resolved.
            onOpenMenu = {
                menuFromPlayer = true
                songActions = song
            },
            onOpenAlbum = { id ->
                showNowPlaying = false
                viewModel.openDetail(
                    id,
                    song.albumName ?: song.title,
                    song.artist,
                    song.thumbnailUrl,
                    BrowseType.ALBUM,
                )
            },
            onOpenArtist = { id ->
                showNowPlaying = false
                // No artwork: this track's cover isn't the artist's
                // picture, and the page fills its own in once loaded.
                viewModel.openDetail(
                    id,
                    song.artist,
                    context.getString(R.string.artist),
                    null,
                    BrowseType.ARTIST,
                )
            },
            onOpenPlaybackSource = openSource@{
                val sourceType = displayedSong.playbackSourceType ?: PlaybackSourceType.QUEUE
                val sourceTitle = displayedSong.playbackSource
                    ?: displayedSong.albumName
                    ?: queueLabel
                val sourceId = displayedSong.playbackSourceId

                // A context link is navigation, not another page stacked over
                // Now Playing. Clear the current route before restoring it.
                showNowPlaying = false
                viewModel.clearDetail()
                showSettings = false
                showEqualizer = false
                showProxyScreen = false
                showPhoneLoginScreen = false
                showQrLoginScreen = false
                showProfileScreen = false
                showReplay = false
                showHistory = false
                showChannels = false
                showPlaylists = false
                showLiked = false
                openPlaylistId = null
                libraryShowAll = null

                when (sourceType) {
                    PlaybackSourceType.BROWSE -> {
                        val id = sourceId ?: return@openSource
                        viewModel.openDetail(id, sourceTitle)
                    }
                    PlaybackSourceType.HOME -> selectedTab = TAB_HOME
                    PlaybackSourceType.SEARCH -> selectedTab = TAB_SEARCH
                    PlaybackSourceType.HISTORY -> showHistory = true
                    PlaybackSourceType.REPLAY -> {
                        replayLandingPage = ReplayStoryPage.INTRO
                        showReplay = true
                    }
                    // Channels stopped being a tab; a track filed under a
                    // channel goes back to the picker it was chosen in.
                    PlaybackSourceType.EXPLORE -> showChannels = true
                    // A radio seed's source is the track it was seeded from.
                    // Opening it used to hand the id to a browser; there is no
                    // page behind it any more, so there is nowhere to go.
                    PlaybackSourceType.SHARED_LINK -> Unit
                    // Handled inside NowPlayingScreen by opening its queue.
                    PlaybackSourceType.QUEUE -> Unit
                }
            },
            lyrics = lyrics,
            lyricsSource = lyricsSource,
            lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = viewModel::selectLyricsProvider,
            lyricsUnavailable = lyricsChecked && lyrics.isNullOrEmpty(),
            lyricsOffsetOpen = showLyricsOffset,
            onDismissLyricsOffset = { showLyricsOffset = false },
            onClearQueue = {
                // Keep what's playing and context/autoplay; drop user-queued tracks.
                controller?.let { c ->
                    QueueCoordinator.clearUserQueue(c)
                }
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val telegramState by Telegram.client.authState.collectAsStateWithLifecycle()
        val isWelcomeVisible = shouldShowWelcome(telegramState)

        // A pushed album/artist/playlist page replaces the tab content but
        // leaves the tab bar and mini player in place.
        // Replay's three layers unwind in the order they were opened. Ahead of
        // every other handler because they are drawn over everything else.
        BackHandler(enabled = showReplayShare) { showReplayShare = false }
        BackHandler(enabled = replayStory != null && !showReplayShare) { replayStory = null }
        BackHandler(
            enabled = showReplay && !showSettings && replayStory == null && !showReplayShare,
        ) {
            showReplay = false
        }
        BackHandler(
            enabled = detail != null && !showSettings &&
                !showEqualizer && !showProxyScreen && !showReplay,
        ) { viewModel.closeDetail() }
        BackHandler(enabled = showEqualizer) {
            showEqualizer = false
        }
        BackHandler(enabled = showProxyScreen) {
            showProxyScreen = false
        }
        BackHandler(enabled = showPhoneLoginScreen) {
            showPhoneLoginScreen = false
            TelegramAuthController.dismissAuthUi()
        }
        BackHandler(enabled = showQrLoginScreen) {
            showQrLoginScreen = false
            TelegramAuthController.dismissAuthUi()
        }
        BackHandler(enabled = showProfileScreen && !showSettings && !showProxyScreen && !showChannels && !showEqualizer) {
            showProfileScreen = false
        }
        // One back step out of Settings, or out of any tab but Home, lands on
        // Home rather than exiting — only Home itself hands back to the system,
        // which is what actually closes/minimizes the app.
        BackHandler(enabled = showSettings && !showEqualizer && !showProxyScreen && !showPhoneLoginScreen && !showQrLoginScreen) {
            showSettings = false
            // Only when Settings was the whole of what was on screen. Opened
            // over Replay or over a release page, closing it reveals that again
            // rather than throwing both away.
            if (detail == null && !showReplay && !showProfileScreen) selectedTab = TAB_HOME
        }
        BackHandler(
            enabled = !isWelcomeVisible && detail == null && !showSettings &&
                !showEqualizer && !showProxyScreen && !showPhoneLoginScreen && !showQrLoginScreen && !showProfileScreen &&
                !showReplay && !showChannels && !showPlaylists &&
                selectedTab != TAB_HOME,
        ) {
            selectedTab = TAB_HOME
        }
        BackHandler(enabled = showHistory) { showHistory = false }
        BackHandler(enabled = showChannels) { showChannels = false }
        BackHandler(enabled = openPlaylistId != null) { openPlaylistId = null }
        BackHandler(enabled = showLiked) { showLiked = false }
        BackHandler(enabled = showPlaylists && openPlaylistId == null) { showPlaylists = false }
        // Disabled while a detail page is open over the grid: that one's own
        // BackHandler below has to close first, or back would skip past it
        // straight to Library. See [onLibraryItemClick].
        BackHandler(enabled = libraryShowAll != null && detail == null) { libraryShowAll = null }
        BackHandler(enabled = detailActiveShelf != null) { detailActiveShelf = null }

        // On a tablet the page and the player stand side by side rather than
        // one over the other: everything a phone stacks in a single column —
        // the feed, the frosted bars, the tab row — becomes the left half of
        // a row, and the player is the right. Off a tablet the row has the
        // one child it always had and changes nothing.
        val showTelegramAuth by
            TelegramAuthController.showAuth.collectAsStateWithLifecycle()
        LaunchedEffect(showTelegramAuth) {
            if (showTelegramAuth) {
                showPhoneLoginScreen = true
            }
        }
        LaunchedEffect(telegramState) {
            if (telegramState is TelegramAuthState.Ready) {
                showPhoneLoginScreen = false
                showQrLoginScreen = false
                TelegramAuthController.dismissAuthUi()
            }
        }

        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                AnimatedContent(
                    targetState = when {
                        isWelcomeVisible && !showProxyScreen && !showPhoneLoginScreen && !showQrLoginScreen -> "welcome"
                        showHistory -> "history"
                        openPlaylistId != null -> "playlist_detail"
                        showLiked -> "liked"
                        showPlaylists -> "playlists"
                        showChannels -> "channels"
                        libraryShowAll != null && detail == null -> "library_show_all"
                        showEqualizer -> "equalizer"
                        showProxyScreen -> "proxy"
                        showPhoneLoginScreen -> "login_phone"
                        showQrLoginScreen -> "login_qr"
                        showSettings -> "settings"
                        showProfileScreen -> "profile"
                        showReplay -> "replay"
                        detail != null -> detail.browseId
                        else -> "$TAB_KEY$selectedTab"
                    },
                    // Tabs swap outright; everything else crossfades.
                    //
                    // A tab is not a place you travel to — the bar is the whole
                    // navigation and it carries its own movement — so a fade
                    // between two of them only ever reads as a stutter. And it
                    // cannot read as anything else: neither page paints a
                    // background, so a crossfade dissolves both through to the
                    // window and the switch dips through a dimmer frame in the
                    // middle. Pushing a page or raising Settings is a real
                    // change of context and keeps the fade.
                    //
                    // "Show all" swapping with the Library tab underneath it is
                    // the same case as a tab swap, not a pushed page: it's still
                    // that tab, just laid out as a grid instead of a row, sharing
                    // its background rather than painting its own — so this one
                    // pair gets the tab's no-fade swap too, in both directions, or
                    // the dip through a dim frame shows up on every hold of a
                    // card there. A card opened *from* the grid is a real page
                    // and keeps the fade, same as one opened from the row.
                    transitionSpec = {
                        val tabSwap = initialState.startsWith(TAB_KEY) && targetState.startsWith(TAB_KEY)
                        val libraryTabKey = "$TAB_KEY$TAB_LIBRARY"
                        val libraryShowAllSwap = (initialState == "library_show_all" && targetState == libraryTabKey) ||
                            (targetState == "library_show_all" && initialState == libraryTabKey)
                        if (tabSwap || libraryShowAllSwap) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        }
                    },
                    modifier = Modifier
                        .hazeSource(hazeState)
                        .then(
                            if (glassActive) {
                                Modifier
                                    // Not under "reduce dynamic blur": nothing
                                    // samples the layer then, and recording a
                                    // whole page into one for no reader is the
                                    // cost that setting exists to remove.
                                    .then(
                                        if (glassSamplesBackdrop) {
                                            Modifier.layerBackdrop(appBackdrop)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    // Every page's scroll passes through here, so
                                    // the glass bar collapses on all of them
                                    // without each one having to know about it.
                                    .nestedScroll(navBarScroll)
                            } else {
                                Modifier
                            },
                        ),
                    label = "content",
                ) { key ->
                    // Every branch below reads `key` rather than the state that
                    // produced it. The two are the same thing only for the page
                    // being entered: the one on its way out is still composed,
                    // and asking it what is selected *now* has it redraw itself
                    // as its own replacement — which then fades out from under
                    // the identical copy fading in behind it.
                    val live = detailStack.lastOrNull()?.takeIf {
                        it.browseId == key && key != "settings" &&
                            key != "replay" && key != "history" &&
                            key != "library_show_all" && key != "channels"
                    }
                    // Held for the same reason, one step further on: a popped
                    // page is off the stack before it has finished animating
                    // out, so `live` goes null under it and it would spend its
                    // exit drawing whatever is underneath instead of itself.
                    // Per slot, since each is remembered against its own key.
                    val held = remember(key) { mutableStateOf(live) }
                    if (live != null) held.value = live
                    val page = held.value
                    if (key == "history") {
                        HistoryScreen(
                            state = historyState,
                            listState = historyListState,
                            onSongClick = { songs, index ->
                                playFrom(songs, index, QueueSource(historyLabel, PlaybackSourceType.HISTORY))
                            },
                            onSongLongPress = { songActions = it },
                            onSongSwipe = onSongSwipe,
                            onRetry = viewModel::loadHistory,
                            contentPadding = listPadding,
                        )
                    } else if (key == "playlist_detail") {
                        openPlaylistId?.let { playlistId ->
                            PlaylistDetailScreen(
                                playlistId = playlistId,
                                listState = playlistDetailListState,
                                contentPadding = listPadding,
                                onPlay = { songs, index ->
                                    playFrom(
                                        songs,
                                        index,
                                        QueueSource(
                                            "پلی‌لیست",
                                            PlaybackSourceType.BROWSE,
                                            "playlist:$playlistId",
                                        ),
                                    )
                                },
                                onBack = { openPlaylistId = null },
                            )
                        }
                    } else if (key == "liked") {
                        LikedScreen(
                            listState = likedListState,
                            contentPadding = listPadding,
                            onPlay = { songs, index ->
                                playFrom(
                                    songs,
                                    index,
                                    QueueSource(likedLabel, PlaybackSourceType.HOME),
                                )
                            },
                        )
                    } else if (key == "playlists") {
                        val pinnedPlaylists by AppSettings.pinnedPlaylists
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        PlaylistsScreen(
                            contentPadding = listPadding,
                            listState = playlistListState,
                            onOpenPlaylist = { id -> openPlaylistId = id },
                            onPlay = { songs, index, name ->
                                playFrom(
                                    songs,
                                    index,
                                    QueueSource(name, PlaybackSourceType.BROWSE, "playlist"),
                                )
                            },
                            pinned = pinnedPlaylists,
                            onTogglePin = { AppSettings.togglePinnedPlaylist(it) },
                        )
                    } else if (key == "channels") {
                        ChannelsScreen(
                            contentPadding = listPadding,
                            onChannelSelected = {
                                showChannels = false
                                // The shelf's entries land on Songs with the
                                // filter already on; the Search pill's stay on
                                // Search, where the results re-filter.
                                if (channelsReturnToSongs) {
                                    selectedTab = TAB_HOME
                                    libraryShowAll = null
                                }
                            },
                            onPlayChannel = { songs, title ->
                                playFrom(
                                    songs,
                                    0,
                                    QueueSource(title, PlaybackSourceType.BROWSE, "tg:channels"),
                                )
                            },
                        )
                    } else if (key == "library_show_all") {
                        libraryShowAll?.let { shelf ->
                            LibraryGridPage(
                                shelf = shelf,
                                gridState = libraryShowAllGridState,
                                onItemClick = onLibraryItemClick,
                                onItemLongPress = onBrowseLongPress,
                                contentPadding = listPadding,
                            )
                        }
                    } else if (key == "replay") {
                        // Replay reads right-to-left, like the library and the settings —
                        // the same deal one screen at a time. The charts, the shelves, the
                        // story pages: they were drawn for a reader coming from the right,
                        // and a flag on the manifest would have taken the player with them.
                        ProvideLocaleDirection {
                            ReplayScreen(
                                state = replay,
                                holder = account?.name.orEmpty(),
                                onPeriodChange = setReplayPeriod,
                                onOpenStory = { replayStory = it },
                                // A track tapped on a chart is one the user already
                                // knows they like, so it starts a station off itself
                                // rather than queueing the chart it was on — the
                                // same reading [playRadio] makes of a search hit.
                                onPlaySong = { song ->
                                playRadio(song, QueueSource(replayLabel, PlaybackSourceType.REPLAY))
                                },
                                onOpenArtist = { id, name ->
                                showReplay = false
                                id?.let {
                                viewModel.openDetail(
                                it, name, context.getString(R.string.artist), null, BrowseType.ARTIST,
                                )
                                }
                                },
                                onOpenAlbum = { id, title, artist, art ->
                                showReplay = false
                                id?.let { viewModel.openDetail(it, title, artist.orEmpty(), art, BrowseType.ALBUM) }
                                },
                                onShare = {
                                replaySharePage = null
                                showReplayShare = true
                                },
                                contentPadding = listPadding,
                                listState = replayListState,
                                landingPage = replayLandingPage,
                            )
                        }
                    } else if (key == "welcome") {
                        WelcomeScreen(
                            onLoginPhoneClick = {
                                TelegramAuthController.usePhoneNumberInstead()
                                showPhoneLoginScreen = true
                            },
                            onLoginQrClick = {
                                showQrLoginScreen = true
                            },
                            onProxyClick = {
                                showProxyScreen = true
                            },
                            onLanguageClick = {
                                showAppLanguage = true
                            },
                        )
                    } else if (key == "login_phone") {
                        PhoneLoginScreen(
                            contentPadding = listPadding,
                            onSwitchToQr = {
                                showPhoneLoginScreen = false
                                showQrLoginScreen = true
                            },
                        )
                    } else if (key == "login_qr") {
                        QrLoginScreen(
                            contentPadding = listPadding,
                            onSwitchToPhone = {
                                showQrLoginScreen = false
                                showPhoneLoginScreen = true
                            },
                        )
                    } else if (key == "equalizer") {
                        EqualizerScreen(contentPadding = listPadding)
                    } else if (key == "proxy") {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides LayoutDirection.Rtl,
                        ) {
                            ProxyScreen(contentPadding = listPadding)
                        }
                    } else if (key == "settings") {
                    // Settings the same — read from the right, one screen at a
                    // time, so nothing else moves.

                    CompositionLocalProvider(
                        LocalLayoutDirection provides LayoutDirection.Rtl,
                    ) {
                        SettingsScreen(
                            windowWidth = windowWidth,
                            onEqualizer = { showEqualizer = true },
                            onProxy = { showProxyScreen = true },
                            onOpenReplay = {
                            showSettings = false
                            replayLandingPage = ReplayStoryPage.INTRO
                            showReplay = true
                            },
                            onLyricsSources = { showLyricsSources = true },
                            onTranslationLanguage = { showTranslationLanguage = true },
                            onAppLanguage = { showAppLanguage = true },
                            contentPadding = listPadding,
                        )
                    }
                    } else if (key == "profile") {
                        ProfileScreen(
                            contentPadding = listPadding,
                            onOpenSettings = { showSettings = true },
                            onOpenProxy = { showProxyScreen = true },
                            onOpenChannels = { showChannels = true },
                            onOpenSavedMessages = {
                                showProfileScreen = false
                                showChannels = true
                            },
                        )
                    } else if (page != null && page.browseId.isDeviceFolder()) {
                        // Local Music and Downloads — both the tabbed Songs / Artists /
                        // Albums view. Two folders of tracks already on the device, so
                        // there is nothing to tell them apart on screen beyond what is
                        // in them and what to say when that is nothing.
                        //
                        // A single downloaded playlist is not one of these: it has one
                        // running order and nothing to tab through, so it falls to the
                        // release page below.
                        val localState = page.songs
                        val localSongs = (localState as? com.extrumz.app.data.model.UiState.Success)
                            ?.data.orEmpty()
                        // Only the Downloads folder has releases behind it: Local
                        // Music is files this app never asked for, so there is
                        // nothing on record about how they were grouped. Keyed on
                        // the record as well as the list, so downloading an album
                        // while its folder is open adds the folder rather than
                        // waiting for the page to be reopened.
                        val downloadCollections = remember(localSongs, savedCollections) {
                            if (page.browseId == "local:downloads") {
                                Downloads.collectionsAmong(localSongs)
                            } else {
                                emptyList()
                            }
                        }
                        LocalMusicScreen(
                            songs = localSongs,
                            collections = downloadCollections,
                            isDownloads = page.browseId == "local:downloads",
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            onDeleteDownloads = { selected ->
                                scope.launch {
                                    selected.forEach { song -> Downloads.delete(context, song.videoId) }
                                }
                            },
                            onSongClick = { songs, index ->
                                playFrom(
                                    songs,
                                    index,
                                    QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId),
                                )
                            },
                            onSongLongPress = openSongMenu,
                            onSongSwipe = onSongSwipe,
                            onShuffle = { songs ->
                                QueueShuffle.enableForNextQueue()
                                playFrom(
                                    songs,
                                    songs.indices.random(),
                                    QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId),
                                )
                            },
                            emptyMessage = (localState as? com.extrumz.app.data.model.UiState.Error)
                                ?.message,
                            // An album or artist here is a grouping of rows rather than
                            // a page, so the menu is handed the rows themselves — there
                            // is no id anything could be fetched with.
                            onCollectionLongPress = { label, grouped ->
                                // An artist grouping is never one of these — only a
                                // release downloaded whole has a record to match,
                                // which is exactly the distinction `asked` draws in
                                // `albumEntries`.
                                val downloadId = downloadCollections.firstOrNull {
                                    it.title == label && it.songs == grouped
                                }?.id
                                browseActions = BrowseTarget(
                                    browseId = null,
                                    title = label,
                                    subtitle = grouped.firstOrNull()?.artist.orEmpty()
                                        .takeUnless { it == label }
                                        .orEmpty(),
                                    thumbnailUrl = grouped.firstOrNull()?.thumbnailUrl,
                                    songs = grouped,
                                    downloadId = downloadId,
                                )
                            },
                            contentPadding = listPadding,
                        )
                    } else if (page != null) {
                        // An album page's rows carry no album name of their own — the
                        // release is billed once, in the header the rows hang under — so
                        // the page title is stamped on as they leave for the download
                        // queue or the track menu. Without it every track saved from an
                        // album arrives in the Downloads folder with nothing to group it
                        // under, and its Albums tab stays empty however much is in it.
                        val withAlbum: (Song) -> Song = { song ->
                            if (page.type == BrowseType.ALBUM) {
                                song.copy(albumName = song.albumName ?: page.title)
                            } else {
                                song
                            }
                        }
                        DetailScreen(
                            page = page,
                            currentSong = player.song,
                            isPlaying = player.isPlaying,
                            listState = detailListState,
                            activeShelf = detailActiveShelf,
                            onActiveShelfChange = { detailActiveShelf = it },
                            onSongClick = { songs, index ->
                                playFrom(
                                    songs,
                                    index,
                                    QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId),
                                )
                            },
                            onSongLongPress = { openSongMenu(withAlbum(it)) },
                            onSongSwipe = onSongSwipe,
                            onShuffle = { songs ->
                                // Shuffle goes on first so the queue is built shuffled
                                // as it is set — the random pick here only decides
                                // which track leads it.
                                QueueShuffle.enableForNextQueue()
                                playFrom(
                                    songs,
                                    songs.indices.random(),
                                    QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId),
                                )
                            },
                            onSectionItemClick = { item ->
                                item.browseId?.let { id ->
                                    viewModel.openDetail(
                                        browseId = id,
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        thumbnailUrl = item.thumbnailUrl,
                                        type = BrowseType.ALBUM,
                                    )
                                }
                            },
                            onSectionItemLongPress = onBrowseLongPress,
                            // The page's own tracks, so the sheet has them already and
                            // Play, Shuffle and Open are the buttons beside the one that
                            // opened it rather than rows on it. Download is the other
                            // way round: the header no longer carries it, so the sheet
                            // is where a whole release is asked for — and the tracks
                            // arrive stamped with the album they came off, which is what
                            // the download record groups them under.
                            onMore = { songs ->
                                browseActions = BrowseTarget(
                                    browseId = page.browseId,
                                    title = page.title,
                                    subtitle = page.subtitle,
                                    thumbnailUrl = page.thumbnailUrl,
                                    type = page.type,
                                    songs = songs.map(withAlbum),
                                    fromCard = false,
                                    downloadId = downloadIdFor(page.browseId),
                                )
                            },
                            onArtistClick = { id, name ->
                                viewModel.openDetail(id, name, "Artist", null, BrowseType.ARTIST)
                            },
                            songSort = songSort,
                            contentPadding = listPadding,
                        )
                    } else when (key.removePrefix(TAB_KEY).toIntOrNull() ?: selectedTab) {
                        TAB_SEARCH -> SearchScreen(
                            query = query,
                            onQueryChange = viewModel::onQueryChange,
                            filter = filter,
                            onFilterChange = viewModel::onFilterChange,
                            results = results,
                            loadingMore = searchLoadingMore,
                            onLoadMore = viewModel::loadMoreSearchResults,
                            listState = searchListState,
                            scrollResetTrigger = searchScrollReset,
                            focusRequested = searchFocusRequested,
                            onFocusHandled = { searchFocusRequested = false },
                            // Search hits are alternatives to each other, not a running
                            // order — play the one tapped and build a station from it.
                            onSongClick = { songs, index ->
                                songs.getOrNull(index)?.let { song ->
                                    viewModel.recordEntity(SearchHistoryEntity(
                                        id = song.videoId,
                                        title = song.title,
                                        subtitle = song.artist.ifEmpty { "" },
                                        artworkUrl = song.thumbnailUrl,
                                        entityType = EntityType.TRACK,
                                    ))
                                    playRadio(song, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
                                }
                            },
                            onSongLongPress = openSongMenu,
                            onSongSwipe = onSongSwipe,
                            onTopResultPlay = { song ->
                                viewModel.recordEntity(SearchHistoryEntity(
                                    id = song.videoId,
                                    title = song.title,
                                    subtitle = song.artist.ifEmpty { "" },
                                    artworkUrl = song.thumbnailUrl,
                                    entityType = EntityType.TRACK,
                                ))
                                playRadio(song, QueueSource(searchLabel, PlaybackSourceType.SEARCH))
                            },
                            onBrowseClick = { item ->
                                viewModel.recordEntity(SearchHistoryEntity(
                                    id = item.browseId ?: "",
                                    title = item.title,
                                    subtitle = item.subtitle.ifBlank { "" },
                                    artworkUrl = item.thumbnailUrl,
                                    entityType = when (item.type) {
                                        BrowseType.ALBUM -> EntityType.ALBUM
                                        BrowseType.ARTIST -> EntityType.ARTIST
                                        BrowseType.PLAYLIST -> EntityType.PLAYLIST
                                        else -> EntityType.TRACK
                                    },
                                ))
                                viewModel.openDetail(
                                    browseId = item.browseId,
                                    title = item.title,
                                    subtitle = item.subtitle,
                                    thumbnailUrl = item.thumbnailUrl,
                                    type = item.type,
                                )
                            },
                            onBrowseLongPress = { item ->
                                // A search row does say what it is, so its own type is
                                // better than what the browse id can be read to mean.
                                if (item.type != BrowseType.ARTIST) {
                                    browseActions = BrowseTarget(
                                        browseId = item.browseId,
                                        title = item.title,
                                        subtitle = item.subtitle,
                                        thumbnailUrl = item.thumbnailUrl,
                                        type = item.type,
                                        downloadId = downloadIdFor(item.browseId),
                                    )
                                }
                            },
                            history = searchHistory,
                            suggestions = searchSuggestions,
                            typeaheadResults = viewModel.typeaheadResults.collectAsStateWithLifecycle().value,
                            onSubmit = viewModel::submitSearch,
                            // Suggestions land in search history via searchFor → recordSearch.
                            // History items (onHistoryClick) navigate/play without re-logging.
                            onSuggestionClick = viewModel::searchFor,
                            onHistoryClick = { entity ->
                                // Tap a history entity: navigate to it or play it directly.
                                // Do NOT recordEntity here — tapping an existing history item
                                // must not update its timestamp and push it to the top.
                                when (entity.entityType) {
                                    EntityType.TRACK -> {
                                        // Play the track by its video id
                                        playRadio(
                                            com.extrumz.app.data.model.Song(
                                                videoId = entity.id,
                                                title = entity.title,
                                                artist = entity.subtitle,
                                                thumbnailUrl = entity.artworkUrl,
                                            ),
                                            QueueSource(entity.title, PlaybackSourceType.SEARCH),
                                        )
                                    }
                                    EntityType.ALBUM, EntityType.ARTIST, EntityType.PLAYLIST -> {
                                        viewModel.openDetail(
                                            browseId = entity.id,
                                            title = entity.title,
                                            subtitle = entity.subtitle,
                                            thumbnailUrl = entity.artworkUrl,
                                        )
                                    }
                                }
                            },
                            onHistoryRemove = viewModel::removeSearch,
                            onHistoryClear = viewModel::clearSearchHistory,
                            onTypeaheadLongPress = openSongMenu,
                            // From Search the picker keeps the listener on
                            // Search — the rows re-filter in place.
                            onOpenChannels = {
                                channelsReturnToSongs = false
                                showChannels = true
                            },
                            contentPadding = listPadding,
                        )
                        // The library reads right-to-left — the whole screen, not
                        // just its strings: shelf order, title alignment, the "Show
                        // all" link. It is mounted under an explicit direction rather
                        // than by flipping supportsRtl, because that flag would take
                        // every other screen with it.

                        TAB_LIBRARY -> CompositionLocalProvider(
                            LocalLayoutDirection provides LayoutDirection.Rtl,
                        ) {
                            LibraryScreen(
                                listState = libraryListState,
                                onShelfItemClick = onLibraryItemClick,
                                // Every shelf here has a menu behind it now — a saved
                                // album or a Liked Music card gets it. Holding an artist
                                // still does nothing; see [onBrowseLongPress].
                                onShelfItemLongPress = onBrowseLongPress,
                                onShowAll = { shelf -> libraryShowAll = shelf },
                                replayCards = replayCards,
                                replayHolder = account?.name.orEmpty(),
                                replayMemberSince = replay.memberSince,
                                onOpenReplay = { page ->
                                    replayLandingPage = page
                                    showReplay = true
                                },
                                contentPadding = listPadding,
                                downloadedPlaylists = downloadedPlaylists,
                            )
                        }
                        // [TAB_HOME] itself, and any stray value a tab key
                        // could carry, land on the default home tab — the
                        // three real branches above are explicit.
                        else -> SongsScreen(
                            contentPadding = listPadding,
                            currentSong = player.song,
                            listState = songsListState,
                            onSongClick = { songs, index ->
                                playFrom(
                                    songs,
                                    index,
                                    // HOME, not BROWSE: this play has no
                                    // sourceId, and the BROWSE branch of
                                    // onOpenPlaybackSource bails on a null id,
                                    // stranding Now Playing's source tap.
                                    QueueSource(songsLabel, PlaybackSourceType.HOME),
                                )
                            },
                            onSongLongPress = { songActions = it },
                        )
                    }
                }

                // Artwork-led pages and Replay leave the top-bar footprint
                // transparent so the shared app-level gradient is continuous.
                // Other pages use the navbar's regular bounded blur unless
                // Liquid Glass has switched them to separated controls too.
                val isDetailVisible = detail != null &&
                    (detail.type == BrowseType.ALBUM ||
                        detail.type == BrowseType.PLAYLIST ||
                        detail.type == BrowseType.ARTIST) &&
                    !isLocalDetail && !showHistory && !showSettings && !showProfileScreen &&
                    !showEqualizer && !showProxyScreen && !showPhoneLoginScreen && !showQrLoginScreen && !showReplay
                val isReplayVisible = showReplay && !showHistory &&
                    !(libraryShowAll != null && detail == null) &&
                    !showEqualizer && !showProxyScreen && !showPhoneLoginScreen && !showQrLoginScreen && !showProfileScreen && !showSettings
                val chromePageColor = if (isDetailVisible) {
                    detailPalette.background
                } else {
                    MaterialTheme.colorScheme.background
                }
                if (!isWelcomeVisible || showProxyScreen || showPhoneLoginScreen || showQrLoginScreen || showProfileScreen) {
                    // This is the bottom floor itself turned upside down, not a
                    // separately maintained approximation. Both edges therefore
                    // share the same curve, height and page-aware colour — including
                    // the white theme background in light mode.
                    BottomFadeScrim(
                        pageColor = chromePageColor,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .rotate(180f),
                    )

                    // With Liquid Glass enabled, every page uses separated floating
                    // controls and therefore has no full-width pane underneath.
                    if (!glassActive && !isReplayVisible && !isDetailVisible) {
                        TopBarBlur(
                            hazeState = hazeState,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }

                    FrostedTopBar(
                        title = when {
                            showHistory -> stringResource(R.string.history)
                            showChannels -> stringResource(R.string.tab_channels)
                            openPlaylistId != null ->
                                openPlaylistTitle ?: stringResource(R.string.playlists)
                            showPlaylists -> stringResource(R.string.playlists)
                            libraryShowAll != null && detail == null -> libraryShowAll?.title.orEmpty()
                            showEqualizer -> stringResource(R.string.equalizer)
                            showProxyScreen -> stringResource(R.string.proxy)
                            showPhoneLoginScreen -> stringResource(R.string.login_phone_title)
                            showQrLoginScreen -> stringResource(R.string.login_qr_title)
                            showProfileScreen -> stringResource(R.string.profile_title)
                            showSettings -> stringResource(R.string.settings)
                            showReplay -> stringResource(R.string.replay)
                            detail != null && detailActiveShelf != null -> detailActiveShelf?.title.orEmpty()
                            detail != null -> detail.title
                            // getOrNull: a saved-state key from the pre-stage-1
                            // build can still hold an index this three-tab list
                            // has no meaning for — home beats crashing. The old
                            // "Play" remap was YouTube-fork dead code.
                            else -> tabs.getOrNull(selectedTab)?.label ?: tabs.first().label
                        },
                        transparentBackdrop = glassActive || isReplayVisible || isDetailVisible,
                        artworkPageChrome = isReplayVisible || isDetailVisible,
                        backButtonHazeState = hazeState,
                        trailingTitle = if (detail != null && detailActiveShelf != null) detail.title else null,
                        // Search has no large in-list header to hand the title back to —
                        // the field takes that space — so its bar title is always up.
                        scrolled = when {
                            // The picker owns its list; its big title stays up the
                            // way the Channels tab's did when it was a tab.
                            showChannels -> false
                            // false, like the channels picker: the bar's title IS
                            // the page's heading, so it has nothing to hand back
                            // to — hiding it on scroll would leave the page nameless.
                            openPlaylistId != null -> false
                            // false, for the same reason as the playlist's own
                            // page: the bar's title is this page's heading, so
                            // there is nothing for it to hand back to.
                            showLiked -> false
                            showPlaylists -> false
                            showSettings ||
                                showEqualizer ||
                                showProxyScreen ||
                                showPhoneLoginScreen ||
                                showQrLoginScreen ||
                                showProfileScreen ||
                                showHistory ||
                                (libraryShowAll != null && detail == null) ||
                                (detail != null && detailActiveShelf != null) -> true
                            // The page leads with its own large "Replay", so the bar
                            // stays out of the way until that has been scrolled off.
                            showReplay -> replayScrolled
                            detail != null -> detailScrolled
                            else -> scrolled || selectedTab == TAB_SEARCH
                        },
                        refreshing = false,
                        pullFraction = { 0f },
                        onBack = when {
                            showHistory -> ({ showHistory = false })
                            showChannels -> ({ showChannels = false })
                            openPlaylistId != null -> ({ openPlaylistId = null })
                            showLiked -> ({ showLiked = false })
                            showPlaylists -> ({ showPlaylists = false })
                            libraryShowAll != null && detail == null -> ({ libraryShowAll = null })
                            showEqualizer -> ({ showEqualizer = false })
                            showProxyScreen -> ({ showProxyScreen = false })
                            showPhoneLoginScreen -> ({ showPhoneLoginScreen = false; TelegramAuthController.dismissAuthUi() })
                            showQrLoginScreen -> ({ showQrLoginScreen = false; TelegramAuthController.dismissAuthUi() })
                            showProfileScreen -> ({ showProfileScreen = false })
                            showSettings -> ({ showSettings = false })
                            showReplay -> ({ showReplay = false })
                            detailActiveShelf != null -> ({ detailActiveShelf = null })
                            detail != null -> ({ viewModel.closeDetail(); Unit })
                            else -> null
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                        actions = {
                            if (!showSettings && !showProfileScreen && !showProxyScreen && !showPhoneLoginScreen && !showQrLoginScreen && !showEqualizer) {
                                // Left of the account photo, and only on Library itself:
                                // a history is a record of what was played, which reads
                                // as that tab's business rather than every tab's.
                                if (!showHistory && !showReplay && !showChannels && !showPlaylists &&
                                    libraryShowAll == null &&
                                    detail == null && selectedTab == TAB_LIBRARY
                                ) {
                                    IconButton(
                                        onClick = {
                                            showHistory = true
                                            viewModel.loadHistory()
                                        },
                                    ) {
                                        Icon(
                                            Icons.Rounded.History,
                                            contentDescription = stringResource(R.string.listening_history),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                // Left of the account photo, and only on a Library
                                // "Show all" grid — the same control the Downloads
                                // folder offers (see `LocalSearchField`), adapted to
                                // the one thing a playlist or album card carries: a
                                // title.
                                if (libraryShowAll != null && detail == null) {
                                    Box {
                                        IconButton(onClick = { librarySortMenuOpen = true }) {
                                            Icon(
                                                Icons.Rounded.Sort,
                                                contentDescription = stringResource(R.string.sort_library),
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                        ExtrumzDropdownMenu(
                                            expanded = librarySortMenuOpen,
                                            onDismissRequest = { librarySortMenuOpen = false },
                                        ) {
                                            LibrarySort.entries.forEach { option ->
                                                ExtrumzDropdownMenuItem(
                                                    text = { Text(option.localizedLabel()) },
                                                    trailingIcon = if (option == librarySort) {
                                                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                                    } else null,
                                                    onClick = {
                                                        AppSettings.setLibrarySort(option)
                                                        librarySortMenuOpen = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                // Left of the account photo, and only on an album or
                                // playlist page — an artist page has no single track
                                // list to reorder, and the device folders already
                                // carry this same control themselves (see
                                // `LocalSearchField`).
                                if (detail != null && !isLocalDetail && detail.type != BrowseType.ARTIST) {
                                    // The menu itself is [FrostedSortMenu], composed
                                    // with the app's other frosted overlays further
                                    // down — in the main hierarchy, where the haze
                                    // can see the content it blurs.
                                    IconButton(onClick = { songSortMenuOpen = true }) {
                                        Icon(
                                            Icons.Rounded.Sort,
                                            contentDescription = stringResource(R.string.sort_songs),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                // Left of the account photo, and only there while
                                // there is a batch to report on — see
                                // [TopBarDownloadButton], which decides that for
                                // itself rather than being told.
                                TopBarDownloadButton(onClick = { showDownloadManager = true })
                                val telegramUser by Telegram.client.currentUser.collectAsStateWithLifecycle()
                                TopBarAccountButton(
                                    account = account,
                                    avatarUrl = telegramUser?.photoUrl,
                                    onClick = {
                                        showChannels = false
                                        showProfileScreen = true
                                    },
                                )
                            }
                        },
                    )

                    // Drawn before the bars so their own glass reads on top of it.
                    BottomFadeScrim(
                        withMiniPlayer = player.song != null,
                        // Not the wash: by the foot of the screen the page has finished
                        // easing out of it and into this, so this is what is actually
                        // under the tab bar.
                        pageColor = chromePageColor,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // One tab handler, whichever bar is drawing it.
                val onTabSelected: (Int) -> Unit = { index ->
                    viewModel.clearDetail()
                    showSettings = false
                    showEqualizer = false
                    showProxyScreen = false
                    showReplay = false
                    showHistory = false
                    showChannels = false
                    // Without this the route below still outranked the tab:
                    // tapping Songs changed selectedTab and left this true, so
                    // the key switch kept showing this page and the bar looked
                    // dead.
                    showPlaylists = false
                    showLiked = false
                    openPlaylistId = null
                    libraryShowAll = null
                    selectedTab = index

                    // Every search tab tap resets the field, focuses it, and opens
                    // the keyboard through SearchScreen's focus request.
                    if (index == TAB_SEARCH) {
                        viewModel.onQueryChange("")
                        searchFocusRequested = true
                    }
                }

                if (!isWelcomeVisible) {
                    if (glassActive) Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                            .fillMaxWidth(),
                    ) {
                        QueueActionNoticeHost(queueNotice)
                        // Liquid glass replaces the two stacked bars with the single
                        // component they are stacked to imitate: the now playing
                        // controls dock into the tab bar rather than riding above it,
                        // and the pair folds together on scroll. See [GlassNavBar].
                        GlassNavBar(
                            tabs = tabs,
                            selectedIndex = selectedTab,
                            onTabSelected = onTabSelected,
                            scrollConnection = navBarScroll,
                            song = player.song,
                            isPlaying = player.isPlaying,
                            isLoading = playPauseBusy,
                            onPlayPause = {
                                togglePlayPause()
                            },
                            onNext = { controller?.seekToNextMediaItem() },
                            onPrevious = { controller?.seekToPrevious() },
                            onExpand = { showNowPlaying = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // Capped and centred rather than run to the page's edges
                            // — see [FLOATING_BAR_MAX_WIDTH]. It sits on the Column
                            // rather than on each bar so the two are held to the same
                            // width and keep the shared left and right edge they have
                            // on a phone. Before fillMaxWidth, so the fill has
                            // already been bounded by the time it is applied.
                            .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        QueueActionNoticeHost(queueNotice)
                        player.song?.let { song ->
                            MiniPlayer(
                                song = song,
                                isPlaying = player.isPlaying,
                                isLoading = playPauseBusy,
                                hazeState = hazeState,
                                onPlayPause = {
                                    togglePlayPause()
                                },
                                onNext = { controller?.seekToNextMediaItem() },
                                onPrevious = { controller?.seekToPrevious() },
                                onExpand = { showNowPlaying = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        FloatingBottomBar(
                            tabs = tabs,
                            selectedIndex = selectedTab,
                            hazeState = hazeState,
                            onTabSelected = onTabSelected,
                        )
                    }
                }

                // Floating shield & return-to-top arrow on main tabs (Songs and Library)
                val isMainContentTab = isMainContentTab(
                    isWelcomeVisible = isWelcomeVisible,
                    selectedTab = selectedTab,
                    homeTab = TAB_HOME,
                    libraryTab = TAB_LIBRARY,
                    hasDetail = detail != null,
                    hasLibraryShowAll = libraryShowAll != null,
                    isSettingsOpen = showSettings,
                    isEqualizerOpen = showEqualizer,
                    isProxyOpen = showProxyScreen,
                    isPhoneLoginOpen = showPhoneLoginScreen,
                    isQrLoginOpen = showQrLoginScreen,
                    isProfileOpen = showProfileScreen,
                    isReplayOpen = showReplay,
                    isHistoryOpen = showHistory,
                    isChannelsOpen = showChannels,
                    isPlaylistsOpen = showPlaylists,
                    isLikedOpen = showLiked,
                    hasOpenPlaylist = openPlaylistId != null,
                )

                val proxyOn by ProxyStore.masterEnabled.collectAsStateWithLifecycle()
                val showJumpToTop by remember {
                    derivedStateOf {
                        when (selectedTab) {
                            TAB_LIBRARY -> shouldShowJumpToTop(
                                libraryListState.firstVisibleItemIndex,
                                libraryListState.firstVisibleItemScrollOffset,
                            )
                            TAB_HOME -> shouldShowJumpToTop(
                                songsListState.firstVisibleItemIndex,
                                songsListState.firstVisibleItemScrollOffset,
                            )
                            else -> false
                        }
                    }
                }

                val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val fabBottomPadding = (if (player.song != null) 148.dp else 80.dp) + navBottomPadding

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                            .fillMaxWidth(),
                    ) {
                        MainFloatingActions(
                            showShield = proxyOn && isMainContentTab,
                            showJumpToTop = showJumpToTop && isMainContentTab,
                            onShieldClick = { showProxyScreen = true },
                            onJumpToTopClick = {
                                scope.launch {
                                    if (selectedTab == TAB_LIBRARY) {
                                        libraryListState.animateScrollToItem(0)
                                    } else if (selectedTab == TAB_HOME) {
                                        songsListState.animateScrollToItem(0)
                                    }
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = PAGE_GUTTER, bottom = fabBottomPadding),
                        )
                    }
                }
            }

        }

        val playerRaised = showNowPlaying && playerSong != null

        // ---- Now Playing ----
        if (playerRaised) {
            val nowPlayingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showNowPlaying = false },
                sheetState = nowPlayingSheetState,
                // The player fills the screen and paints its own background to
                // the very top, so the sheet's default 28.dp top corners would
                // only cut two notches out of the artwork behind the status bar.
                shape = RectangleShape,
                containerColor = Color.Transparent,
                dragHandle = null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                // M3 caps a bottom sheet at [BottomSheetDefaults.SheetMaxWidth]
                // (640.dp) and centres it once the window is wider than that —
                // built for a sheet that's meant to look like a sheet next to
                // visible content either side. This one is the whole player;
                // capped at 640dp on a tablet it renders as a narrow card with
                // the page it's supposed to be covering visible down both
                // sides. Unspecified opts out of the cap entirely, so the
                // sheet always spans the full window this app draws it for.
                sheetMaxWidth = Dp.Unspecified,
            ) {
                // Keeps a sheet still "settling" after a lyrics or queue
                // scroll from taking the next touch meant for that list.
                Box(Modifier.guardSheetFromContentTouches(nowPlayingSheetState)) {
                    nowPlaying(playerSong)
                }
            }
        }

        // ---- Replay stories ----
        // Mounted out here rather than inside the pane above, because a story
        // covers the window: on a tablet the page is only the left half of the
        // row, and a story laid out inside it would run alongside the player
        // instead of over it.
        replayStory?.let { start ->
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                // A story is mounted out here, above the pane, so the direction
                // the page beneath it declared does not reach it — and without
                // declaring its own, every card in the run laid out from the
                // left while the Replay it was opened from read from the right.
                ProvideLocaleDirection {
                    ReplayStories(
                        summary = summary,
                        start = start,
                        onClose = { replayStory = null },
                        onShare = { card ->
                            replaySharePage = card
                            showReplayShare = true
                        },
                        paused = showReplayShare,
                    )
                }
            }
        }

        // ---- Share the Replay ----
        if (showReplayShare) {
            replay.summary?.takeUnless { it.isEmpty }?.let { summary ->
                ModalBottomSheet(
                    onDismissRequest = { showReplayShare = false },
                    // Straight to full height. The sheet is a picture and two
                    // buttons, and half-open it showed the picture with both
                    // buttons below the fold — a sheet whose only two controls
                    // need a drag to reach.
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    // The sheet is the one Replay surface that was left out of
                    // the RTL scope: the screen and the story pages both read
                    // from the right for Persian, and the sheet the user
                    // reaches from either of them opened the other way round.
                    ProvideLocaleDirection {
                        ReplayShareSheet(
                            summary = summary,
                            holder = account?.name.orEmpty(),
                            memberSince = replay.memberSince,
                            page = replaySharePage,
                            onDismiss = { showReplayShare = false },
                        )
                    }
                }
            }
        }

        // ---- Album / playlist detail ----
        // ---- Long-press track actions ----
        songActions?.let { song ->
            // Set by whoever opened it — see [menuFromPlayer]. It cannot be
            // read off the player's own visibility any more, because on a
            // tablet the player is visible whatever the menu was opened from.
            val fromPlayer = menuFromPlayer
            // Navigating has to take the player down with the sheet, or the
            // page it opens lands behind a still-covering player.
            // The track's cover stands in for an album's, but never for an
            // artist's picture — that page loads its own.
            val openPage: (String, String, String, BrowseType) -> Unit = { id, title, sub, type ->
                songActions = null
                showNowPlaying = false
                val art = song.thumbnailUrl.takeUnless { type == BrowseType.ARTIST }
                viewModel.openDetail(id, title, sub, art, type)
            }
            ModalBottomSheet(
                onDismissRequest = { songActions = null },
                // The sheet paints itself in the track's own colours, corners
                // and drag handle included — see SongActionsSheet.
                containerColor = Color.Transparent,
                dragHandle = null,
            ) {
                SongActionsSheet(
                    song = song,
                    signedIn = signedIn,
                    onAddToPlaylist = {
                        addToPlaylistSong = song
                        songActions = null
                    }

,
                    likeStatus = likeStatuses[song.videoId] ?: LikeStatus.INDIFFERENT,
                    onPlayNext = { playNext(song); songActions = null },
                    onAddToQueue = { addToQueue(song); songActions = null },
                    onStartRadio = { startRadio(song); songActions = null },
                    // Stays open: the row it replaces itself with is the
                    // progress, and closing the sheet would hide the only
                    // answer to "did that work?".
                    onDownload = { downloadSong(song) },
                    // The sheet stays up for a rating: it shows the new state
                    // in place, and people often thumb a song and then queue it.
                    // One line. The rating and the write are the same call
                    // now — this used to spell the write out here, which is
                    // exactly why four other surfaces never got one.
                    onToggleLike = { viewModel.toggleLike(song) },
                    onToggleDislike = {
                        val previousStatus = viewModel.toggleDislike(song.videoId)
                        if (
                            previousStatus != null &&
                            shouldSkipAfterDislike(
                                previousStatus = previousStatus,
                                targetVideoId = song.videoId,
                                currentVideoId = player.song?.videoId,
                            )
                        ) {
                            controller?.seekToNextMediaItem()
                        }
                    },
                    onOpenAlbum = { id ->
                        openPage(
                            id,
                            song.albumName ?: song.title,
                            song.artist,
                            BrowseType.ALBUM,
                        )
                    },
                    onOpenArtist = { id ->
                        openPage(id, song.artist, context.getString(R.string.artist), BrowseType.ARTIST)
                    },
                    showSleepTimer = fromPlayer,
                    // Asked for by hand, so it is offered for whatever is
                    // Hidden outright when there's no real YouTube id behind
                    // this row to build a link from — SongActionsSheet already
                    // drops it for a local file via `isOffline`, this catches
                    // the rest.
                    onCopyLog = if (fromPlayer) {
                        {
                            songActions = null
                            scope.launch {
                                val text = TrackLog.forTrack(song, NerdStats.current.value)
                                clipboard.setText(AnnotatedString(text))
                                // The line count, not just "copied": it is the
                                // one thing the system's own paste confirmation
                                // doesn't say, and an empty log is a real
                                // outcome worth seeing rather than a silent one.
                                Toast.makeText(
                                    context,
                                    context.resources.getQuantityString(
                                        R.plurals.log_copied_line_count,
                                        text.lineSequence().count(),
                                        text.lineSequence().count(),
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    } else {
                        null
                    },
                    onLyricsOffset = if (fromPlayer) {
                        {
                            songActions = null
                            showLyricsOffset = true
                        }
                    } else {
                        null
                    },
                )
            }
        }

            // Add-to-playlist, as its own sheet rather than a page inside the
            // first one: the song menu closes and this takes its place, so the
            // list of playlists is never stacked on top of a scrolled list of
            // actions.
            addToPlaylistSong?.let { pending ->
                AddToPlaylistSheet(
                    song = pending,
                    onDismiss = { addToPlaylistSong = null },
                    // Nothing to add it to yet. Hand the user to the page where
                    // a playlist can be made, rather than creating a nameless
                    // one on their behalf.
                    onCreated = {
                        addToPlaylistSong = null
                        showPlaylists = true
                    },
                )
            }

        // ---- Download manager ----
        // The batch view of what the top-bar indicator is counting. Dismissing
        // it is what marks the batch seen, and marking it on the way *out*
        // rather than on the way in is deliberate: it is the outcome the user is
        // signing off on, and while the sheet is up there may not be one yet.
        if (showDownloadManager) {
            val closeDownloadManager = {
                showDownloadManager = false
                DownloadSession.markSeen()
            }
            BackHandler(onBack = closeDownloadManager)
            ModalBottomSheet(
                onDismissRequest = closeDownloadManager,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                DownloadManagerSheet(onDismiss = closeDownloadManager)
            }
        }


        // ---- Album / playlist actions ----
        // Opened by holding a card on any tab, or from the release page's own
        // overflow. What a track's long-press menu is to one song, this is to
        // the whole release — the queue rows above all.
        browseActions?.let { target ->
            // Every row here closes the menu first: the tracks may still have to
            // be fetched, and leaving the sheet up over a request nothing on it
            // reports on reads as a tap that didn't land.
            val act: ((List<Song>) -> Unit) -> () -> Unit = { action ->
                {
                    browseActions = null
                    withBrowseSongs(target, action)
                }
            }
            val remote = target.browseId?.startsWith("local:") == false
            val pinnedPlaylists by AppSettings.pinnedPlaylists.collectAsStateWithLifecycle()
            val pinnableId = target.browseId?.takeIf { target.type == BrowseType.PLAYLIST }
            ModalBottomSheet(
                onDismissRequest = { browseActions = null },
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                BrowseActionsSheet(
                    // The live answer, not the one the target was built with.
                    target = target,
                    onPlayNext = act(playSongsNext),
                    onAddToQueue = act(addSongsToQueue),
                    onPlay = act { songs -> play(songs, 0) }.takeIf { target.fromCard },
                    onShuffle = act { songs ->
                        // As on a release page: shuffle goes on before the queue
                        // is built, so it is built shuffled rather than played
                        // out of order.
                        QueueShuffle.enableForNextQueue()
                        play(songs, songs.indices.random())
                    }.takeIf { target.fromCard },
                    onOpen = target.browseId
                        ?.takeIf { target.fromCard }
                        ?.let { id ->
                            {
                                browseActions = null
                                viewModel.openDetail(
                                    browseId = id,
                                    title = target.title,
                                    subtitle = target.subtitle,
                                    thumbnailUrl = target.thumbnailUrl,
                                    type = target.type,
                                )
                            }
                        },
                    // The one place a whole release is asked for, from a card and
                    // from the release's own page alike — its header spends that
                    // spot on the search now. Nothing on this device needs
                    // fetching to be on it, so a local page is the exception.
                    // What the target carries that the tracks don't is the
                    // release's own name and cover, which is exactly what the
                    // record wants.
                    onDownloadAll = act { songs ->
                        startDownload(
                            songs,
                            target.browseId
                                ?.takeIf { target.type != BrowseType.ARTIST }
                                ?.let { id ->
                                    DownloadTarget(
                                        id = id,
                                        title = target.title,
                                        subtitle = target.subtitle,
                                        thumbnailUrl = target.thumbnailUrl,
                                        playlist = target.type == BrowseType.PLAYLIST,
                                    )
                                },
                        )
                    }.takeIf { remote },
                    isPinned = pinnableId != null && pinnableId in pinnedPlaylists,
                    onTogglePin = pinnableId?.let { id ->
                        {
                            val nowPinned = AppSettings.togglePinnedPlaylist(id)
                            if (!nowPinned && id !in pinnedPlaylists) {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.pinned_playlist_limit,
                                        AppSettings.MAX_PINNED_PLAYLISTS,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            browseActions = null
                        }
                    },
                    onDeleteDownload = target.downloadId?.let { id ->
                        {
                            browseActions = null
                            scope.launch { Downloads.deleteCollection(context, id) }
                        }
                    },
                )
            }
        }

        if (showLyricsSources) {
            BackHandler { showLyricsSources = false }
            LyricsSourcesDialog(
                hazeState = hazeState,
                onDismiss = { showLyricsSources = false },
            )
        }

        if (songSortMenuOpen) {
            BackHandler { songSortMenuOpen = false }
            FrostedSortMenu(
                hazeState = hazeState,
                selected = songSort,
                onSelect = { option ->
                    detail?.browseId?.let { AppSettings.setDetailSongSort(it, option) }
                    songSortMenuOpen = false
                },
                // Flipping the date direction deliberately leaves the menu up:
                // closing it here would cut the arrow's rotation animation off
                // before it played, and the open menu lets the direction flip
                // read against the list reordering behind the frost.
                onFlipDateDirection = {
                    detail?.browseId?.let { browseId ->
                        val next = when (songSort) {
                            SongSort.DATE_ADDED_DESC -> SongSort.DATE_ADDED_ASC
                            SongSort.DATE_ADDED_ASC -> SongSort.DATE_ADDED_DESC
                            else -> SongSort.DATE_ADDED_DESC
                        }
                        AppSettings.setDetailSongSort(browseId, next)
                    }
                },
                onDismiss = { songSortMenuOpen = false },
            )
        }

        if (showAppLanguage) {
            BackHandler { showAppLanguage = false }
            AppLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showAppLanguage = false },
            )
        }

        if (showTranslationLanguage) {
            BackHandler { showTranslationLanguage = false }
            TranslationLanguageDialog(
                hazeState = hazeState,
                onDismiss = { showTranslationLanguage = false },
            )
        }

    }
}

private fun tween(durationMillis: Int) =
    androidx.compose.animation.core.tween<Float>(durationMillis)

@Composable
private fun LibrarySort.localizedLabel(): String = when (this) {
    LibrarySort.DEFAULT -> stringResource(R.string.sort_default)
    LibrarySort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    LibrarySort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
}

/**
 * The track-list sort menu, styled after the account switcher: a full-screen
 * scrim to catch the dismissal tap, and the options on a frosted panel that
 * blurs the page behind it. Composed here in the main hierarchy rather than
 * as a popup window — which is exactly what lets the haze see the content it
 * is blurring.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun FrostedSortMenu(
    hazeState: HazeState,
    selected: SongSort,
    onSelect: (SongSort) -> Unit,
    onFlipDateDirection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = .48f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier
                .padding(top = 56.dp, end = 20.dp)
                .width(IntrinsicSize.Max)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(onClick = {}),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                // Date added is one row, Spotify-style: the arrow on it shows
                // the direction — up for newest first, down for oldest — and
                // tapping flips it, the rotation animating the flip. Up is
                // also where a fresh activation lands, newest first being the
                // point of the feature.
                val dateActive = selected == SongSort.DATE_ADDED_ASC ||
                    selected == SongSort.DATE_ADDED_DESC
                val arrowRotation by animateFloatAsState(
                    targetValue = if (selected == SongSort.DATE_ADDED_ASC) 180f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "dateAddedArrow",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onFlipDateDirection() }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.sort_date_added_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (dateActive) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.rotate(arrowRotation),
                        )
                    }
                }
                SongSort.entries
                    .filter { it != SongSort.DATE_ADDED_ASC && it != SongSort.DATE_ADDED_DESC }
                    .forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .clickable(role = Role.Button) { onSelect(option) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.localizedLabel(),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (option == selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun SongSort.localizedLabel(): String = when (this) {
    SongSort.DEFAULT -> stringResource(R.string.sort_default)
    SongSort.TITLE_ASC -> stringResource(R.string.sort_title_ascending)
    SongSort.TITLE_DESC -> stringResource(R.string.sort_title_descending)
    SongSort.DATE_ADDED_ASC -> stringResource(R.string.sort_date_added_oldest)
    SongSort.DATE_ADDED_DESC -> stringResource(R.string.sort_date_added)
}

/**
 * Whether this page id is one of the two device folders — `local:downloads` and
 * `local:all`, the tabbed Songs / Artists / Albums view.
 *
 * Asked rather than `startsWith("local:")` because that prefix now covers two
 * unlike pages: a folder, and one downloaded playlist, which is a plain track
 * listing under its own cover and wants the same chrome every other release page
 * gets. See [Downloads.PLAYLIST_PREFIX] for why they share a namespace at all.
 */
private fun String?.isDeviceFolder(): Boolean =
    this != null && startsWith("local:") && !startsWith(Downloads.PLAYLIST_PREFIX)

/** `M:SS`/`H:MM:SS`, the same shape [String?.durationMillis] parses back. */
private fun formatDurationText(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.ROOT, minutes, seconds)
}

/**
 * How far short of the end a seek is allowed to land.
 *
 * Seeking to the final millisecond is indistinguishable from the track running
 * out, so it starts the next song — which is not what anyone dragging to the end
 * of the bar, or tapping the last line of a lyric, is asking for. A second back
 * from the end plays the outro instead.
 */
private const val SEEK_END_GUARD_MS = 1_000L

/**
 * How far a detail page scrolls before its title moves up into the bar.
 *
 * Roughly the height of the sleeve and the credit stacked above the Play pair,
 * so the two titles hand over as the header one leaves rather than sitting on
 * screen together. The bar cross-fades over 220ms, which absorbs the difference
 * between that estimate and a particular page's real header.
 */
private val DETAIL_TITLE_DROP = 320.dp

private const val TAB_HOME = 0
private const val TAB_SEARCH = 1
private const val TAB_LIBRARY = 2

/**
 * What a tab's key is prefixed with in the content switcher above.
 *
 * The index is read back off it there rather than off `selectedTab`, so the
 * prefix has to be the one thing both the writing and the reading agree on.
 */
private const val TAB_KEY = "tab:"

/**
 * How much of a cold start the splash covers on its own.
 *
 * Long enough to sit over the library's first load -- the frames between the
 * window appearing and the first real content -- and short enough not to be
 * felt as a delay. ExtrumzSplash caps the whole thing independently.
 */
private const val COLD_START_COVER_MS = 1_200L
