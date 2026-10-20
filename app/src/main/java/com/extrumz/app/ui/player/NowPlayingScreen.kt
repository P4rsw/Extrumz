package com.extrumz.app.ui.player

import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.Constraints
import com.extrumz.app.playback.PlaybackPosition
import com.extrumz.app.R

import android.os.Build
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.extrumz.app.ui.theme.StatusBarIcons
import com.extrumz.app.ui.theme.rememberArtworkTopBandLuminance
import com.extrumz.app.ui.theme.topBandScrimAlpha
import com.extrumz.app.ui.LyricsProviderState
import com.extrumz.app.ui.components.AudioPipelineDialog
import com.extrumz.app.ui.haptics.Haptic
import com.extrumz.app.ui.haptics.rememberHaptics
import com.extrumz.app.ui.icons.ExtrumzIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.extrumz.app.data.lyrics.LyricLine
import com.extrumz.app.data.lyrics.LyricsSource
import com.extrumz.app.data.settings.AppSettings
import com.extrumz.app.data.settings.LastPlayerScreen
import com.extrumz.app.data.model.LikeStatus
import com.extrumz.app.data.model.PlaybackSourceType
import com.extrumz.app.data.model.PLAYER_ART_PX
import com.extrumz.app.data.model.Song
import com.extrumz.app.data.telegram.Covers
import com.extrumz.app.playback.BACK_RESTARTS_AFTER_MS
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue

/** Collapsed-header geometry, shared by the layout and its animation. */
/**
 * Comfortably over the sleeve's drawn size on a phone, without wasting bytes.
 *
 * A rung on the app-wide ladder rather than a number of the player's own, so a
 * large home-screen widget asks for the same copy — see [PLAYER_ART_PX].
 */
internal const val ART_PX = PLAYER_ART_PX

/**
 * How many further goes a cover that failed to load gets.
 *
 * Small on purpose. This is here for the connection that drops for a moment or
 * the request that loses a race with the app coming back to the foreground, not
 * for a track whose artwork has genuinely gone: past a few tries the answer is
 * not going to change, and the placeholder tile is the honest thing to draw.
 */
internal const val ART_RETRIES = 3

/** How long to leave it before trying a failed cover again. */
internal const val ART_RETRY_DELAY_MS = 1_500L

/**
 * How close the player's reported position has to get to a released scrub
 * handle before the handle stops being drawn where it was dropped. Wide enough
 * to swallow a coarse progress tick, tight enough that the handle doesn't hand
 * over while it is still visibly wrong.
 */
internal const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/**
 * How long that handle is held at the drop point regardless. A backstop, not a
 * schedule: a seek normally settles in a tick or two, and this only decides how
 * long a seek that never settles can freeze the bar for. Generous enough that a
 * slow buffer still hands over smoothly rather than snapping back.
 */
internal const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

private val THUMB_SIZE = 54.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp
/**
 * How long the sleeve takes to travel the whole way between the full player and
 * the queue's header.
 *
 * Spent in proportion rather than in full: a drag released four fifths of the
 * way up has a fifth of the journey left and gets a fifth of the time for it.
 * Only the toggle, which travels end to end, ever spends all of it.
 */
private const val QUEUE_TRAVEL_MS = 420
/**
 * How far up the sleeve has to have been dragged for a release to carry on
 * opening the queue rather than falling back, as a share of the sleeve's travel.
 *
 * Well under half, because the gesture is only ever *started* deliberately —
 * there is nothing else an upward drag on the artwork could have meant — so the
 * doubt a halfway line exists to settle isn't there.
 */
private const val QUEUE_CARRY_FRACTION = 0.3f
/**
 * How fast a release has to be moving, in pixels a second, to decide the queue
 * on its own and overrule [QUEUE_CARRY_FRACTION].
 *
 * A flick is a whole gesture in its own right: it says "open" without ever
 * asking the finger to travel, and the distance it covered is beside the point.
 */
private const val QUEUE_FLICK_VELOCITY = 450f
/**
 * The handle strip above the artwork, which always hands drags to the sheet.
 *
 * It isn't the only place that does — the artwork and the credits under it pass
 * theirs on as well, which is what makes the whole top of the player closable
 * rather than just its topmost 32dp. See the dismiss band in `NowPlayingScreen`.
 */
private val DISMISS_STRIP_HEIGHT = 32.dp
/** The breathing room above the sleeve, needed twice: once to apply, once to measure past. */
private val ART_BOX_TOP_PAD = 8.dp
/**
 * How far below the sleeve's top edge the video/audio pill floats.
 *
 * Far enough to clear the artwork's rounded corners, so the pill reads as
 * something laid on the cover rather than something clipped by it.
 */
private val VERSION_PILL_ART_INSET = 12.dp
/**
 * Share of the motion-artwork banner's height given over to its dissolve.
 *
 * Generous on purpose: the banner has no card edge to stop at, so anything
 * short enough to still be reading as artwork where it ends reads as a picture
 * that was cut off rather than one that ran out.
 */
private const val HERO_FADE_FRACTION = 0.42f
/** Kept transparent so the cover remains edge-to-edge, while aiding icon contrast. */
/** A modest floor while a subview replaces the hero with its artwork-derived mesh. */
private const val SUBVIEW_STATUS_SCRIM_MIN_ALPHA = 0.40f

/** The player's side margin. Scrollable panels reach back across it. */
internal val PLAYER_GUTTER = 30.dp
/**
 * How wide the player's content is ever allowed to get. A sleeve and a volume
 * slider stretched right across a tablet aren't a bigger player, just a coarser
 * one; past this the column stops growing and centres itself instead. Phones
 * are narrower than this, so for them it does nothing.
 */
internal val PLAYER_MAX_WIDTH = 560.dp
/**
 * The width from which the player counts as tablet-sized: its backdrop is
 * the full-cover blur in every state rather than the phone's seam-aware
 * mesh, and Settings keeps the full-bleed artwork switch listed — see
 * [fullBleedArtworkAvailable].
 *
 * 700dp is the figure the docked-player layout used to call "tablet sized"
 * (a 360dp page beside a 340dp pane). That layout is gone; the number stays,
 * so removing it moved no breakpoint.
 */
private val TABLET_PLAYER_MIN_WIDTH = 700.dp

/**
 * The least a landscape window has to offer before the player splits into its
 * two columns: enough that each half still holds what it is given — the sleeve
 * above the lyrics / output / queue row on the left, the credits and transport
 * on the right — with the bottom row's three-up capsule still fitting across
 * the narrower half.
 *
 * Low enough to take in a phone turned sideways, which is the point: a phone in
 * landscape and a tablet in landscape are the same shape, and they get the same
 * layout rather than the portrait player squashed into a window it was never
 * drawn for.
 */
private val LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp

/**
 * The artwork's own play/pause/scrub pose in the landscape layout. Three flat
 * scales and one priority rule: paused always wins outright over a scrub in
 * progress, rather than the two combining — there is one artwork, in one of
 * three settled poses, never a blend of two.
 */
private const val ARTWORK_EXPANDED_SCALE = 1f
private const val ARTWORK_PAUSE_SHRINK_SCALE = 0.88f
private const val ARTWORK_DRAG_SHRINK_SCALE = 0.94f

/**
 * The curve and duration those three poses move between — an ease-out cubic
 * over 500ms rather than a spring. A spring reads wrong for a press-and-release
 * gesture specifically: it visibly lags a quick scrub and keeps settling after
 * the finger has already lifted.
 *
 * The phone layout keeps its own bouncy spring ([artScale]) — it is answering a
 * different thing there, a sleeve that also collapses into a header, and the
 * bounce is the signature.
 */
private val ArtworkScaleEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
private const val ARTWORK_SCALE_DURATION_MS = 500

/**
 * How far a tall screen is allowed to push the transport from the blocks either
 * side of it.
 *
 * The spare height has to land somewhere, and above and below the play button is
 * where it reads as room rather than as a hole. Past this it stops reading as one
 * group of controls, so the rest goes back to the artwork block.
 */
private val CONTROL_GAP_SPREAD_MAX = 24.dp
/**
 * The spread [NowPlayingScreen] settled on the last time it was laid out.
 *
 * It follows from the window, so it is very nearly the same answer on every open
 * — and the player is torn down with its sheet, so without this the first frame
 * of each open would show the unspread gaps and then step to the real ones. Only
 * a head start: the frame after re-derives it either way. A plain var because
 * that is all it is, a cache of a measurement, not state anything observes.
 */
private var lastControlSpread: Dp = 0.dp

/**
 * Whether the full-bleed artwork switch is worth listing in Settings for a
 * window this wide. Public so the settings sheet can leave the switch out
 * entirely where it would do nothing.
 *
 * A window narrow enough that the player fills it is where the setting
 * acts: edge to edge there means the artwork *is* the screen. The tablet
 * half is left from the docked player, whose phone-width pane ran the
 * artwork edge to edge too; with the pane gone the portrait player no
 * longer goes full bleed at these widths, but the switch is still listed
 * there, unchanged, until that is decided on its own.
 */
fun fullBleedArtworkAvailable(windowWidth: Dp): Boolean =
    playerFillsWindow(windowWidth) || tabletSizedPlayer(windowWidth)

/**
 * Whether a player given the whole of a window this wide is still narrow enough
 * to run its artwork edge to edge.
 */
private fun playerFillsWindow(windowWidth: Dp): Boolean =
    windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

/**
 * Whether the player is tablet-sized — see [TABLET_PLAYER_MIN_WIDTH].
 *
 * [windowWidth] is the width of the *window*, measured rather than read off
 * `Configuration.screenWidthDp`: in a freeform or desktop window that can
 * report the display instead of the window, and it lands a beat late when
 * the window is dragged.
 */
private fun tabletSizedPlayer(windowWidth: Dp): Boolean =
    windowWidth >= TABLET_PLAYER_MIN_WIDTH

/**
 * Whether the player takes its landscape shape: the sleeve and the lyrics /
 * output / queue row in the left column, and the right column showing the
 * credits and transport, the lyrics or the queue — one of the three at a time.
 *
 * The same answer for a tablet and a phone on its side. Both are asked the
 * same question, the window's own proportions, rather than what kind of
 * device this is.
 *
 * Width alone isn't enough: a tablet held upright can be as wide as a phone
 * held sideways, and the two-column layout is a landscape shape, not a "wide
 * enough" one. Upright, every device gets the portrait player — a tall window
 * is the shape it was drawn for.
 */
fun landscapePlayerAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth > windowHeight && windowWidth >= LANDSCAPE_PLAYER_MIN_WIDTH

/** How long the player stands under the lyrics untouched before standing down. */
private const val LYRICS_CONTROLS_IDLE_MS = 5_000L
/** One shared travel time keeps the deck, credits and stats moving as a unit. */
private const val PLAYER_DECK_ANIMATION_MS = 420

/**
 * One lower-deck component, with one animation value owning both its pixels
 * and the space it occupies.
 *
 * A slide-only AnimatedVisibility kept the full height until its last frame;
 * combining slide and size transitions fixed that but let two transition
 * layers overlap when the presentation mode changed while returning to main.
 * This layout reports a fraction of the deck's real height instead. Since the
 * weighted player/panel above it consumes the remainder, the deck's top and
 * everything drawn from it move together. The subtree is removed only after
 * the exit reaches zero, retaining neither an invisible player nor a duplicate.
 */
@Composable
private fun SlidingPlayerDeck(
    visible: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            reveal.animateTo(
                1f,
                tween(
                    PLAYER_DECK_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            reveal.animateTo(
                0f,
                tween(
                    PLAYER_DECK_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
            mounted = false
        }
    }

    if (mounted) {
        Box(
            modifier = modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val animatedHeight = (placeable.height * reveal.value)
                    .roundToInt()
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                layout(placeable.width, animatedHeight) {
                    // No second translation: the weighted sibling above moves
                    // this component's origin as its reported height changes.
                    placeable.placeRelative(0, 0)
                }
            },
        ) {
            content()
        }
    }
}

/**
 * Apple Music's Now Playing, closely: artwork that shrinks when paused, a
 * hairline scrubber with elapsed / remaining either side, oversized transport
 * glyphs, a volume capsule flanked by speaker icons, and lyrics / AirPlay /
 * queue along the bottom.
 */
@Composable
@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
fun NowPlayingScreen(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    /**
     * The playhead, read only where it is drawn — never here. Passed as the
     * object rather than its value because a value read at this level is a
     * read in this function's own scope, and it ticks twice a second: the whole
     * player recomposed with it. See [PlaybackPosition].
     */
    position: PlaybackPosition,
    durationMs: Long,
    /** A version switch (video vs audio-only), triggered from the player's
     * menu, is fetching and measuring the target cut. */
    audioVersionSwitching: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    signedIn: Boolean,
    accountName: String?,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    /**
     * Seek to a fraction of the track, for the scrubber.
     *
     * Separate from [onSeek] because the scrubber is the one caller that knows
     * *where along the bar* it wants to go rather than a time. Converting that
     * here would use this screen's cached duration, which lags a track change by
     * however long the session takes to report the new one — long enough to drop
     * the handle on a bar still scaled to the previous song and seek to the
     * wrong fraction of the current one. The conversion belongs wherever the
     * freshest duration is.
     */
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    /** Return to the queue-level page named by the caption above the player. */
    onOpenPlaybackSource: () -> Unit,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsUnavailable: Boolean,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    /** The width of the window the player is in — see [fullBleedArtworkAvailable]. */
    windowWidth: Dp,
    /**
     * The window's height, alongside [windowWidth] — needed for exactly one
     * thing: telling a portrait window apart from a landscape one, in
     * [landscapePlayerAvailable]. Width alone can't; a big tablet's portrait
     * width comfortably clears a phone's landscape width.
     */
    windowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = rememberHaptics()

    // The cover every surface below reads from — one value rather than each
    // triggering its own lookup.
    // F8: the first time this track's player opens, the full-size cover
    // replaces the ~90px inline one — sleeves and the full-bleed banner draw
    // at PLAYER_ART_PX, far past upscale territory. The upgrade is written
    // back to the track's row, so every other surface sharpens from that one
    // fetch, and once the URL carries covers/hq_ no RPC is ever issued again.
    // Tier 4 on top: when what lands is STILL small — Telegram's own inline
    // file can be 40px, and hq_ merely means "the largest file the message
    // had" — upgradePlayerCover asks the external catalogues for their
    // ~1000px sleeve instead. See Covers.PLAYER_MIN_PX.
    var upgradedArt by remember(song.videoId) { mutableStateOf<String?>(null) }
    LaunchedEffect(song.videoId, song.thumbnailUrl) {
        upgradedArt = Covers.upgradePlayerCover(song.videoId, song.thumbnailUrl, song.title, song.artist)
    }
    val remoteArt = upgradedArt ?: song.thumbnailUrl

    // Whether that cover is too small for this screen's artwork regions,
    // measured off the local file (bounds-only — no pixels, no network).
    // Null until the measurement answers: the hero waits for a definite
    // "large" rather than flashing one stretched frame first, and a cover
    // that cannot be measured (remote, gone) answers false right after and
    // goes full-bleed exactly as it always did.
    var sleeveTooSmall by remember(remoteArt) { mutableStateOf<Boolean?>(null) }
    // The measured size itself, kept alongside the verdict: above the card
    // threshold a mid-size cover is still stretched past its own pixels
    // (full-bleed hero in portrait, the whole sleeve in landscape), and the
    // unsharp mask is gated off that, not off a second measurement.
    var coverPx by remember(remoteArt) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(remoteArt) {
        coverPx = Covers.localCoverSize(remoteArt)
        sleeveTooSmall = coverPx
            ?.let { (w, h) -> minOf(w, h) < Covers.PLAYER_MIN_PX }
            ?: false
    }
    // Under the card threshold, or under twice it while landing on a larger
    // region: both cases upscale, both go through the mask. A cover at region
    // size (tier-4's 1000 px, a big telegram file) pays nothing.
    val needsSharpen = sleeveTooSmall == true ||
        (coverPx?.let { (w, h) -> minOf(w, h) < Covers.PLAYER_MIN_PX * 2 } ?: false)
    // This is produced by the palette's existing 128 px decode and cache. It
    // samples the upper band rather than the whole sleeve because that is what
    // lies beneath the status bar when the player expands to full bleed.
    val artTopLuminance = rememberArtworkTopBandLuminance(remoteArt, ART_PX)
    val artworkStatusScrimAlpha = topBandScrimAlpha(artTopLuminance)

    // Media-player convention: the player always owns light status icons. The
    // top treatment below, rather than a window-flag flip per cover, provides
    // their contrast and stays visually stable through artwork transitions.
    StatusBarIcons(dark = false)

    // Kept local to the player: a modal player is not in the page's Haze
    // source tree, so it needs its own source for the same frosted material as
    // the bottom navigation pill.
    val playerHaze = remember { HazeState() }
    var showAudioPipeline by remember { mutableStateOf(false) }
    var showAudioOutput by remember { mutableStateOf(false) }
    var showLyricsProviders by remember { mutableStateOf(false) }
    // Gated on the Bluetooth permission the first time — see [rememberOutputPicker].
    val openAudioOutput = rememberOutputPicker { showAudioOutput = true }
    val syncedLyricsEnabled by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsOffsetMs by AppSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    // A lambda, not a value: read by the lyric strip and panel in scopes of
    // their own, so a tick recomposes them and not the player around them.
    val lyricsPosition: () -> Long = { adjustedLyricsPosition(position.positionMs, lyricsOffsetMs) }
    val seekToLyric: (Long) -> Unit = { lineTimeMs ->
        onSeek(adjustedLyricsSeekTarget(lineTimeMs, lyricsOffsetMs))
    }
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()

    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    // Read by the scrubber itself — see [PlayerScrubber.shown].
    val shown: () -> Float = { scrub.shown(position.positionMs, durationMs) }
    // Back restarts the track rather than stepping back once it is a few
    // seconds in. Derived, so this scope hears about the playhead once, when
    // it crosses that point, rather than on every tick.
    val pastRestartPoint by remember(position) {
        derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS }
    }
    // The queue lives inside the player, Apple-style, rather than in a sheet.
    // Read once when this expanded-player instance is created. Every change is
    // written below, so reopening the player (and a process restart) returns to
    // the same surface on phones and tablets without making this UI state a
    // continuously collected setting.
    val restoredPlayerScreen = remember { AppSettings.lastPlayerScreen.value }
    var queueOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.QUEUE)
    }
    var lyricsOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.LYRICS)
    }
    val queueSlide = remember { mutableFloatStateOf(0f) }
    // Expensive, song-scoped preparation is deliberately staggered. The small
    // backdrop decode runs after the track hand-off has settled; lyrics and
    // queue are then composed offscreen on separate beats rather than all three
    // competing with the song change. Opening a panel early bypasses its wait.
    var playerPrewarmStage by remember(song.videoId) { mutableIntStateOf(0) }
    LaunchedEffect(song.videoId) {
        delay(600)
        playerPrewarmStage = 1 // 128px backdrop decode + CPU blur
        delay(650)
        playerPrewarmStage = 2 // lyrics layout
        delay(650)
        playerPrewarmStage = 3 // queue layout and initial scroll
    }
    // Whether the lyrics or queue list is actively mid-scroll. The player's own
    // swipe gestures — skip-by-drag and the dismiss band — are suppressed for
    // as long as either is true, so a scroll that grazes past a list's edge
    // can never be misread as a drag meant for the player underneath it. Reset
    // whenever the owning panel closes, since a list scrolled mid-transition
    // out never gets a matching "stopped scrolling" event of its own.
    var lyricsScrolling by remember { mutableStateOf(false) }
    var queueScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(lyricsOpen) { if (!lyricsOpen) lyricsScrolling = false }
    LaunchedEffect(queueOpen) { if (!queueOpen) queueScrolling = false }
    val panelScrolling = lyricsScrolling || queueScrolling
    var lyricsControlsOpen by remember {
        mutableStateOf(restoredPlayerScreen == LastPlayerScreen.LYRICS)
    }
    var queueControlsOpen by remember { mutableStateOf(true) }
    // Shared with Spotify's title placement below. The deck and credits must
    // read one clock: the weighted region changes size as this value moves, so
    // independently animating the title towards that moving target makes it
    // trail behind and overlap the deck on the way back in.
    val playerDeckReveal = remember { Animatable(1f) }
    LaunchedEffect(lyricsOpen, queueOpen) {
        AppSettings.setLastPlayerScreen(
            when {
                lyricsOpen -> LastPlayerScreen.LYRICS
                queueOpen -> LastPlayerScreen.QUEUE
                else -> LastPlayerScreen.MAIN
            },
        )
        // A fresh visit always starts with the half player present. Scrolling
        // the queue can then dismiss it without this effect firing again.
        if (queueOpen) queueControlsOpen = true
    }
    // Change the panel and its controls in the same snapshot. Driving the
    // controls from a LaunchedEffect left one composed frame where lyrics were
    // open but the half-player was not, so every trip into lyrics briefly
    // started an exit animation and reversed it on the following frame.
    val openLyrics: () -> Unit = {
        lyricsControlsOpen = true
        lyricsOpen = true
        queueOpen = false
    }
    val closeLyrics: () -> Unit = {
        lyricsControlsOpen = false
        lyricsOpen = false
    }
    val toggleLyrics: () -> Unit = {
        if (lyricsOpen) closeLyrics() else openLyrics()
    }
    val toggleQueue: () -> Unit = {
        val opening = !queueOpen
        if (opening && lyricsOpen) queueSlide.floatValue = 1f
        queueOpen = opening
        if (opening) closeLyrics()
    }
    val lyricsLoadingLines = stringArrayResource(R.array.lyrics_loading_lines)
    val lyricsLoadingText = remember(song.videoId) { lyricsLoadingLines.random() }
    val lyricsTranslation = rememberLyricsTranslation(
        trackId = song.videoId,
        lyrics = lyrics,
        lyricsSource = lyricsSource,
        lyricsUnavailable = lyricsUnavailable,
        loadingText = lyricsLoadingText,
        haptics = haptics,
    )
    // Nothing here resets [lyricsOpen] on a track change, deliberately. The
    // panel is a place, not a property of the track: someone reading along who
    // skips — or who simply lets the queue run on — means to carry on reading,
    // so the words change underneath them and the panel stays. Closing it
    // dropped them back onto the artwork every few minutes with no gesture of
    // their own behind it.
    // Lyrics are meant to be read continuously, so hold off the device's normal
    // screen timeout — but only while the panel is actually up. Closing it hands
    // the screen back, and the system starts its own timeout from that moment
    // rather than from whenever the panel was opened.
    //
    // Keyed to [lyricsOpen] rather than written from a SideEffect on every
    // recomposition: this is a piece of state on the window, not a per-frame
    // value, and the panel now outlives a track change (see above), so there is
    // no longer a whole-player recomposition standing behind it as a backstop.
    val playerView = LocalView.current
    DisposableEffect(playerView, lyricsOpen) {
        playerView.keepScreenOn = lyricsOpen
        onDispose { playerView.keepScreenOn = false }
    }

    // Back out of the lyrics panel to the player, and only from the player
    // itself out to the mini player.
    //
    // See [PlayerBackHandler] for why this needs more than a BackHandler.
    // Controls being visible must not insert an extra navigation level. Back
    // always leaves lyrics in one step, whether it starts over the lyrics list
    // or over the half-player at the bottom.
    PlayerBackHandler(enabled = lyricsOpen, onBack = closeLyrics)

    // Back out of the queue to the player.
    PlayerBackHandler(enabled = queueOpen) { queueOpen = false }

    // Registered ahead of the pipeline dialog's own handler below: the
    // pipeline is now only ever opened from the row at the bottom of this
    // drawer, so it is always the topmost of the two when both are up, and
    // back has to close it first rather than taking the drawer out from
    // under it.
    PlayerBackHandler(enabled = showAudioOutput) { showAudioOutput = false }

    PlayerBackHandler(enabled = showLyricsProviders) { showLyricsProviders = false }

    PlayerBackHandler(enabled = showAudioPipeline) { showAudioPipeline = false }

    PlayerBackHandler(enabled = lyricsOffsetOpen, onBack = onDismissLyricsOffset)

    // 0 = full sleeve, 1 = queue. Everything that moves reads off this.
    //
    // Plain state driven by an animation rather than [animateFloatAsState],
    // because it has two drivers and only one of them is an animation: the
    // toggle at the foot of the player, which travels end to end, and a finger
    // dragging the sleeve upward, which sets it outright. An animation keyed on
    // [queueOpen] cannot be pushed around mid-flight by a drag — and a drag that
    // could only move the *target* would have nothing to show for itself until
    // it was released, then jump from wherever the animation had got to.
    //
    // Read only inside layout and draw lambdas and the thresholds below, never
    // as a value here — see [p].
    // Whether a finger is on the sleeve right now. Parks the settle below rather
    // than leaving the two to write the same value on alternate frames.
    var queueDragging by remember { mutableStateOf(false) }
    // Bumped when a drag hands the value back, so the settle runs again even
    // though [queueOpen] may not have moved: a swipe that gave up short of
    // [QUEUE_CARRY_FRACTION] has to fall back to 0 just as surely as one that
    // carried has to finish reaching 1.
    var queueReleased by remember { mutableIntStateOf(0) }
    LaunchedEffect(queueOpen, queueDragging, queueReleased) {
        if (queueDragging) return@LaunchedEffect
        val target = if (queueOpen) 1f else 0f
        val from = queueSlide.floatValue
        if (from == target) return@LaunchedEffect
        animate(
            initialValue = from,
            targetValue = target,
            animationSpec = tween(
                durationMillis = (QUEUE_TRAVEL_MS * abs(target - from)).roundToInt(),
                easing = FastOutSlowInEasing,
            ),
        ) { value, _ -> queueSlide.floatValue = value }
    }

    // Horizontal fling anywhere on the player skips tracks; the artwork
    // follows the finger so the gesture has something to hold on to.
    val swipeThreshold = with(density) { 72.dp.toPx() }
    // Where the finger has dragged the sleeve to, and the sleeve springing
    // after it. What animateFloatAsState does inside — a conflated channel of
    // targets, each chased from the current velocity — but fed straight from
    // the gesture: as state read here, every pointer move recomposed the whole
    // player just to hand the spring a new target, when the only readers are
    // draw-phase layers.
    val swipeSettle = remember { Animatable(0f) }
    val swipeTargets = remember { Channel<Float>(Channel.CONFLATED) }
    LaunchedEffect(swipeTargets) {
        for (target in swipeTargets) {
            val newest = swipeTargets.tryReceive().getOrNull() ?: target
            launch {
                if (newest != swipeSettle.targetValue) {
                    swipeSettle.animateTo(newest, spring(stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
    }
    val setSwipeOffset: (Float) -> Unit = { swipeTargets.trySend(it) }
    // The skip hint under the sleeve only needs composing while there is a
    // drag to hint at, and which way it points; its fade is drawn, not composed.
    val swipeHintShown by remember(swipeThreshold) {
        derivedStateOf { abs(swipeSettle.value) / swipeThreshold > 0.01f }
    }
    val swipeHintNext by remember { derivedStateOf { swipeSettle.value > 0f } }

    // Signature Apple Music touch: the sleeve shrinks back while paused.
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "artScale",
    )

    val volume = rememberPlayerVolume()
    // Present when you arrive, out of the way once you are actually reading.
    //
    // The panel opens with the player under it so the scrubber and transport
    // are there to be reached, and this is the other half of that bargain: left
    // alone for [LYRICS_CONTROLS_IDLE_MS] it stands down and gives the words the
    // whole screen. Scrolling up brings it back and restarts the wait, since
    // that write to [lyricsControlsOpen] re-keys this effect.
    //
    // Never while a finger is on the scrubber or the volume bar: those are the
    // two controls that are *being used* while nothing else on screen moves,
    // and timing out underneath them would take the thing away mid-gesture.
    LaunchedEffect(lyricsOpen, lyricsControlsOpen, scrub.scrubbing, volume.dragging) {
        if (lyricsOpen && lyricsControlsOpen && !scrub.scrubbing && !volume.dragging) {
            delay(LYRICS_CONTROLS_IDLE_MS)
            lyricsControlsOpen = false
        }
    }
    // 0 = the ordinary square sleeve, 1 = the artwork as a full-bleed banner.
    // Both states collapse the header, but the banner only ever shows over a
    // settled player: opening the queue or the lyrics hands the sleeve back its
    // card first.
    // How collapsed the sleeve is, whichever surface asked for it.
    //
    // This used to read `if (lyricsOpen) 1f else queueProgress`, which gave the
    // queue a 420ms ease and the lyrics nothing at all: opening them snapped
    // the sleeve to a thumbnail in a single frame while [heroT] — reading off
    // this same value — went on fading the banner out over the full 420. One
    // half of the artwork jumped, the other half glided after it, and the pair
    // read as a stutter rather than as either. One animation, both surfaces.
    val animatedCollapse = animateFloatAsState(
        targetValue = if (lyricsOpen || queueOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "sleeveCollapse",
    )
    // A queue tap/drag already owns a 420ms progress value. Reusing it avoids
    // driving this large layout from two independent animations on every frame.
    // Lyrics retain the ordinary collapse animation; switching queue -> lyrics
    // stays at one because its target never changed.
    //
    // A function rather than a value. It moves on every frame of a 420ms
    // collapse and of a finger dragging the queue, and read here as a value it
    // recomposed this whole function on each of those frames — the sleeve, the
    // credits, both panels and the controls, just to move a few of them. Layout
    // and draw lambdas call it where they place or paint; composition only asks
    // the thresholds below, each of which changes once per collapse.
    val p: () -> Float = {
        val queueOwnsCollapse = !lyricsOpen &&
            (queueOpen || queueDragging || queueSlide.floatValue > 0.001f)
        if (queueOwnsCollapse) queueSlide.floatValue else animatedCollapse.value
    }
    val collapseAtRest by remember { derivedStateOf { p() == 0f } }
    val collapsePastSettling by remember { derivedStateOf { p() >= 0.01f } }
    val collapsePastHalf by remember { derivedStateOf { p() >= 0.5f } }
    val collapseAlmostDone by remember { derivedStateOf { p() >= 0.999f } }
    val collapseDone by remember { derivedStateOf { p() >= 1f } }
    val queueShowing by remember { derivedStateOf { queueSlide.floatValue > 0.01f } }

    // Whether the sleeve has finished getting out of the way, and how far the
    // panel that replaces it has faded up since.
    //
    // The lyric sheet and the queue list are the two most expensive things this
    // screen can compose — measuring every line of a song, or building a lazy
    // list with drag-reorder state per row — and both used to be composed on
    // the frame the panel was asked for, which is the frame the 420ms collapse
    // above starts on. That put the single heaviest composition of the whole
    // screen directly on top of the one animation the eye is following, and it
    // read as the open stuttering.
    //
    // Held back until [p] has actually arrived, the expensive frame lands while
    // nothing is moving, where a dropped frame costs nothing to look at, and
    // the panel then fades up on its own short curve. The open is a little
    // longer end to end and visibly smoother for it.
    //
    // Declared out here rather than beside either panel on purpose: an
    // [animateFloatAsState] created at the moment its target becomes true is
    // created *at* that target and has nothing left to animate. Living above
    // both panels, this one is already at 0 when they mount.
    val panelsSettled = collapseDone
    val panelFade by animateFloatAsState(
        targetValue = if (panelsSettled) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "panelFade",
    )
    val fullBleedArt by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    // Full-bleed is a phone idiom. What the width has to rule out is a player
    // running a foot wider than the column of controls under it — edge to edge
    // meaning "a picture, and separately some controls" rather than "the
    // artwork *is* the player".
    //
    val heroMode = fullBleedArt && playerFillsWindow(windowWidth)

    // Whether there's a still image to blow out — a placeholder tile is a card
    // or it is nothing, and going full-bleed with one would just tint the top
    // third of the screen.
    //
    // Keyed on the artwork rather than on the track, because that is what it
    // actually describes and because only Coil can set it back to true. Two
    // tracks off one album share a cover, so skipping between them leaves the
    // request below byte-identical: the painter keeps the Success it already
    // had and never re-emits, so the `onState` that is the sole writer here
    // never fires again. Keyed on the track this reset to false and stayed
    // there, which pinned the sleeve fully opaque (see the alpha it feeds) on
    // top of an equally opaque banner — the same cover drawn twice, card and
    // full-bleed at once. Keyed on the cover there is nothing to reset: the
    // bitmap really is still loaded, so the state stays true and the two
    // layers go on trading places as they should.
    val art = rememberPlayerArtwork(remoteArt)
    // Sticky, unlike [PlayerArtwork.loaded]: the banner is the shape of the player rather
    // than a property of the track in it. Waiting on each new cover would
    // collapse the banner into a card and blow it back out on every skip —
    // twice the length of the whole screen's worth of movement for a change the
    // artwork itself already announces. The frame stays; the cover arrives in
    // it, fading in as Coil fades in everywhere else.
    //
    // Latched off the clip as well as the still art, for a cover that never
    // arrives at all and leaves the banner standing on the clip alone: the clip
    // gives its frame up and takes it back every time the app leaves the screen,
    // and a banner that answered only to that would collapse behind the user's
    // back and blow itself out again in front of them on the way in.
    var heroSettled by remember { mutableStateOf(false) }
    // Success from the banner's own painter, rather than from the separate
    // sleeve painter. Sharing one ImageRequest lets Coil share its cached
    // bitmap, but it does not make two AsyncImage painters enter Success in
    // the same frame. The sleeve must not hand over to a banner which is still
    // empty just because its own painter finished first.
    var heroArtLoaded by remember(art.url, art.attempt, heroMode) { mutableStateOf(false) }
    LaunchedEffect(art.loaded) {
        if (art.loaded) heroSettled = true
    }

    // The backdrop's colours, taken off the artwork's own arrangement rather
    // than quantised out of it — see [ArtworkMesh].
    val artMesh = key(song.videoId) { rememberArtworkMesh(remoteArt, ART_PX) }
    // Whether the banner is the presentation at all: full-bleed is on, and there
    // is something to blow out. The collapse is deliberately *not* part of this
    // — see [heroVisible].
    val heroT = animateFloatAsState(
        targetValue = if (
            heroMode && (art.loaded || heroSettled)
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "hero",
    )

    /**
     * How much of the banner is actually on screen: its own fade, dissolved by
     * the collapse rather than after it.
     *
     * The collapse used to be a threshold on this animation's *target* — the
     * banner was told to go once [p] passed a half. That chained two 420ms
     * animations end to end when they should have been the same one: for the
     * first half of the collapse the banner sat at full size and full opacity
     * with nothing appearing to move, since the card shrinking behind it is
     * transparent while the banner is up; then the card finished collapsing and
     * a full-screen banner cross-dissolved into a finished thumbnail. Two sizes
     * of the same artwork on screen at once, which is what made every trip in
     * and out of the lyrics look wrong.
     *
     * Multiplied by the collapse instead, the banner goes as the card shrinks:
     * one movement, and the card is fading in the whole way down.
     */
    val heroVisible: () -> Float = { heroT.value * (1f - p()) }
    val heroShowing by remember { derivedStateOf { heroVisible() > 0.001f } }
    // How tall that banner is, worked out down in the layout where the sleeve's
    // own geometry is known. Zero until the first measure, which is fine: there
    // is nothing to show that early either.
    var heroHeight by remember { mutableStateOf(0.dp) }
    var playerBounds by remember { mutableStateOf(IntSize.Zero) }
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // What sits between the status bar and the artwork: the drag strip. Read
    // in three places — the strip itself,
    // the scrim drawn over it and the banner's own height — which all have to
    // agree or the artwork and the credits under it move.
    val topStrip = DISMISS_STRIP_HEIGHT

    // The band of the player a vertical drag belongs to rather than to whatever
    // is under it: from the top of the artwork to the bottom of the credits, in
    // root coordinates. Everything in between is one block — the full sleeve
    // with the title and artist beneath it — and a drag on it closes the player
    // downwards and opens the queue upwards.
    //
    // Read off the layout rather than recomputed, so it stays the block's own
    // shape whatever the screen: a height-bound sleeve on a tablet, a full-bleed
    // banner on a phone.
    //
    // Only ever the *expanded* block, though. Once a panel is up the band is not
    // this pair at all but the header, worked out from the state instead — see
    // the gesture below. The two edges do travel with the sleeve as it collapses,
    // which reads like the band could simply follow them the whole way, and that
    // is exactly what went wrong: the sleeve takes [QUEUE_TRAVEL_MS] to get
    // there, and for that whole half second the queue was already listed and
    // scrollable underneath a band still lying across it. A drag on a row came
    // out as the player closing.
    //
    // Bare numbers rather than a rect: the band runs the full width of the
    // player either way, and on a height-bound sleeve the bare backdrop down
    // each side of it should close the player too — it is part of the same
    // gesture, and a hole there would be a strip the finger mysteriously
    // slides off.
    //
    // Both start at zero, which is a band with no height and so no hole at all
    // until the first layout pass. There is nothing on screen to drag then
    // either.
    var dismissBandTop by remember { mutableFloatStateOf(0f) }
    var dismissBandBottom by remember { mutableFloatStateOf(0f) }
    // The suppressing Column's own coordinates, to put a pointer's local
    // position into the same space as the two edges above.
    var dismissBandSpace by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Where the caption above the credits leads: the queue itself for a
    // queue-built session, and otherwise back to the album or playlist the
    // session was started from.
    val openPlaybackOrigin: () -> Unit = {
        if (song.playbackSourceType == PlaybackSourceType.QUEUE) {
            queueOpen = true
            closeLyrics()
        } else {
            onOpenPlaybackSource()
        }
    }

    // Horizontal fling skips tracks. The portrait player hangs it on the whole
    // screen, the landscape one on the sleeve alone — the right column there is
    // full of horizontal sliders and a lyric list that should not be one stray
    // sideways drag away from changing the song.
    val skipSwipeGesture = Modifier.pointerInput(showAudioPipeline, panelScrolling) {
        if (showAudioPipeline || panelScrolling) return@pointerInput
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { setSwipeOffset(0f) },
            onDragEnd = {
                // The same two buzzes the transport glyphs give, so swiping the
                // sleeve and tapping skip feel like one gesture with two
                // spellings.
                when {
                    total <= -swipeThreshold -> {
                        haptics.play(Haptic.SkipNext)
                        onNext()
                    }
                    total >= swipeThreshold -> {
                        haptics.play(Haptic.SkipPrevious)
                        onPrevious()
                    }
                }
                setSwipeOffset(0f)
            },
            onHorizontalDrag = { _, delta ->
                total += delta
                // Damped: it's a hint, not a drag-to-position.
                setSwipeOffset(total * 0.35f)
            },
        )
    }

    // The scrubber's two halves, shared by both layouts so a drop point is held
    // the same way in each — see [PlayerScrub.pendingSeek].
    val onScrub: (Float) -> Unit = scrub::drag
    val onScrubFinished: () -> Unit = {
        // On release only. Ticking the whole way along the bar turns a scrub
        // into a rattle, and the beat that matters is the one that says where
        // the playhead landed.
        haptics.play(Haptic.Select)
        scrub.release(onSeekFraction)
    }
    val onVolumeChange: (Float) -> Unit = volume::drag
    val onVolumeChangeFinished: () -> Unit = volume::release

    // Lyrics, output and queue, with the output name under them — the row both
    // layouts end on, and the only thing in the landscape player's left column
    // besides the sleeve.
    val playerActions: @Composable () -> Unit = {
        PlayerActionRow(
            lyricsOpen = lyricsOpen,
            queueOpen = queueOpen,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            autoplayEnabled = autoplayEnabled,
            onToggleLyrics = toggleLyrics,
            onToggleQueue = toggleQueue,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            onToggleAutoplay = onToggleAutoplay,
            onOpenOutput = openAudioOutput,
        )
        // Keep the current output caption visible in every state.
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            OutputCaption(
                accountName = accountName,
                onOpenOutput = openAudioOutput,
            )
        }
    }

    // The drawers and dialogs the player raises over itself. Mounted by
    // whichever layout is on screen — they are overlays over the player, not
    // part of either shape of it.
    val playerOverlays: @Composable () -> Unit = {
        if (showAudioOutput) {
            AudioOutputSheet(
                hazeState = playerHaze,
                accountName = accountName,
                onDismiss = { showAudioOutput = false },
                onOpenPipeline = { showAudioPipeline = true },
            )
        }
        if (showLyricsProviders) {
            LyricsProviderSheet(
                hazeState = playerHaze,
                currentSource = lyricsSource,
                states = lyricsProviderStates,
                onSelect = onSelectLyricsProvider,
                onDismiss = { showLyricsProviders = false },
            )
        }
        if (showAudioPipeline) {
            AudioPipelineDialog(
                hazeState = playerHaze,
                isPlaying = isPlaying,
                onDismiss = { showAudioPipeline = false },
            )
        }
        if (lyricsOffsetOpen) {
            LyricsOffsetSheet(
                hazeState = playerHaze,
                onDismiss = onDismissLyricsOffset,
            )
        }
    }

    // A landscape window — a tablet, or a phone on its side — takes an entirely
    // different shape from the portrait player below: two columns rather than
    // one, see [landscapePlayerAvailable] and [LandscapePlayerLayout].
    //
    // A separate branch rather than something woven into the layout below:
    // the portrait player's collapsing sleeve, hero banner and vertical drag
    // gesture exist to let one tall column be the player, the lyrics and the
    // queue in turn, and in landscape none of them has anything to do — the
    // sleeve never has to get out of anything's way.
    val landscape = landscapePlayerAvailable(windowWidth, windowHeight)
    // Tablet-sized players use the full-cover blur in all three states. On a
    // phone only the lyrics and queue replace the main player's seam-aware,
    // reflected mesh; the main player deliberately keeps its existing look.
    val tabletArtworkBackdrop = tabletSizedPlayer(windowWidth)
    // Owned by the song-level player composition, not by any one screen state.
    // Opening lyrics/queue, and rotating into or out of landscape, therefore
    // reuse the same tiny pre-blurred bitmap instead of running a screen-sized
    // effect.
    val fullArtworkBlurImage = rememberFullArtworkBlurImage(
        imageUrl = remoteArt,
        artPx = ART_PX,
        prepare = landscape || tabletArtworkBackdrop || lyricsOpen || queueOpen ||
            playerPrewarmStage >= 1,
    )
    // One movable Image node, not two backdrop call sites. Compose carries it
    // between the portrait and landscape players; only the cached bitmap
    // changes when the artwork preparation above completes for another song.
    val currentFullArtworkBlurImage = rememberUpdatedState(fullArtworkBlurImage)
    val fullArtworkBlurContent = remember {
        movableContentOf<Modifier> { backdropModifier ->
            FullArtworkBlurBackdrop(
                image = currentFullArtworkBlurImage.value,
                modifier = backdropModifier,
            )
        }
    }

    if (landscape) {
        // Paused always wins outright over a scrub in progress — see
        // [ARTWORK_PAUSE_SHRINK_SCALE].
        val landscapeArtScale by animateFloatAsState(
            targetValue = when {
                !isPlaying -> ARTWORK_PAUSE_SHRINK_SCALE
                scrub.scrubbing -> ARTWORK_DRAG_SHRINK_SCALE
                else -> ARTWORK_EXPANDED_SCALE
            },
            animationSpec = tween(durationMillis = ARTWORK_SCALE_DURATION_MS, easing = ArtworkScaleEasing),
            label = "landscapeArtworkScale",
        )
        val versionAligning by AppSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
        val panelOpen = lyricsOpen || queueOpen

        Box(modifier = modifier.fillMaxSize()) {
            LandscapePlayerLayout(
                pane = when {
                    lyricsOpen -> PlayerPane.Lyrics
                    queueOpen -> PlayerPane.Queue
                    else -> PlayerPane.Main
                },
                background = { backgroundModifier ->
                    // The whole screen is the sheets' frost source: there is
                    // no full-bleed banner here to be it instead.
                    fullArtworkBlurContent(backgroundModifier.hazeSource(playerHaze))
                },
                artwork = { artworkModifier ->
                    LandscapeArtwork(
                        artRequest = art.request,
                        artLoaded = art.loaded,
                        onArtState = art::onState,
                        isPlaying = isPlaying,
                        cardSize = if (sleeveTooSmall == true) smallArtCardSize() else null,
                        sharpen = needsSharpen,
                        modifier = artworkModifier
                            .then(skipSwipeGesture)
                            .graphicsLayer {
                                scaleX = landscapeArtScale
                                scaleY = landscapeArtScale
                                translationX = swipeSettle.value
                            }
                            // With a panel up the sleeve is the way back to
                            // the player, as the portrait thumbnail is.
                            .clickable(
                                enabled = panelOpen,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                queueOpen = false
                                closeLyrics()
                            },
                    ) {
                        // The stats belong to the player, not to the sleeve, so
                        // they leave it along with the rest of the player.
                        SleeveNerdStats(
                            song = song,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .graphicsLayer { alpha = if (panelOpen) 0f else 1f },
                        )
                    }
                },
                actions = playerActions,
                mainPane = { compact ->
                    LandscapeMainPane(
                        compact = compact,
                        caption = if (hideSongStatus) null else playbackOriginText(song),
                        onOpenCaption = openPlaybackOrigin,
                        credits = {
                            LandscapeCredits(
                                song = song,
                                signedIn = signedIn,
                                likeStatus = likeStatus,
                                onToggleLike = onToggleLike,
                                onOpenMenu = onOpenMenu,
                                onOpenAlbum = onOpenAlbum,
                                onOpenArtist = onOpenArtist,
                            )
                        },
                        lyricStrip = if (syncedLyricsEnabled) {
                            {
                                CurrentLyricStrip(
                                    lines = lyricsTranslation.displayedLyrics,
                                    trackKey = song.videoId,
                                    positionMs = lyricsPosition,
                                    isPlaying = isPlaying,
                                    durationMs = durationMs,
                                    lyricsUnavailable = lyricsUnavailable,
                                    loadingText = lyricsLoadingText,
                                    onClick = openLyrics,
                                )
                            }
                        } else {
                            null
                        },
                        scrubber = {
                            PlayerScrubber(
                                shown = shown,
                                durationMs = durationMs,
                                loading = versionAligning || audioVersionSwitching,
                                onScrub = onScrub,
                                onScrubFinished = onScrubFinished,
                            ) {
                                PlaybackQualityLabel(
                                    song = song,
                                    isLoading = isLoading,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 8.dp),
                                )
                            }
                        },
                        transport = {
                            TransportRow(
                                isPlaying = isPlaying,
                                isLoading = isLoading || audioVersionSwitching,
                                previousEnabled = hasPrevious || pastRestartPoint,
                                nextEnabled = hasNext,
                                onPrevious = onPrevious,
                                onPlayPause = onPlayPause,
                                onNext = onNext,
                                compact = compact,
                            )
                        },
                        volume = if (hideVolumeBar) {
                            null
                        } else {
                            {
                                VolumeRow(
                                    value = { volume.level.value },
                                    onValueChange = onVolumeChange,
                                    onValueChangeFinished = onVolumeChangeFinished,
                                )
                            }
                        },
                    )
                },
                lyricsPane = {
                    LandscapeLyricsPane(
                        hasLyrics = lyricsTranslation.displayedLyrics.isNotEmpty(),
                        placeholder = if (lyricsUnavailable) {
                            stringResource(R.string.lyrics_not_available)
                        } else {
                            lyricsLoadingText
                        },
                        status = lyricsTranslation.status,
                        onChangeProvider = { showLyricsProviders = true },
                        romanizationToggle = {
                            RomanizationToggleButton(
                                state = lyricsTranslation.romanizationState,
                                showingRomanization = lyricsTranslation.showingRomanization,
                                enabled = !lyrics.isNullOrEmpty(),
                                onClick = lyricsTranslation.toggleRomanization,
                            )
                        },
                        translationToggle = {
                            TranslationToggleButton(
                                state = lyricsTranslation.translationState,
                                showingTranslation = lyricsTranslation.showingTranslation,
                                enabled = !lyrics.isNullOrEmpty(),
                                onClick = lyricsTranslation.toggleTranslation,
                            )
                        },
                    ) { panelModifier ->
                        LyricsTranslationMotion(
                            trigger = lyricsTranslation.transition,
                            reduceMotion = lyricsTranslation.reduceMotion,
                            modifier = panelModifier,
                        ) { particleProgress ->
                            // [controlsOpen] is a constant `true`: that flag
                            // exists because the portrait player hides its
                            // transport behind the lyrics and needs a tap to
                            // bring it back. Here there is nothing hidden for
                            // a tap to reveal, and leaving the reveal gesture
                            // armed would only eat taps meant for the lines.
                            PlaybackPositionScope(lyricsPosition) { lyricsPositionMs ->
                                LyricsPanel(
                                    lines = lyrics.orEmpty(),
                                    subLines = lyricsTranslation.subLines,
                                    trackKey = song.videoId,
                                    positionMs = lyricsPositionMs,
                                    looking = !lyricsUnavailable,
                                    isPlaying = isPlaying,
                                    onSeekToLine = seekToLyric,
                                    controlsOpen = true,
                                    onRevealControls = {},
                                    onHideControls = {},
                                    translationProgress = particleProgress,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                },
                queuePane = {
                    InlineQueue(
                        queue = queue,
                        currentIndex = queueIndex,
                        autoplayEnabled = autoplayEnabled,
                        onJumpTo = onJumpTo,
                        onRemove = onRemoveFromQueue,
                        onMove = onMoveInQueue,
                        onClear = onClearQueue,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            playerOverlays()
        }
        return
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { playerBounds = it }.background(Color.Black)) {
        // Anchored to the sleeve's bottom edge, so the screen carries on in the
        // colours the artwork ended in rather than in a quantiser's idea of what
        // the artwork was about. Position ticks recompose this screen twice a
        // second and must not drag a full-screen blur along with them, which is
        // why the mesh is passed as one immutable value.
        //
        // The seam is the *expanded* banner's bottom edge and is left there as
        // the player collapses, rather than following the sleeve down: it is
        // the anchor for a blurred layer, and moving it would re-blur the whole
        // screen on every frame of the drag. Above it the mesh holds one colour,
        // so a seam left behind a collapsed sleeve shows nothing at all.
        // Both phone backgrounds stay mounted for the entire song. Only these
        // retained layers' alpha changes when a panel opens, so the full-cover
        // blur is neither rebuilt nor switched in on a hard frame boundary.
        // The tablet has no mirrored main-player treatment to crossfade from.
        val fullArtworkBackdropAlpha by animateFloatAsState(
            targetValue = if (
                (tabletArtworkBackdrop || lyricsOpen || queueOpen) &&
                (tabletArtworkBackdrop || fullArtworkBlurImage != null)
            ) 1f else 0f,
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            label = "fullArtworkBackdropCrossfade",
        )
        if (!tabletArtworkBackdrop) {
            ArtworkMeshBackdrop(
                mesh = artMesh,
                seam = if (heroMode) heroHeight else 0.dp,
                modifier = Modifier.graphicsLayer { alpha = 1f - fullArtworkBackdropAlpha },
            )
        }
        fullArtworkBlurContent(
            Modifier.graphicsLayer { alpha = fullArtworkBackdropAlpha },
        )

        // The artwork, edge to edge and running up behind the status bar,
        // dissolving into the backdrop where the sleeve's bottom edge would
        // have been. It lives out here rather than in the sleeve because that
        // is the only way to escape the player's side gutter and its status-bar
        // inset — a banner that stops short of either reads as a misplaced card
        // rather than as the artwork the screen is made of.
        if (heroHeight > 0.dp) {
            // The still sleeve first, so a clip fading in on top of it never
            // shows the backdrop through the gap between them — and only until
            // that fade has run. Both layers carry the same bottom gradient, so
            // a still frame left lit under a settled clip is not hidden by it:
            // down in the fade the clip is only part-opaque, and what shows
            // through it there is the cover art rather than the backdrop. That
            // is the artwork and the clip on screen at once.
            //
            // So it is dropped outright once the clip is opaque, rather than
            // held at alpha 0: nothing under a full-bleed clip is ever visible,
            // and a full-screen AsyncImage kept mounted for no one is a bitmap
            // and a layer the compositor still has to carry.
            //
            // Kept mounted through the handover in either direction rather than
            // dropped the moment [p] crosses the collapse threshold: the sleeve
            // behind it is still transparent at that point, so pulling the
            // banner straight out leaves a frame or two with no artwork anywhere
            // on screen before the card catches up.
            // A sleeve too small for this region (sleeveTooSmall) does not
            // get the full-bleed treatment: stretched across it, its pixels
            // WOULD be the blur this state exists to fix. The compact sleeve
            // below takes over instead, as a small framed card over the same
            // backdrop (see [smallArtCardSize]), and this layer simply stays
            // unmounted until a cover big enough arrives (tier 4 rewrites
            // remoteArt, which re-runs the measurement). A cover that stays
            // full-bleed but is still smaller than this region (mid-size
            // telegram files, tier-3 sleeves tier 4 could not improve) gets
            // the unsharp mask instead of the card.
            if (heroMode && sleeveTooSmall == false &&
                (!collapsePastHalf || heroShowing)
            ) {
                // Dropping this painter (while a panel is open) also drops
                // the proof that this particular destination can draw. If it
                // is mounted again, keep the sleeve visible until the new
                // painter reports Success.
                DisposableEffect(art.request) {
                    onDispose { heroArtLoaded = false }
                }
                AsyncImage(
                    // Decoded at the same size the sleeve asks for, so the two
                    // share one entry in Coil's cache and one bitmap: the pair
                    // cross-fade into each other, and asking twice at two sizes
                    // would decode the same art twice and let the banner fade in
                    // before its own copy had arrived.
                    //
                    // Literally the same request object as the sleeve's, not an
                    // identical one — see [PlayerArtwork.request] for why that distinction
                    // is the whole of it.
                    model = art.request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { heroArtLoaded = it is AsyncImagePainter.State.Success },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(heroHeight)
                        // A region larger than the source it is handed: the
                        // mask over the upscale, not instead of it.
                        .sharpenUpscale(needsSharpen)
                        // Haze must observe the drawable layer itself. A
                        // source on the surrounding layout only captured its
                        // mesh backdrop, leaving the cover sharp in the pill.
                        .hazeSource(playerHaze)
                        .graphicsLayer {
                            alpha = heroVisible()
                            // The mask below erases part of what this layer
                            // drew, which it can only do in a buffer of its own.
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startY = size.height * (1f - HERO_FADE_FRACTION),
                                    endY = size.height,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                )
            }

        }

        // This transparent top gradient is always present while the modal
        // player owns the system bar. Its opacity follows the actual top-band
        // artwork, rather than changing the status-bar glyph colour per cover.
        // A subview replaces that hero with an artwork-derived mesh, so it gets
        // only a modest floor rather than an opaque status-bar surface.
        val playerSubviewOpen = lyricsOpen || queueOpen || lyricsOffsetOpen ||
            showAudioPipeline || showAudioOutput || showLyricsProviders
        val topGradientAlpha = if (playerSubviewOpen) {
            maxOf(artworkStatusScrimAlpha, SUBVIEW_STATUS_SCRIM_MIN_ALPHA)
        } else {
            artworkStatusScrimAlpha
        }

        val steps = 8
        val gradientColors = remember(topGradientAlpha) {
            List(steps) { index ->
                val progress = index / (steps - 1).toFloat()
                val factor = (1f - progress).toDouble().pow(1.5).toFloat()
                Color.Black.copy(alpha = topGradientAlpha * factor)
            }
        }
        val topScrimBrush = remember(gradientColors) {
            Brush.verticalGradient(gradientColors)
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .height(statusBarTop + topStrip)
                .background(topScrimBrush)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .then(skipSwipeGesture),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The only strip that passes drags through to the sheet, so the
            // player closes from the handle and the space around it — not from
            // a stray downward swipe on the artwork or the controls.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topStrip),
                contentAlignment = Alignment.Center,
            ) {
                // While the origin caption is present the handle belongs at
                // the top of the strip. As lyrics or the queue replace the
                // album-cover player, it glides into the now-empty strip's
                // vertical centre alongside the caption's fade.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset {
                            IntOffset(
                                x = 0,
                                y = lerp(
                                    6.dp,
                                    (topStrip - 5.dp).coerceAtLeast(0.dp) / 2,
                                    p(),
                                ).roundToPx(),
                            )
                        }
                        .width(38.dp)
                        .height(5.dp)
                        .shadow(2.dp, RoundedCornerShape(3.dp), clip = false)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.70f)),
                )
                // [p] is the shared album-to-panel transition. Keeping this in
                // composition until its final frame gives the caption a real
                // fade on both entry and exit, but removes its click target
                // entirely once lyrics or the queue owns the player.
                if (!hideSongStatus && !collapseAlmostDone) {
                    PlaybackOriginCaption(
                        text = playbackOriginText(song),
                        onClick = openPlaybackOrigin,
                        textAlign = TextAlign.Center,
                        contentPadding = PaddingValues(start = PLAYER_GUTTER, end = PLAYER_GUTTER, bottom = 1.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer { alpha = 1f - p() },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Swallow vertical drags before the sheet can read them as
                    // "dismiss me". Children that scroll consume first, so the
                    // lists are unaffected. This sits outside the side padding
                    // on purpose: inside it, the two gutters were left as bare
                    // sheet, and a swipe that strayed into one closed the whole
                    // player instead of scrolling the lyrics or the queue.
                    //
                    // With one hole in it, and where that hole is depends on
                    // which screen of the player is up:
                    //
                    //  * The main player — the artwork-and-credits block. Down is
                    //    left unconsumed for the sheet to dismiss with, so the
                    //    player closes from the picture as well as from the
                    //    handle; up is taken here and drags the queue in.
                    //  * The queue or the lyrics — the header those panels sit
                    //    below, and nothing else. Down closes the player, up does
                    //    nothing: there is no sleeve left to pull away from.
                    //
                    // The header is worked out from the state rather than read
                    // off the sleeve, which is the whole point of doing it here:
                    // the sleeve is still on its way for [QUEUE_TRAVEL_MS] after
                    // the queue opens, and a hole that waited for it spent that
                    // half second lying across a list the finger was already
                    // scrolling.
                    .onGloballyPositioned { dismissBandSpace = it }
                    .pointerInput(showAudioPipeline, panelScrolling) {
                        if (showAudioPipeline || panelScrolling) return@pointerInput
                        awaitEachGesture {
                            // Unconsumed on purpose, as the blanket version was:
                            // the collapsed sleeve's own clickable — the way back
                            // out of the queue — has taken the press by the time
                            // an ancestor sees it.
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val space = dismissBandSpace
                            val y = space?.localToRoot(down.position)?.y
                                ?: down.position.y
                            // A panel is up from the moment it is asked for to
                            // the moment the sleeve has finished growing back —
                            // never mind where the sleeve is in between.
                            val panelUp = queueOpen || lyricsOpen ||
                                queueSlide.floatValue > 0.01f
                            val bandTop: Float
                            val bandBottom: Float
                            if (panelUp) {
                                bandTop = space?.positionInRoot()?.y ?: 0f
                                bandBottom = bandTop +
                                    (ART_BOX_TOP_PAD + HEADER_HEIGHT).toPx()
                            } else {
                                bandTop = dismissBandTop
                                bandBottom = dismissBandBottom
                            }
                            if (y >= bandTop && y <= bandBottom) {
                                if (!panelUp) {
                                    dragQueueIn(
                                        down = down,
                                        travel = bandBottom - bandTop -
                                            HEADER_HEIGHT.toPx(),
                                        slide = queueSlide,
                                        onHold = { queueDragging = it },
                                        onSettle = { open ->
                                            if (open != queueOpen) {
                                                haptics.play(
                                                    if (open) Haptic.Expand else Haptic.Tap,
                                                )
                                                queueOpen = open
                                            }
                                            queueReleased++
                                        },
                                    )
                                }
                                return@awaitEachGesture
                            }
                            // A down already taken means the sheet grabbed it
                            // while "settling" after a scroll (see
                            // guardSheetFromContentTouches), and the guard is
                            // about to hand it back to the list under the
                            // finger. Holding it here would strand that list
                            // mid-handback, and the list would never scroll.
                            if (down.isConsumed) return@awaitEachGesture
                            // What detectVerticalDragGestures does, minus the
                            // callbacks: cross the slop, then hold the gesture
                            // to the end so nothing downstream of the first
                            // event reaches the sheet either.
                            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (drag != null) verticalDrag(drag.id) { it.consume() }
                        }
                    }
                    .padding(horizontal = PLAYER_GUTTER),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // ---- Top and centre: artwork, then the credits ----
            // Everything that changes between the artwork and the queue lives
            // in this one weighted box, so the controls below it never move.
            // The loading state the scrubber wears for the length of a version
            // switch — see [ThinSlider.loading]. The flag is the alignment
            // half (fetch + measure); [audioVersionSwitching] is the resolve.
            // Both named here, so there is no frame of the switch with nothing
            // on screen saying the player is still working.
            val versionAligning by AppSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
            val versionSwitching = versionAligning || audioVersionSwitching
            // Height the artwork block below turns out not to need, spent by the
            // controls at the foot of the screen. Filled in from inside the box,
            // where the sleeve's real size is known; see [lastControlSpread].
            var controlSpread by remember { mutableStateOf(lastControlSpread) }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = PLAYER_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(top = ART_BOX_TOP_PAD, bottom = 18.dp),
            ) {
                // The height this box would have if the controls at the foot of
                // the screen were at their natural size. They aren't: they are
                // holding [controlSpread] of extra gap, which came out of here,
                // so adding it back cancels the only thing down there that
                // depends on what is decided up here.
                //
                // The sleeve and the slack below are both worked out from this
                // rather than from the box as it actually stands, and that is
                // what keeps the hand-off from creeping. Measured off the real
                // height, granting the gaps 20dp came back as a box 20dp
                // shorter and read as a *further* 20dp going spare — so any
                // moment the controls were briefly shorter than usual (a track
                // change, where the lyric strip drops back to its loading line,
                // or coming back from the lyrics panel, where the strip is
                // rebuilt from scratch) was pocketed for good. The gaps
                // ratcheted open a little at a time and the sleeve paid for it.
                val roomy = maxHeight + controlSpread
                // The sleeve is square, so it is bounded by whichever of the
                // two axes runs out first: the player's width on a phone, or —
                // on a tablet, where there is width to spare — the height left
                // over once the credits row and the gap above it have had
                // theirs. Sizing it off the width alone is what pushed the
                // credits down across the scrubber on anything but a phone.
                val wantArt = minOf(maxWidth, roomy - ART_TITLE_GAP - HEADER_HEIGHT)
                // Held to what the box has actually got, for the single frame it
                // takes the gaps below to catch up with a change in their own
                // height: a sleeve a few dp under for one frame is a better
                // failure than a credits row overhanging the lyric strip.
                val fullArt = minOf(wantArt, maxHeight - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(THUMB_SIZE)
                // What's left over once the sleeve, the gap and the credits have
                // had theirs. A few dp on a phone; the better part of a
                // centimetre on anything taller, and since the group is centred,
                // half of it used to land between the credits and the lyric strip
                // as one wide hole in the middle of the controls.
                val slack = (roomy - wantArt - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(0.dp)
                // Handed to the two gaps around the transport row instead, which
                // is where a tall screen should be doing its breathing.
                //
                // Assigned, not added to: [slack] is stated in terms the spread
                // cannot move, so this is the whole answer in one step, and it
                // gives the room back just as readily when the controls grow
                // into it again.
                //
                // The settled spread is retained while either panel is up. It is
                // part of the controls' footprint, not part of the artwork, and
                // removing it only for lyrics made the half-player jump shorter
                // at the exact moment the sleeve started collapsing.
                //
                // Granted in whole even pixels, and only when it actually moves.
                // This is a measurement feeding the layout it was measured from,
                // and [roomy] cancels that by adding the grant back — but only if
                // this pass's [maxHeight] already reflects the grant about to be
                // written, which needs the Column above to have re-measured the
                // controls at that grant already. It doesn't always have: on some
                // aspect ratios
                // the cancellation lands a pass late, the grant overshoots, the
                // next pass corrects past it the other way, and the two chase
                // each other through the same handful of values forever instead
                // of settling — a full-amplitude standing oscillation, not the
                // single-pixel shiver this rounding alone was built to absorb.
                // See [granted] below for the fix.
                // Do not feed transitional artwork measurements back into the controls.
                // The settled player's spread is retained throughout the return animation.
                if (!lyricsOpen && collapseAtRest) {
                    val target = with(density) {
                        val half = slack
                            .coerceAtMost(CONTROL_GAP_SPREAD_MAX * 2)
                            .toPx()
                            .div(2f)
                            .roundToInt()
                        (half * 2).toDp()
                    }
                    // Stepped towards [target] rather than jumped there in one
                    // grant, so a late cancellation (see above) decays instead of
                    // standing: still one pass to settle when the cancellation
                    // does land on time, and a fast-converging approach rather
                    // than a full-amplitude swing on the passes where it doesn't.
                    val granted = with(density) {
                        val steppedPx = (controlSpread.toPx() +
                            (target.toPx() - controlSpread.toPx()) * 0.4f)
                            .roundToInt()
                        steppedPx.toDp()
                    }
                    if (granted != controlSpread) {
                        SideEffect {
                            controlSpread = granted
                            lastControlSpread = granted
                        }
                    }
                }
                // Artwork and the title row travel together as one block, so
                // the pair sits centred while the queue is closed — in whatever
                // the controls couldn't take, which on all but the tallest
                // screens is nothing.
                val groupTop = (maxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(0.dp) / 2
                // Functions of the collapse, called at placement and measure
                // — see [p]. Composition never reads them.
                fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
                fun artTop(): Dp = lerp(groupTop, 0.dp, p())
                // Expanded and height-bound, the sleeve is narrower than the
                // player and has to be centred in it; collapsed, it belongs
                // hard against the left edge with the credits beside it.
                fun artStart(): Dp = lerp((maxWidth - fullArt) / 2, 0.dp, p())
                fun regularTitleTop(): Dp = lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, p())
                fun currentTitleTop(): Dp = regularTitleTop()
                fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p())

                // How far down the *screen* the sleeve's bottom edge sits, which
                // is where the full-bleed banner has to stop for the credits
                // below it not to move when it appears. Everything between the
                // screen's top and this box's own top is fixed padding, so it
                // can simply be added back up rather than measured.
                val bannerBottom = statusBarTop + topStrip + ART_BOX_TOP_PAD +
                    groupTop + fullArt + ART_TITLE_GAP / 2
                // Frozen while lyrics are up. The controls now retain their full
                // footprint across the transition, so this answer is identical
                // on both sides; avoiding writes during the panel keeps the
                // backdrop independent of its animation. The first pass is
                // exempt so a player composed with lyrics already open still
                // receives an anchor.
                //
                // Guarded, like the spread above: this runs on every pass, and a
                // state write from inside a layout is a recomposition asked for
                // from inside a layout. Writing the same answer back costs a
                // comparison here and a whole frame if it is left to the snapshot
                // to notice.
                val bannerSettled = !lyricsOpen || heroHeight == 0.dp
                if (bannerSettled && bannerBottom != heroHeight) {
                    SideEffect { heroHeight = bannerBottom }
                }

                // Empty state lives on this Box, not the AsyncImage: a
                // background *and* a painter both trying to fill the same
                // clipped shape is what read as two overlapping squares
                // whenever there was nothing to paint. One layer, one square.
                // [PlayerArtwork.loaded] is hoisted to the screen, where the banner needs
                // it too.
                Box(
                    modifier = Modifier
                        // The lambda overload deliberately: the Dp one reads
                        // its arguments at composition, so an animated offset
                        // recomposes and re-measures this Box — cover, clip and
                        // all — once per frame. Read at placement instead, the
                        // same movement costs a placement pass.
                        .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                        // What `.size(artSize)` measured, asked at measure
                        // time instead of composition.
                        .layout { measurable, constraints ->
                            val side = artSize().roundToPx()
                            val placeable = measurable.measure(
                                constraints.constrain(Constraints.fixed(side, side)),
                            )
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        }
                        // Where the dismiss band starts. Read here, above the
                        // paused shrink below, so the band covers the sleeve's
                        // slot rather than the 86% of it that is drawn while
                        // paused — the ring of backdrop the shrink opens up is
                        // still the artwork as far as a finger is concerned, and
                        // a band that breathed with the shrink would hand it
                        // back and forth on every play and pause.
                        .onGloballyPositioned { dismissBandTop = it.boundsInRoot().top }
                        .graphicsLayer {
                            // The paused shrink and the swipe nudge only make
                            // sense on the full sleeve.
                            val collapse = p()
                            val idle = artScale + (1f - artScale) * collapse
                            scaleX = idle
                            scaleY = idle
                            translationX = swipeSettle.value * (1f - collapse)
                        }
                        // Collapsed, the sleeve is the way back: tapping the
                        // thumbnail puts the queue or the lyrics away again.
                        .then(
                            if (queueOpen || lyricsOpen) {
                                Modifier.clickable {
                                    queueOpen = false
                                    closeLyrics()
                                }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // The sleeve proper. Separated from the box around it so
                    // the banner can dissolve the card — shadow, corners, tile
                    // and all — without taking the stats line with it.
                    //
                    // Held fully opaque until the destination banner has
                    // artwork of its own,
                    // regardless of [heroT]: the banner is sticky across skips
                    // by design (see [heroSettled]), but its content is not — a
                    // new track's cover has to come from somewhere while the
                    // banner waits on Coil or the clip's first frame, and the
                    // sleeve underneath, with its loading icon, is that
                    // somewhere. Once either destination source catches up,
                    // hiding the sleeve behind the banner is invisible.
                    // [PlayerArtwork.loaded] alone is not enough: the sleeve and banner
                    // use separate painters, and the banner can still be empty
                    // for a frame after the sleeve reports Success.
                    // A cover too small for the sleeve (sleeveTooSmall) loses
                    // the full-size frame — that grey square would BE the
                    // artwork — and takes a small framed card instead: the
                    // same tile, corners and shadow every other cover wears,
                    // at [smallArtCardSize], centred on the backdrop. One
                    // design decision in both orientations; the source's
                    // provenance (a telegram file or a tier-4 file) never
                    // enters it, only the pixel size does.
                    val framedSleeve = sleeveTooSmall != true
                    val smallCard = sleeveTooSmall == true
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // The compact sleeve is the source while full
                            // bleed artwork is off.
                            .hazeSource(playerHaze)
                            .graphicsLayer {
                                alpha = if (heroArtLoaded) {
                                    1f - heroVisible()
                                } else {
                                    1f
                                }
                            }
                            .then(
                                if (framedSleeve) Modifier
                                    // A drop shadow grounds a photo; on the flat
                                    // placeholder tile it has nothing to sit behind, so
                                    // it just reads as a second, darker square ringing
                                    // the first. Only cast it once there's actually art.
                                    .shadow(
                                        if (art.loaded) 10.dp else 0.dp,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.18f))
                                else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Art and placeholder share one box so the
                        // card's corners, shadow and size apply to all three
                        // at once: the frame hugs the picture, not the
                        // region. For a full-size cover this box is just the
                        // region and the frame stays out on the box above.
                        Box(
                            modifier = Modifier
                                .then(
                                    if (smallCard) {
                                        Modifier
                                            .size(smallArtCardSize())
                                            .shadow(if (art.loaded) 10.dp else 0.dp, RoundedCornerShape(8.dp))
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black.copy(alpha = 0.18f))
                                    } else {
                                        Modifier.fillMaxSize()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                        if (!art.loaded) {
                            Icon(
                                imageVector = ExtrumzIcons.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .size(40.dp)
                                    .graphicsLayer {
                                        val scale = 1f - 0.5f * p()
                                        scaleX = scale
                                        scaleY = scale
                                    },
                            )
                        }
                        AsyncImage(
                            // Decode at the sleeve's *expanded* size, always.
                            // Coil otherwise sizes the decode to however large
                            // this is when the request goes out — and changing
                            // track from the queue does that while the sleeve is
                            // collapsed to a thumbnail, leaving a thumbnail-sized
                            // bitmap to be blown back up when the queue closes.
                            // Skipping tracks with the transport keeps it sharp
                            // only because the sleeve happens to be full size at
                            // that moment.
                            //
                            // Asked for at the source's own size rather than the
                            // sleeve's: it is the same request the full-bleed
                            // banner makes, and the banner is taller than the
                            // sleeve is wide. One ask, one decode, one bitmap for
                            // both — and nothing to upscale when the two swap.
                            model = art.request,
                            contentDescription = null,
                            // Video thumbnails are 16:9; letterboxing them inside
                            // the square sleeve looks like a broken frame.
                            //
                            // Crop in both states: the card is square and the
                            // source is square, so nothing gets cut. The card
                            // is where a small cover stops being a smear; the
                            // unsharp mask below is what stops it being soft.
                            contentScale = ContentScale.Crop,
                            onState = art::onState,
                            modifier = Modifier
                                .fillMaxSize()
                                .sharpenUpscale(needsSharpen),
                        )

                        }
                    }

                    // Measured stats stay on the sleeve's bottom centre. They
                    // fade away with the collapse rather than following the
                    // compact credits to the bottom edge.
                    if (!collapsePastHalf) {
                        SleeveNerdStats(
                            song = song,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .graphicsLayer {
                                    alpha = 1f - p() * 2f
                                },
                        )
                    }
                }

                // Sits in the gap under the sleeve, clear of its rounded
                // corners and shadow — no box, no clip, nothing for the art
                // itself to be cropped by. Just a glyph that fades in with
                // the drag to hint which way a release would skip.
                //
                // Shown under the banner as well as under the card, and it is
                // the only feedback the drag has there: a card can slide with
                // the finger, but a full-bleed image sliding would open a strip
                // of bare backdrop down one edge of the screen. It lands where
                // the banner has all but dissolved, so it reads against the
                // backdrop rather than against the artwork.
                if (swipeHintShown) {
                    val showNext = swipeHintNext
                    val enabled = if (showNext) hasNext else hasPrevious
                    val hintAlpha = if (enabled) 0.85f else 0.3f
                    Icon(
                        imageVector = if (showNext) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset {
                                IntOffset(0, (artTop() + artSize() + (ART_TITLE_GAP - 16.dp) / 2).roundToPx())
                            }
                            .size(16.dp)
                            .graphicsLayer {
                                alpha = (abs(swipeSettle.value) / swipeThreshold)
                                    .coerceIn(0f, 1f) * (1f - p()) * hintAlpha
                            },
                    )
                }

                // ---- Title + menu ----
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Collapsed, this row shares the header with the sleeve
                        // rather than sitting under it, and the two are not the
                        // same height — centring the credits in the taller of
                        // the two boxes left them riding low against the
                        // artwork they belong to. Only as it collapses: opened
                        // out, the row is below the sleeve and owns its band.
                        // Read the animated value during placement. The Dp
                        // overload would recompose and remeasure this whole
                        // weighted player region on every animation frame.
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (
                                    currentTitleTop() -
                                        lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, p())
                                ).roundToPx(),
                            )
                        }
                        // What `.padding(start = titleStart)` laid out, asked at
                        // measure time instead of composition.
                        .layout { measurable, constraints ->
                            val start = titleStart().roundToPx()
                            val placeable = measurable.measure(constraints.offset(horizontal = -start))
                            layout(
                                constraints.constrainWidth(placeable.width + start),
                                constraints.constrainHeight(placeable.height),
                            ) { placeable.placeRelative(start, 0) }
                        }
                        .height(HEADER_HEIGHT)
                        // Where the dismiss band ends — see its top on the
                        // artwork above. Taken from the row rather than added up
                        // from the sleeve so the gap between the two is inside
                        // the band as well: it is a gap in one block, not a seam
                        // between two, and a finger should not be able to find it.
                        .onGloballyPositioned { dismissBandBottom = it.boundsInRoot().bottom },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            // Scaling the already measured type is a draw-only
                            // operation. Animating fontSize forced both marquee
                            // texts through shaping and measurement every frame.
                            .graphicsLayer {
                                val scale = 1f - 0.2f * p()
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            },
                    ) {
                        // Only the title's own overflow gates the artist's stagger
                        // below — an artist line that's long on its own has no
                        // reason to wait on a title that already fits.
                        var titleOverflowing by remember { mutableStateOf(false) }
                        // Only while these credits are the screen. Collapsed into
                        // a header over the queue or the lyrics they are a label
                        // on a list, and a label that crawls pulls the eye off
                        // whatever is being read below it.
                        val scrolls = !collapsePastSettling
                        // Targeted on the words rather than the videoId: two
                        // cuts that share a title cross-fade into the exact
                        // same text, which is nothing at all and leaves the
                        // marquee where it was; a cut that renames the song
                        // ("… (Live)") dissolves into the new one instead of
                        // snapping while the rest of the switch moves around
                        // it.
                        Crossfade(
                            targetState = song.title to song.artist,
                            animationSpec = tween(durationMillis = 300),
                            label = "playerCredits",
                        ) {
                            Column {
                                MarqueeText(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = 20.sp,
                                    ),
                                    color = Color.White,
                                    enabled = scrolls,
                                    leading = if (song.isExplicit == true) {
                                        { ExplicitBadge(color = Color.White) }
                                    } else {
                                        null
                                    },
                                    onOverflowChange = { titleOverflowing = it },
                                    // Only the tracks YouTube hands us a browse id for
                                    // lead anywhere; the rest stay plain text.
                                    modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
                                )
                                MarqueeText(
                                    text = song.artist,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.W500,
                                        fontSize = 20.sp,
                                    ),
                                    color = Color.White.copy(alpha = 0.55f),
                                    enabled = scrolls,
                                    // A title that's also scrolling gets to go first —
                                    // starting together reads as clutter, so the artist
                                    // waits a beat before it joins in.
                                    startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                                    modifier = Modifier.opensPage(song.artistId, onOpenArtist),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // Beside the credits rather than down in the toggle row:
                    // liking is about *this song*, and the row below is about
                    // how the queue plays. Guests get nothing to tap, since
                    // there's no account to record it against — and neither
                    // does a local file or a finished download, which carries
                    // no YouTube identity to rate.
                    if (signedIn && song.localUri == null) {
                        val liked = likeStatus == LikeStatus.LIKE
                        CircleGlyph(
                            icon = if (liked) ExtrumzIcons.HeartFilled else ExtrumzIcons.Heart,
                            contentDescription = stringResource(
                                if (liked) R.string.remove_from_liked else R.string.like,
                            ),
                            onClick = onToggleLike,
                            active = liked,
                            haptic = if (liked) Haptic.ToggleOff else Haptic.ToggleOn,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    CircleGlyph(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = stringResource(R.string.more),
                        onClick = onOpenMenu,
                    )
                }

                val lyricsPanelVisible = lyricsOpen && panelsSettled
                if (lyricsPanelVisible || playerPrewarmStage >= 2) {
                        LyricsTranslationMotion(
                            trigger = lyricsTranslation.transition,
                            reduceMotion = lyricsTranslation.reduceMotion,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = HEADER_HEIGHT)
                                // Keep the prepared list measured but completely
                                // outside hit-testing/drawing until it is needed.
                                .offset {
                                    if (lyricsPanelVisible) IntOffset.Zero else IntOffset(100_000, 0)
                                }
                                // Arrives after the sleeve has finished collapsing
                                // into the header rather than during — see
                                // [panelsSettled]. Fading lyrics in over a sleeve
                                // still mid-collapse doubled the same movement in
                                // two places on screen at once, and composing them
                                // there was what made the collapse stutter.
                                .graphicsLayer { alpha = if (lyricsPanelVisible) panelFade else 0f },
                        ) { particleProgress ->
                            PlaybackPositionScope(lyricsPosition) { lyricsPositionMs ->
                                LyricsPanel(
                                    lines = lyrics.orEmpty(),
                                    subLines = lyricsTranslation.subLines,
                                    trackKey = song.videoId,
                                    positionMs = lyricsPositionMs,
                                    looking = !lyricsUnavailable,
                                    isPlaying = isPlaying,
                                    active = lyricsPanelVisible,
                                    onSeekToLine = seekToLyric,
                                    controlsOpen = lyricsControlsOpen,
                                    onRevealControls = { lyricsControlsOpen = true },
                                    onHideControls = { lyricsControlsOpen = false },
                                    translationProgress = particleProgress,
                                    onScrollingChange = { lyricsScrolling = it },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                    // Floated over the foot of the lyrics rather than placed in
                    // the controls below them. In the controls it was a row of
                    // layout like any other, and the bottom block is measured at
                    // its natural height — so the button's 34dp came straight
                    // off the panel above it and the lyrics lost a line. Drawn
                    // here it costs the panel nothing and still reads as sitting
                    // on top of the half player, because that is where it is.
                    //
                    // Arrives and leaves on the controls' own fade: the panel is
                    // for reading, and a control parked over the words when
                    // nobody asked for the controls is one more thing between
                    // the reader and them.
                    val translateShown = lyricsPanelVisible && lyricsControlsOpen
                    val translateFade by animateFloatAsState(
                        targetValue = if (translateShown) 1f else 0f,
                        animationSpec = tween(if (translateShown) 220 else 160),
                        label = "translateFade",
                    )
                    if (translateFade > 0.01f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .graphicsLayer { alpha = translateFade },
                        ) {
                            RomanizationToggleButton(
                                state = lyricsTranslation.romanizationState,
                                showingRomanization = lyricsTranslation.showingRomanization,
                                enabled = translateShown && !lyrics.isNullOrEmpty(),
                                onClick = lyricsTranslation.toggleRomanization,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .graphicsLayer { alpha = translateFade },
                        ) {
                            TranslationToggleButton(
                                state = lyricsTranslation.translationState,
                                showingTranslation = lyricsTranslation.showingTranslation,
                                // Not tappable on the way out: a disc at 20%
                                // opacity is on its way to gone, not a target.
                                enabled = translateShown && !lyrics.isNullOrEmpty(),
                                onClick = lyricsTranslation.toggleTranslation,
                            )
                        }
                    }
                }

                // Toggles and the queue arrive after the sleeve has finished
                // travelling, and leave before it starts coming back.
                // Held back until the sleeve has settled, exactly as the lyric
                // sheet above is — except while a finger is actually dragging
                // the queue in. A drag is direct manipulation: the queue has to
                // be under the finger the whole way for the gesture to mean
                // anything, and the person doing it is setting the pace, so
                // there is no animation of ours for the composition to trip up.
                val queuePanelVisible = !lyricsOpen &&
                    (queueDragging || (queueShowing && panelsSettled))
                if (queuePanelVisible || playerPrewarmStage >= 3) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = HEADER_HEIGHT)
                            .offset {
                                if (queuePanelVisible) IntOffset.Zero else IntOffset(100_000, 0)
                            }
                            .graphicsLayer {
                                alpha = if (!queuePanelVisible) {
                                    0f
                                } else if (queueDragging) {
                                    ((queueSlide.floatValue - 0.45f) / 0.55f).coerceIn(0f, 1f)
                                } else {
                                    panelFade
                                }
                                translationY = if (queuePanelVisible) {
                                    (1f - queueSlide.floatValue) * 26.dp.toPx()
                                } else {
                                    0f
                                }
                            },
                    ) {
                        InlineQueue(
                            queue = queue,
                            currentIndex = queueIndex,
                            autoplayEnabled = autoplayEnabled,
                            onJumpTo = onJumpTo,
                            onRemove = onRemoveFromQueue,
                            onMove = onMoveInQueue,
                            onClear = onClearQueue,
                            onScrollingChange = { queueScrolling = it },
                            collapsePlayerOnScroll = true,
                            onRevealPlayer = { queueControlsOpen = true },
                            onHidePlayer = { queueControlsOpen = false },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- Bottom: lyric strip, scrubber, transport, volume, toggles ----
            // One block, measured at its natural height and pinned to the foot
            // of the player. Whatever is left over above it is the artwork's,
            // which is what keeps this row of controls in the same place on
            // every screen instead of being shoved off the bottom of a tall one.
            SlidingPlayerDeck(
                visible = (!lyricsOpen || lyricsControlsOpen) &&
                    (!queueOpen || queueControlsOpen),
                reveal = playerDeckReveal,
            ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Column(
                modifier = Modifier
                    .widthIn(max = PLAYER_MAX_WIDTH)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // Current lyric, one line, directly above the scrubber. It stays in
            // the layout — and stays fully visible — whether or not the queue
            // is open: dropping it would shorten this block and the controls
            // under it would jump the moment the queue started sliding in, and
            // fading it away behind the queue left this the one place in the
            // player where the current line simply vanished.
            //
            // Switched off in Settings it goes entirely, rather than sitting
            // there saying no lyrics were found: none were looked for. It is
            // accompanied by a dedicated lyrics button in the bottom row. Its
            // one-line slot remains, invisibly, so opening lyrics cannot grow
            // the half-player merely to make room for the source label.
            if (!lyricsOpen && syncedLyricsEnabled) {
                CurrentLyricStrip(
                    lines = lyricsTranslation.displayedLyrics,
                    trackKey = song.videoId,
                    positionMs = lyricsPosition,
                    isPlaying = isPlaying,
                    durationMs = durationMs,
                    lyricsUnavailable = lyricsUnavailable,
                    loadingText = lyricsLoadingText,
                    // Still visible over the queue, so still a valid way in:
                    // opens the same full lyrics panel it always has, closing
                    // the queue behind it the same way the "Up next" glyph
                    // closes lyrics behind the queue.
                    onClick = openLyrics,
                )
            }
            if (!lyricsOpen && !syncedLyricsEnabled) {
                Text(
                    text = "\u00A0",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = 6.dp)
                        .padding(vertical = 4.dp),
                )
            }
            if (lyricsOpen) {
                LyricsStatusWithChange(
                    status = lyricsTranslation.status,
                    onChange = { showLyricsProviders = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = 6.dp)
                        .padding(vertical = 4.dp),
                )
            }
            PlayerScrubber(
                shown = shown,
                durationMs = durationMs,
                loading = versionSwitching,
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
            ) {
                PlaybackQualityLabel(
                    song = song,
                    isLoading = isLoading,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 8.dp),
                )
            }

            // The transport rides midway between the two blocks it separates:
            // the scrubber above it, and the volume bar and toggle row below,
            // which sit close enough together to read as one. Both of its own
            // gaps take half the spread, so on a tall screen it holds the
            // centre rather than drifting up under the seek bar.
            Spacer(Modifier.height(8.dp + controlSpread / 2))

            TransportRow(
                isPlaying = isPlaying,
                isLoading = isLoading || audioVersionSwitching,
                previousEnabled = hasPrevious || pastRestartPoint,
                nextEnabled = hasNext,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
            )

            // Keep the volume slot's full footprint when its contents are
            // hidden. Removing the slot itself shortened the controls by 50dp
            // and moved every control below it. A display preference should not
            // change the half-player's geometry.
            Spacer(Modifier.height(12.dp + controlSpread / 2))

            if (!hideVolumeBar) {
                VolumeRow(
                    value = { volume.level.value },
                    onValueChange = onVolumeChange,
                    onValueChangeFinished = onVolumeChangeFinished,
                )
            } else {
                Spacer(Modifier.height(VOLUME_ROW_HEIGHT))
            }

            // The volume slider already has 13dp below its drawn track.
            // Balance that invisible inset with the caption gap below the icons.
            Spacer(Modifier.height(6.dp))

            playerActions()
            Spacer(Modifier.height(18.dp))
            }
            }
            }
            }
        }
        playerOverlays()
    }
}

/**
 * The upward half of the sleeve's vertical gesture: dragged up, the artwork
 * block pulls the queue in behind it, following the finger the whole way and
 * settling to whichever end it was nearer on release.
 *
 * Downward is deliberately not ours. The sheet the player sits in is what closes
 * when the sleeve is dragged that way, and it can only read a drag it was
 * allowed to see — so a downward crossing of the touch slop is left entirely
 * alone and this returns having consumed nothing at all.
 *
 * Which of the two it is can only be known at the crossing, which is why the
 * decision is made there rather than at the press. A pointer event reaches a
 * child before its parent, so consuming the very event that crossed the slop is
 * enough to keep the sheet out of an upward drag, and letting that one event
 * through is enough to hand it a downward one — the sheet's own slop detector
 * gives up the moment it sees a change already spoken for.
 *
 * @param travel how far the sleeve has to be dragged for the queue to arrive.
 * @param slide the 0..1 the player's whole layout reads off.
 * @param onHold true while the finger owns [slide] and false when it hands it
 *   back; the settling animation is parked in between so the two never write the
 *   same value on alternate frames.
 * @param onSettle the state the release decided on, which that animation then
 *   finishes reaching from wherever the finger left off.
 */
private suspend fun AwaitPointerEventScope.dragQueueIn(
    down: PointerInputChange,
    travel: Float,
    slide: MutableFloatState,
    onHold: (Boolean) -> Unit,
    onSettle: (Boolean) -> Unit,
) {
    // A block with nowhere to travel — a player not yet measured — would divide
    // by nothing and snap the queue open on the first pixel of movement.
    if (travel < 1f) return

    var pulled = 0f
    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
        if (overSlop < 0f) {
            pulled = -overSlop
            change.consume()
        }
    }
    if (drag == null || pulled <= 0f) return

    onHold(true)
    val velocity = VelocityTracker()
    velocity.addPointerInputChange(drag)
    slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
    verticalDrag(drag.id) { change ->
        velocity.addPointerInputChange(change)
        pulled -= change.positionChange().y
        slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
        change.consume()
    }

    // A flick decides on its own — it says "open" without asking the finger to
    // travel at all. Anything slower goes to whichever end it got nearer to.
    val flick = -velocity.calculateVelocity().y
    val open = when {
        flick >= QUEUE_FLICK_VELOCITY -> true
        flick <= -QUEUE_FLICK_VELOCITY -> false
        else -> slide.floatValue >= QUEUE_CARRY_FRACTION
    }
    onHold(false)
    onSettle(open)
}

/**
 * Back goes to [onBack] while [enabled], ahead of the sheet the player is drawn
 * in.
 *
 * The BackHandler can't do that on its own. The player is a ModalBottomSheet,
 * and from API 33 the sheet puts its own dismiss straight onto the window's
 * OnBackInvokedDispatcher at PRIORITY_DEFAULT — which is also where the dialog
 * dispatcher that every BackHandler feeds ends up, and at equal priority the
 * platform picks whichever registered last: the sheet's, every time. So on 33+
 * this outranks it with an overlay-priority callback for as long as [enabled]
 * holds, and only that long — otherwise the sheet keeps its own back handling
 * and its predictive-back shrink. Below 33 there is no window dispatcher to
 * outrank and the BackHandler is already the newest callback on the dialog's.
 *
 * Call order is priority order among these: a later call is the one back
 * reaches first while both are enabled.
 */
@Composable
private fun PlayerBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val view = LocalView.current
        DisposableEffect(view, enabled) {
            val callback = if (enabled) OverlayBack.register(view, onBack) else null
            onDispose { OverlayBack.unregister(view, callback) }
        }
    }
}

/**
 * A back callback that outranks whatever else the window has registered —
 * here, the sheet the player is drawn in. See the call site in
 * [NowPlayingScreen] for why it takes that.
 *
 * Everything that names an `android.window` type lives in this object so those
 * classes, which don't exist below API 33, are only ever *loaded* on a device
 * that has them: the callback comes back as [Any] rather than as the platform
 * interface for the same reason. Gating the calls on [Build.VERSION.SDK_INT]
 * is very likely enough by itself; this way it can't come down to how eagerly
 * a particular runtime resolves a reference it is never going to use.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object OverlayBack {
    /** The registered callback, to hand back to [unregister]; null if it couldn't be. */
    fun register(view: View, onBack: () -> Unit): Any? {
        val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
        val callback = OnBackInvokedCallback { onBack() }
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        return callback
    }

    fun unregister(view: View, callback: Any?) {
        if (callback !is OnBackInvokedCallback) return
        view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    }
}
