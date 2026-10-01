package com.music.bitchord.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.SystemClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * On-disk cache of the audio itself, and the one background fetch that fills it.
 *
 * Two things it is for:
 *
 *  - **Seeking.** Everything played is written to disk on the way through, so
 *    seeking back is always a file read. What is *ahead* of the player is
 *    closed by the track arriving on disk in full — which is why a background
 *    fetch asks for the whole range it wants in one request rather than
 *    trickling.
 *  - **Track changes.** [requestAnalysisHead] pulls an upcoming track's
 *    opening ahead of time, so the analyzer is never the thing a track change
 *    waits on.
 *
 * The cache is keyed by id, not by URL: stream URLs are single-use, expire
 * within hours, and differ between resolves of the same track, so keying on
 * them would cache every track afresh on every play. [CacheDataSource] sits
 * *outside* whatever serves the request, so a cache hit never goes to a
 * source at all.
 */
@UnstableApi
object AudioCache {

    private const val TAG = "BitChord"

    /**
     * The disk budget, straight from [AppSettings] — 512MB by default, roughly
     * 150 tracks at the highest bitrate offered, adjustable up to 10GB from
     * Settings. Least-recently-used entries are dropped past it, so it's a
     * ceiling rather than something the listener has to manage day to day.
     */
    private val evictor = DynamicLruCacheEvictor(AppSettings.DEFAULT_CACHE_LIMIT_BYTES)

    /**
     * What [fetch] risks before committing to a whole range.
     *
     * Sized to answer one question — did this write land at all — as cheaply
     * as that question can be asked, not to be worth anything on its own.
     */
    private const val LOCK_PROBE_BYTES = 64L * 1024

    /**
     * How far into a rendition [cachedPrefixBytes] looks when its real length
     * isn't known yet. Only an upper bound on the answer, so it costs nothing
     * to set well past the half-minute of audio any caller actually wants —
     * eight megabytes covers that even for lossless.
     */
    private const val HEAD_PROBE_BYTES = 8L * 1024 * 1024

    /**
     * What [requestAnalysisHead] asks for when the track's real size can't be
     * had.
     *
     * Twelve seconds of audio is what the head pass needs and four megabytes
     * clears that for anything short of lossless. Only reached when the real
     * length is not in the cache metadata yet — which is the ordinary case on
     * a first fetch — and a blind request is the one case where spending more
     * would be the listener's data spent on a guess.
     */
    private const val MAX_ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

    /**
     * The most [requestAnalysisHead] will pull for one track when its size *is*
     * known.
     *
     * A bound rather than "the whole file, always": a substituted lossless
     * rendition runs to thirty or forty megabytes, and the analyzer only ever
     * reads the head and the tail. Sixteen covers every YouTube Opus stream in
     * full — a ten-minute track at 160 kbps is twelve — which is the case this
     * exists for.
     *
     * Taking the whole file in one request is also what keeps the entry
     * single-sourced; see [analysisHeadSize].
     */
    private const val MAX_ANALYSIS_TRACK_BYTES = 16L * 1024 * 1024

    /**
     * How long [renditionKeysFor]'s answer is reused. Short enough that a
     * rendition which just started downloading is picked up well inside the
     * several seconds an analysis takes anyway.
     */
    private const val RENDITION_KEYS_TTL_MS = 5_000L

    /**
     * How many tracks [prefetchQueue] is handed past the one playing.
     *
     * One, because only the next track is ever worth spending bytes on — see
     * [prefetchQueue] — and the count is bounded so a long queue cannot turn a
     * single read-ahead into a scan of everything the listener has queued.
     */
    private const val QUEUE_LOOKAHEAD = 1

    /** How many upcoming tracks are worth gathering for [prefetchQueue] — the caller doesn't need to know why. */
    const val QUEUE_DEPTH = QUEUE_LOOKAHEAD + 1

    private lateinit var cache: SimpleCache

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Kept in the app's cache directory: this is disposable by definition, and
     * that is where the system and the "clear cache" button expect to reclaim
     * it from. [SimpleCache] copes with files disappearing underneath it by
     * dropping the spans that named them.
     */
    fun init(context: Context) {
        evictor.maxBytes = AppSettings.audioCacheLimitBytes.value
        cache = SimpleCache(
            File(context.cacheDir, "audio"),
            evictor,
            StandaloneDatabaseProvider(context),
        )
        // Builds before album/explicit/video-aware and credit-aware matching
        // may have cached a completely different recording under a YouTube
        // track's `#alt` key.
        // Those bytes otherwise outlive the matcher fix: playback serves them
        // without resolving, then refuses the correct 320kbps replacement as
        // no quality gain. Drop only the substitution-capable entries once;
        // ordinary YouTube cache entries remain warm.
        val state = context.getSharedPreferences(CACHE_STATE_PREFS, Context.MODE_PRIVATE)
        if (state.getInt(KEY_MATCHING_SCHEMA, 0) < MATCHING_SCHEMA) {
            val stale = cache.keys.filter { it.endsWith(ALT_SUFFIX) }
            stale.forEach { runCatching { cache.removeResource(it) } }
            state.edit().putInt(KEY_MATCHING_SCHEMA, MATCHING_SCHEMA).apply()
            TrackLog.d(TAG, "invalidated ${stale.size} source cache entries after matcher upgrade")
        }
        // A SimpleCache can only be opened once per process, so the ceiling
        // moves by mutating this evictor rather than reopening the cache —
        // see [DynamicLruCacheEvictor].
        scope.launch {
            AppSettings.audioCacheLimitBytes.collect { maxBytes ->
                evictor.maxBytes = maxBytes
                evictor.applyNow(cache)
            }
        }
    }

    /** Drops everything on disk. The listener asked; no grace period. */
    fun clear(onComplete: () -> Unit = {}) {
        cancel()
        // The bookkeeping describes bytes that are about to stop existing: a
        // one-round-per-track claim over an entry that just got deleted starts
        // nothing on the next switch until the wait's settle pass evicts it,
        // a remembered `want` is a size for a file that is gone, and the key
        // memo lists keys with nothing behind them. Cleared with the disk so
        // the first switch after a wipe asks for its head from a clean slate —
        // [discardBadRendition] clears one track's claim for the same reason.
        analysisHeads.clear()
        analysisHeadsInFlight.clear()
        analysisHeadWant.clear()
        renditionKeys.clear()
        scope.launch {
            cache.keys.toList().forEach { cache.removeResource(it) }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /**
     * Throws away everything held for the track [uri] plays, so the next open
     * fetches it again from the top.
     *
     * For when what is on disk is the problem rather than the network: a
     * half-written entry, or one filled from two different files and now
     * unreadable at the seam. Nothing here can tell which of those it is
     * looking at, so every rendition of the track goes — the `#alt` and
     * `#hifi` siblings as well as the entry named — and the cost is a
     * re-download rather than a track that cannot be played at all.
     *
     * A key still locked by a live reader can't be removed; that throw is
     * caught rather than prevented, because the alternative is holding a lock
     * of our own across the player's teardown.
     *
     * Runs on the caller's thread rather than off in [scope], so that a caller
     * about to re-open the track can be sure the old bytes are gone first.
     * Call it off the main thread.
     */
    fun discard(uri: Uri) {
        val exact = keyFactory.buildCacheKey(DataSpec(uri))
        val about = mediaIdIn(uri)
        val family = uri.getQueryParameter("v")?.let { videoId ->
            cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        } ?: emptyList()
        (family + exact).distinct().forEach { key ->
            runCatching { cache.removeResource(key) }
                .onSuccess { TrackLog.d(TAG, "discarded cache entry $key", about = about) }
                .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = about) }
        }
    }

    /**
     * Throws away only the rendition [uri] names, leaving the track's other
     * entries where they are.
     *
     * [discard]'s scorched-earth pass is right when what is on disk cannot be
     * trusted and there is no telling which entry is at fault. This is for the
     * case where there is: an upgrade that was fetched and then not used — an
     * audition that failed to prove itself, a swap the player put back — has
     * written a prefix of one file under the `#hifi` key and stopped. Left
     * there, the *next* upgrade of the same track keys to that same `#hifi`
     * entry, is served the abandoned prefix, and streams a different file into
     * the middle of it. Taking the whole family instead would throw away the
     * bytes of the stream still playing, which is the one thing that is
     * definitely fine.
     *
     * Runs on the caller's thread; call it off the main one.
     */
    fun discardRendition(uri: Uri) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        runCatching { cache.removeResource(key) }
            .onSuccess { TrackLog.d(TAG, "discarded unused rendition $key", about = mediaIdIn(uri)) }
            .onFailure { TrackLog.d(TAG, "rendition $key still in use: ${it.message}", about = mediaIdIn(uri)) }
    }

    /**
     * Throws away one named rendition of [uri] because its bytes cannot be
     * decoded, so the next attempt starts from a clean copy.
     *
     * The analyzer can tell a corrupt file from a merely incomplete one — a
     * container that reports two and a half minutes and decodes fourteen seconds
     * is not still downloading — but until this existed, knowing that led
     * nowhere. It recorded the rendition as bad and moved on, the bytes stayed on
     * disk looking complete, and every later attempt (including after a restart,
     * which clears that memory) re-read the same file and reached the same
     * answer. Two tracks were observed stuck that way permanently.
     *
     * Refuses to touch the rendition the player is currently reading from, whose
     * bytes are by definition fine — the decode that failed was of a *sibling*
     * copy. [Cache.removeResource] would also throw on the live entry's lock, but
     * relying on that would mean asking for playback's bytes to be deleted and
     * being saved by a race.
     *
     * Runs on the caller's thread; call it off the main one.
     *
     * @return true when the bytes are actually gone, which the caller must treat
     *   as "stop holding this key against the track" — a copy that no longer
     *   exists cannot be the reason to refuse the one replacing it.
     */
    fun discardBadRendition(uri: Uri, key: String): Boolean {
        if (!::cache.isInitialized) return false
        val about = mediaIdIn(uri)
        if (key == keyFactory.buildCacheKey(DataSpec(uri))) {
            TrackLog.d(TAG, "keeping undecodable rendition $key: it is the one playing", about = about)
            return false
        }
        return runCatching { cache.removeResource(key) }
            .onSuccess {
                TrackLog.d(TAG, "discarded undecodable rendition $key", about = about)
                // Deleting the bytes is only half of it. The head fetch runs once
                // per track per session, so without this the entry it just made
                // room for is never refilled and the discard buys nothing.
                uri.getQueryParameter("v")?.let(analysisHeads::remove)
            }
            .onFailure {
                TrackLog.d(TAG, "undecodable rendition $key still in use: ${it.message}", about = about)
            }
            .isSuccess
    }

    /**
     * The videoId behind a request. Playback asks through the custom scheme;
     * read-ahead builds the same URI, so both land on one cache entry.
     */
    private val keyFactory = CacheKeyFactory { spec ->
        spec.uri.getQueryParameter("v")
            // A videoId can name several different recordings on disk: the
            // copy the resolver handed over, and the better copy that replaces
            // *that* mid-track when one turns up, see [QualityUpgrade]. Sharing
            // one entry between them survives neither a reorder nor a
            // half-cached track: the next play would serve a FLAC prefix and
            // then stream Opus into the middle of it. Each gets its own entry,
            // and the duplication costs a re-download rather than a corrupt
            // file.
            //
            // Built by an `if` rather than a chain of `?.let`: the previous
            // form ended `cacheTag(...)?.let { return@let "$videoId#$it" }`,
            // where `return@let` binds to the *inner* lambda, not the outer
            // one it was meant for. The upgraded key was built, discarded as
            // an unused expression, and every upgraded track fell through to
            // the entry belonging to the stream it had just replaced —
            // so a 320kbps AAC was written into the middle of a half-cached
            // WebM, which is the exact corruption the paragraph above exists
            // to prevent. It cost `IllegalStateException: No valid varint
            // length mask found` at the seam, and eight-second stalls before
            // that, when the swap blocked on a cache lock the outgoing reader
            // still held.
            ?.let { videoId ->
                val rendition = QualityUpgrade.cacheTag(spec.uri)
                if (rendition != null) "$videoId#$rendition" else videoId
            }
            // A source-backed track keys on the source and its track id alone.
            // The full URI would work but carries the title and artist used
            // for cross-source matching, and the same track queued from a row
            // that spelled either of them differently would then occupy a
            // second copy of itself on disk.
            ?: spec.uri.takeIf { it.authority == "source" }?.let { uri ->
                val source = uri.getQueryParameter("s")
                val track = uri.getQueryParameter("t")
                if (source != null && track != null) {
                    val base = "$source|$track"
                    QualityUpgrade.cacheTag(uri)?.let { "$base#$it" } ?: base
                } else {
                    null
                }
            }
            ?: spec.key
            ?: spec.uri.toString()
    }

    private const val CACHE_STATE_PREFS = "audio_cache_state"
    private const val KEY_MATCHING_SCHEMA = "matching_schema"
    private const val MATCHING_SCHEMA = 2
    private const val ALT_SUFFIX = "#alt"

    /**
     * Wraps [upstream] so everything played is written to disk on the way
     * through, and anything already there is served without a request.
     * Local file and content URIs bypass disk caching to prevent redundant
     * writes — and so do Telegram tracks (see [isTelegramTrack]).
     */
    fun playbackFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val cacheDs = cacheFactory(upstream).createDataSource()
        val upstreamDs = upstream.createDataSource()
        object : DataSource {
            private var activeDs: DataSource = cacheDs

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                cacheDs.addTransferListener(transferListener)
                upstreamDs.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val scheme = dataSpec.uri.scheme
                activeDs = if (
                    scheme == "file" || scheme == "content" || isTelegramTrack(dataSpec.uri)
                ) {
                    upstreamDs
                } else {
                    cacheDs
                }
                return activeDs.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                activeDs.read(buffer, offset, length)

            override fun getUri(): Uri? = activeDs.uri

            override fun close() {
                activeDs.close()
            }
        }
    }

    /**
     * A Telegram track (`bitchord://source?…&t=tg_…`) whose bytes already sit
     * on disk — the TDLib download or a /Telegram/ file — and which the
     * resolver re-serves as `file://` on every play. The queue's URI is still
     * `bitchord://` when it reaches this `open()`, so without this check the
     * cache path took a second full copy of a file that was already there
     * (2× disk per Telegram track). Bypassing cannot cost offline playback:
     * the replay resolves to the same local file, network or not. Read-ahead
     * never writes these either — [prefetchQueue] drops source-backed keys.
     */
    private fun isTelegramTrack(uri: Uri): Boolean =
        uri.authority == "source" && uri.getQueryParameter("t")?.startsWith("tg_") == true

    private fun cacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)
        // A cache write that fails (full disk, evicted mid-write) should drop
        // to streaming, not surface as a playback error.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * As [cacheFactory], minus [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] —
     * for [fetch] alone, never for playback.
     *
     * That flag exists so a playback read whose *write* fails still serves
     * the listener their audio; a background fetch has no listener to serve,
     * so hiding the same failure just spends their data reading bytes onto
     * the floor. Measured: a fetch aimed at an entry the player had already
     * reached — its cache entry locked by the real reader, exactly the "lost
     * race" [fetch] probes for — instead read its whole range from the
     * network on every retry, because the flag turned the lock exception into
     * a silent, uncached pass-through rather than the failure [fetch]'s own
     * `runCatching` is written to catch. Nine megabytes on one ordinary,
     * unskipped track change, for a fetch that cached nothing and was always
     * going to. Without the flag, losing the race throws before a byte is
     * read, and every attempt past the first costs nothing.
     */
    private fun readAheadCacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)

    /** Set once the player exists; read-ahead resolves streams the same way. */
    private var upstreamFactory: DataSource.Factory? = null

    fun setUpstream(factory: DataSource.Factory) {
        upstreamFactory = factory
    }

    private var job: Job? = null
    private var pendingQueue: List<String> = emptyList()

    /**
     * Notes the queue ahead of the one playing, in play order.
     *
     * Nothing is fetched for it any more. What this used to run was a warm-up
     * of the *stream URLs* of upcoming `bitchord://watch?v=…` tracks — a
     * request nothing in the app answers now — plus whole-track read-ahead for
     * those same ids, written under the plain cache key that only ever belonged
     * to such a request.
     *
     * Everything actually in a queue now is either source-backed, which
     * bypasses this cache outright (see [isTelegramTrack]), or already on disk
     * in the listener's own downloads, and neither is a case read-ahead can
     * serve: the bytes would be written under a key playback never asks for,
     * spending mobile data on a second copy of a file that is already there.
     *
     * Called freely and cheaply; a call naming the same queue as the one
     * already noted is left alone, since the queue moving is what makes a
     * fresh look worth anything.
     */
    fun prefetchQueue(upcoming: List<Upcoming>) {
        val mediaIds = upcoming.map { it.mediaId }
        if (mediaIds == pendingQueue) return
        android.util.Log.d("BCFetchDebug", "prefetchQueue: head ${pendingQueue.firstOrNull()} -> ${mediaIds.firstOrNull()}")
        pendingQueue = mediaIds
        job?.cancel()
        job = null
    }

    /**
     * A queued track as read-ahead needs it.
     *
     * [target] is what a cross-source match is made on, and read-ahead cannot
     * reach it any other way: it runs for tracks that are not the current item,
     * so the session's metadata is the wrong track's, and the plain
     * `bitchord://watch?v=…` URI it builds for itself carries an id and nothing
     * else. It rides along from the queue instead — see
     * [PlaybackService.prefetchAround][com.music.bitchord.playback.PlaybackService].
     */
    data class Upcoming(val mediaId: String, val target: TrackMatcher.Target)

    /**
     * Nothing to read ahead for once playback stops. The queue is cleared with
     * the job, so resuming starts the fetch again rather than being mistaken
     * for one already in hand.
     */
    fun cancel() {
        pendingQueue = emptyList()
        job?.cancel()
        job = null
    }

    /**
     * Pulls [length] bytes of whatever [uri] names into the cache, under [uri]'s
     * own key rather than the plain videoId.
     *
     * For the opening of a rendition that is about to be swapped in — see
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s audition.
     * A player preparing a progressive source has to parse the container from
     * byte zero before it can seek anywhere, and for a FLAC that is not a few
     * bytes: STREAMINFO, the seek table, the tags and an embedded cover can run
     * to hundreds of kilobytes. Measured here, the audition itself cached only
     * `[0, 8192)` before seeking away to the playing position, so the real
     * player's very first read after the swap — the one nothing can start
     * without — was a cache miss and a round trip to the CDN, in silence.
     *
     * Call it *before* the audition rather than alongside: Media3 locks a cache
     * entry to one writer, and two writers on the same rendition means one of
     * them spends the listener's data caching nothing.
     */
    /**
     * What is actually on disk for [uri]'s rendition, as a log line.
     *
     * Here because "the audition cached it" is an assumption that has already
     * been wrong once, and the only place it can be checked is against the
     * cache itself: a swap that lands on bytes the audition was supposed to
     * have fetched looks, from the player's side, exactly like one that lands
     * on bytes it never reached.
     */
    fun cachedSummary(uri: Uri): String {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val spans = cache.getCachedSpans(key).filter { it.isCached }
        if (spans.isEmpty()) return "$key holds nothing"
        val total = spans.sumOf { it.length }
        val ranges = spans.sortedBy { it.position }
            .joinToString(" ") { "[${it.position},${it.position + it.length})" }
        return "$key holds ${total / 1024}kB in ${spans.size} spans: $ranges"
    }

    suspend fun warmRange(uri: Uri, position: Long, length: Long) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        fetch(key, uri, position, length)
    }

    /**
     * Video ids whose analysis copy has already been asked for this session.
     *
     * A set, not a size: the fetch is sized once from the track's real length
     * rather than grown into over several rounds — see [analysisHeadSize].
     * Cleared for a track whose copy turns out to be undecodable, so discarding
     * it leads to a fresh pull rather than to nothing.
     */
    private val analysisHeads = ConcurrentHashMap<String, Boolean>()

    /** Video ids with a head fetch in the air, so a tick cannot stack another on top. */
    private val analysisHeadsInFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many bytes each track's last round asked for — the only way for
     * [awaitAnalysisHead] to tell a finished head from one the round left
     * part-written. See [alignmentHeadComplete].
     */
    private val analysisHeadWant = ConcurrentHashMap<String, Long>()

    /**
     * Pulls [uri]'s recording onto disk under the plain YouTube key, so Smart
     * Fade has something to measure; sized by [analysisHeadSize].
     *
     * ## Why the analyzer has to ask for this
     *
     * Nothing else fetches a track's opening early enough. The analyzer can only
     * read bytes some other part of the app happened to write, at the offsets it
     * happened to need, and neither of the two writers produces a head in time:
     *
     *  - Read-ahead's *byte* half is switched off outright whenever source
     *    substitution is on — see [prefetchQueue] — so a track that has never
     *    been played holds nothing at all until it starts playing. Measured, the
     *    next track's analysis then lands eleven to forty seconds late, which is
     *    after the transition it was meant to inform and sometimes after the
     *    track has started.
     *  - A quality upgrade fetches its rendition from the swap point onward, so
     *    the upgraded copy's first seconds are a region nothing downloads on its
     *    own, and the copy it replaced is discarded. A track that plays lossless
     *    from the start is therefore permanently unmeasurable however long it
     *    sits in the cache.
     *
     * ## Why this one is safe when [prefetchQueue]'s bytes are not
     *
     * The hazard read-ahead was disabled over is a key that disagrees with its
     * contents. [keyFactory] decides between `videoId` and `videoId#alt` from
     * the *global* substitution setting rather than from what a request actually
     * resolved to, so a fetch built from an id alone — which always resolves to
     * YouTube, carrying none of the title and artist a substitution is matched
     * on — wrote Opus bytes into the entry playback was filling from another
     * source. That is what produced `No valid varint length mask found` at the
     * seam.
     *
     * Here the key is pinned to the plain videoId rather than derived, so the
     * bytes and the entry they land in are both unambiguously YouTube's, and the
     * `#alt` and `#<rendition>` entries are untouched. The analyzer reaches this
     * copy through [renditionsOf] and cross-checks its container duration before
     * borrowing a beat grid across renditions, so a substituted source that
     * turns out to be a different cut is caught there.
     *
     * ## Why it fetches once, at full size
     *
     * An earlier version started at a megabyte and grew the request on each
     * later call, so that a track whose first attempt did not decode was not
     * stuck forever. That cured the dead end and caused a worse fault: every
     * round resolves the stream again, and the second round skips what is
     * already cached and writes the *remainder of a different rendition* into the
     * same entry. See [analysisHeadSize].
     *
     * The dead end it was solving is now answered from the other side. A copy
     * that cannot be decoded is deleted rather than remembered — see
     * [discardBadRendition] — which both frees the entry and re-arms this, so the
     * retry is a clean pull instead of a larger read of the same bad bytes.
     *
     * A no-op for anything that isn't a YouTube-backed track.
     */
    /**
     * Enough of a YouTube Opus head to decode the opening window
     * [com.music.bitchord.playback.smart.VersionAudioAligner] measures.
     */
    const val MIN_ALIGNMENT_PREFIX_BYTES = 800L * 1024

    /**
     * How many passes [awaitAnalysisHead] may start at a head. One is the
     * normal case; the rest exist for a round that ends part-written, which is
     * the failure worth spending a fetch on again.
     */
    private const val MAX_ALIGNMENT_HEAD_ROUNDS = 3

    /**
     * Gap kept before a retry claims the track again. Claiming and registering
     * the claim are two statements in [requestAnalysisHead]; without a gap, a
     * wait that looked between them could take the claim away and set off a
     * second fetch alongside the one it was trying to join.
     */
    private const val HEAD_ROUND_SETTLE_MS = 500L

    /**
     * Pulls the analysis head for [uri] if needed and waits until enough of
     * the opening is on disk to align two versions of a track.
     *
     * "Enough" is the whole opening a round asked for, or
     * [MIN_ALIGNMENT_PREFIX_BYTES] for a head that was grown elsewhere. What it
     * deliberately does *not* accept is part of a round that gave up halfway —
     * a head that decodes for a handful of seconds caps how far the two cuts
     * may be slid against each other, so a thirty-second difference comes back
     * as an eight-second search reporting whatever noise it found. Handing
     * that over as a measurement is how a swap ends up landing near the wrong
     * second while looking like it worked, so the wait pulls again instead.
     *
     * @return whether the opening is fully on disk, `false` once no further
     *   round can improve on it — the deadline passed, or
     *   [MAX_ALIGNMENT_HEAD_ROUNDS] rounds all came up short.
     */
    suspend fun awaitAnalysisHead(uri: Uri, timeoutMs: Long = 20_000L): Boolean {
        if (!::cache.isInitialized) return false
        val videoId = uri.getQueryParameter("v") ?: return false
        if (alignmentHeadComplete(videoId)) return true
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        // A previous round that wrote nothing would otherwise block retries
        // for the rest of the session.
        if (videoId !in analysisHeadsInFlight) {
            analysisHeads.remove(videoId)
        }
        requestAnalysisHead(opus)
        var rounds = 1
        var askedAt = SystemClock.elapsedRealtime()
        val deadline = askedAt + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (alignmentHeadComplete(videoId)) return true
            if (videoId !in analysisHeadsInFlight) {
                if (rounds >= MAX_ALIGNMENT_HEAD_ROUNDS) return alignmentHeadComplete(videoId)
                val now = SystemClock.elapsedRealtime()
                if (now - askedAt >= HEAD_ROUND_SETTLE_MS) {
                    // A round that got as far as its lock probe — the few
                    // dozen kilobytes written before the stream underneath
                    // them was refused — used to count as bytes on disk and
                    // block every later attempt, so the wait sat out the
                    // whole deadline against a head that was never going to
                    // grow and the switch went ahead unaligned. Below
                    // [MIN_ALIGNMENT_PREFIX_BYTES] there is nothing here a
                    // measurement can act on, so the round may try again, and
                    // each retry runs [clearPartialHead] first — which is what
                    // keeps a part-written entry from splicing two encodings.
                    // [MAX_ALIGNMENT_HEAD_ROUNDS] still bounds how often.
                    if (alignmentPrefixBytes(videoId) < MIN_ALIGNMENT_PREFIX_BYTES) {
                        analysisHeads.remove(videoId)
                        requestAnalysisHead(opus)
                        rounds++
                    }
                    askedAt = now
                }
            }
            delay(40)
        }
        return alignmentHeadComplete(videoId)
    }

    /**
     * Whether [videoId]'s opening is as complete as it is ever going to be:
     * past [MIN_ALIGNMENT_PREFIX_BYTES], or as much as the round asked for once
     * that round has stopped writing.
     *
     * [analysisHeadWant] is what was actually asked for, recorded when it was
     * asked, because the wait cannot otherwise tell "finished" from "stopped
     * early" — both look like bytes on disk.
     */
    private fun alignmentHeadComplete(videoId: String): Boolean {
        val held = alignmentPrefixBytes(videoId)
        if (held >= MIN_ALIGNMENT_PREFIX_BYTES) return true
        val want = analysisHeadWant[videoId] ?: return false
        if (videoId in analysisHeadsInFlight) return false
        return held >= want
    }

    /**
     * The figure [VersionAudioAligner] opens by: the largest same-source
     * (non-`#alt`) copy when it is big enough to measure, and the largest copy
     * of any kind otherwise. A substitute can be a different cut of the same
     * song, so it is only worth waiting on when nothing else is on disk — and
     * asking the same question here as there is what makes "ready" mean
     * "openable with exactly these bytes".
     */
    private fun alignmentPrefixBytes(videoId: String): Long {
        val watch = Uri.parse("bitchord://watch?v=$videoId")
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        val renditions = renditionsOf(watch)
        val sameSource = renditions
            .filter { !it.key.endsWith("#alt") }
            .maxOfOrNull { it.cachedPrefix } ?: 0L
        if (sameSource >= MIN_ALIGNMENT_PREFIX_BYTES) return sameSource
        return maxOf(
            cachedPrefixBytes(opus),
            cachedPrefixBytes(watch),
            renditions.maxOfOrNull { it.cachedPrefix } ?: 0L,
        )
    }

    fun requestAnalysisHead(uri: Uri) {
        // Each of these gates used to stop a head dead with nothing in the
        // log to show for it: an unwired cache or upstream looked from the
        // outside exactly like a network that refuses everything.
        if (!::cache.isInitialized) {
            TrackLog.w(TAG, "analysis head for $uri not started: cache is not initialised")
            return
        }
        if (upstreamFactory == null) {
            TrackLog.w(TAG, "analysis head for $uri not started: no upstream yet")
            return
        }
        val videoId = uri.getQueryParameter("v") ?: return
        // One round per track per session, and it is sized correctly up front
        // rather than grown into. See [analysisHeadSize] for why growing it was
        // the wrong shape.
        if (analysisHeads.putIfAbsent(videoId, true) != null) return
        // Checked before the round is claimed, so the playback thread pays a set
        // lookup per tick rather than queueing coroutines four times a second on
        // top of a fetch that is still running.
        if (!analysisHeadsInFlight.add(videoId)) return
        scope.launch {
            try {
                // The real size, if anything has already recorded it against
                // this key — see [recordContentLength]. Zero when nothing has,
                // which is the ordinary case on a first fetch and exactly the
                // case [analysisHeadSize] is written to fall back on.
                val total = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(videoId)) }
                    .getOrDefault(0L)
                val want = analysisHeadSize(total)
                analysisHeadWant[videoId] = want
                TrackLog.w(
                    TAG,
                    "analysis head round for $videoId: total=$total want=$want " +
                        "held=${alignmentPrefixBytes(videoId)}",
                    videoId,
                )
                if (!clearPartialHead(videoId, want)) {
                    TrackLog.w(TAG, "analysis head round for $videoId skipped: partial is held elsewhere", videoId)
                    return@launch
                }
                fetch(
                    cacheKey = videoId,
                    // Do not inherit a substitute/lossless StreamChoice from
                    // playback. The base key is reserved for the lightweight
                    // YouTube Opus copy used by Automix analysis.
                    uri = Uri.parse(AutomixAnalysisSource.opusUri(videoId)),
                    position = 0,
                    length = want,
                    pinKey = true,
                )
                if (alignmentPrefixBytes(videoId) < MIN_ALIGNMENT_PREFIX_BYTES) {
                    // The plain Opus copy would not come: it is minted through
                    // a request no enabled source answers, while playback of
                    // the very same track is unbothered because it reads
                    // whichever source the substitution chose — which is also
                    // exactly what the direction that *works* measures against.
                    // So when the pinned copy comes up short, pull that copy
                    // instead: alignment opens the fullest copy on disk anyway,
                    // and a failed head here otherwise costs the whole switch
                    // its smart align while the swap goes ahead regardless.
                    pull(
                        videoId,
                        Uri.parse("bitchord://watch?v=$videoId"),
                        0,
                        want,
                        /* pinKey = */ false,
                    )
                }
                if (total > 0) recordContentLength(videoId, total)
                val held = alignmentPrefixBytes(videoId)
                if (held < MIN_ALIGNMENT_PREFIX_BYTES) {
                    // The one line that says *this* fetch failed, as opposed to
                    // the read-ahead chatter around it: every cause below
                    // already logs its own message at debug, but nothing said
                    // where a round ended or what it had to work with.
                    TrackLog.w(
                        TAG,
                        "analysis head for $videoId came up short: " +
                            "held=$held want=$want total=$total " +
                            "(cause in the read-ahead lines around this one)",
                        videoId,
                    )
                }
            } finally {
                analysisHeadsInFlight.remove(videoId)
            }
        }
    }

    /**
     * How much of a track to pull for the analyzer, decided once from its real
     * size.
     *
     * This used to start at a megabyte and grow by [HEAD_ESCALATION] on each
     * later call, which was the wrong shape for a reason this file documents
     * elsewhere — see [discardRendition]. Each round resolves the stream again,
     * and YouTube does not promise the same rendition twice. Round one wrote a
     * megabyte of one encoding; round two skipped what was already cached and
     * wrote the *remainder of a different one* into the same entry. The result is
     * a contiguous, correctly sized, complete-looking file that decodes for a few
     * seconds and then stops at the seam — which is exactly what two tracks were
     * observed doing, one decoding 14.4s of a 153s container and another 35.5s of
     * 211.5s, both while the cache called them complete.
     *
     * One resolve, one range, one encoding. The length is read before the
     * range is asked for — see [recordContentLength] — so asking first costs
     * nothing.
     */
    private fun analysisHeadSize(total: Long): Long =
        if (total > 0) minOf(total, MAX_ANALYSIS_TRACK_BYTES) else MAX_ANALYSIS_HEAD_BYTES

    /**
     * Makes sure [videoId]'s entry is either empty or already covers [want]
     * before anything writes to it.
     *
     * A part-filled entry is the seam hazard in [analysisHeadSize] waiting to
     * happen: whatever is there came from an earlier resolve — a fetch this
     * session cancelled, or a round from a build that still escalated — and
     * filling the gap would splice a second encoding onto it. Cheaper to throw
     * the prefix away and pull one clean copy.
     *
     * @return false when the caller should not fetch: either the entry is
     *   already complete enough, or it is held by a live reader and cannot be
     *   cleared, in which case writing would create the very seam this avoids.
     */
    private fun clearPartialHead(videoId: String, want: Long): Boolean {
        val held = cache.getCachedBytes(videoId, 0, want)
        if (held <= 0L) return true
        if (held >= want) return false
        return runCatching { cache.removeResource(videoId) }
            .onSuccess {
                TrackLog.d(TAG, "cleared partial analysis head for $videoId ($held of $want)", about = videoId)
            }
            .onFailure {
                TrackLog.d(TAG, "partial head for $videoId is in use: ${it.message}", about = videoId)
            }
            .isSuccess
    }

    /**
     * Writes [videoId]'s real size into its cache metadata, which a bounded
     * fetch does not.
     *
     * Media3 records a resource's length from a response that describes the
     * whole resource. [requestAnalysisHead] asks for a megabyte, so the response
     * describes a megabyte, and the entry is left with no length at all — which
     * is not a cosmetic gap. Everything downstream divides by it: the analyzer
     * ranks renditions by how much *audio* each holds, so a length of zero makes
     * a freshly fetched head score zero seconds and lose to any sibling holding
     * a sliver of an unusable one. That is how a track whose opening had just
     * been downloaded went on reading as having nothing.
     *
     * Cheap because the number is already in the cache metadata whenever the
     * rendition has been opened through [cacheFactory] in full — the response
     * that described the whole resource recorded it.
     */
    private fun recordContentLength(videoId: String, total: Long) {
        if (ContentMetadata.getContentLength(cache.getContentMetadata(videoId)) > 0) return
        if (total <= 0) return
        runCatching {
            cache.applyContentMetadataMutations(
                videoId,
                ContentMetadataMutations().apply { ContentMetadataMutations.setContentLength(this, total) },
            )
        }.onFailure { TrackLog.d(TAG, "could not record length for $videoId: ${it.message}", about = videoId) }
    }

    /**
     * True once every byte of [uri]'s rendition is on disk.
     *
     * Automix's analyzer needs this before it can safely decode a track: a
     * partially fetched file may not even have a parsable container, and
     * analysing the head of a track whose tail hasn't arrived would produce a
     * grid for audio the listener will never reach through that transition.
     *
     * Reads the content length Media3 already recorded against this cache key
     * (from the upstream response, the first time anything read this rendition)
     * rather than re-deriving it per source type, so it works for anything that
     * has ever been opened through [cacheFactory] — source-backed, on disk, or
     * either of the plain forms of [uri].
     */
    fun isFullyCached(uri: Uri): Boolean {
        val contentLength = contentLengthOf(uri)
        if (contentLength <= 0) return false
        // [Cache.getCachedLength], not [Cache.getCachedBytes]. The latter counts
        // every cached byte in the span *however it is scattered*, so a
        // rendition with holes in it — which is the normal result of seeking
        // around a track while read-ahead fills the rest in behind you — reports
        // the same total as a complete one. The decoder does not skip holes: it
        // stops at the first, and the analyzer then reads the entire missing
        // remainder as trailing silence and places the mix-out anchor there.
        // That is how a four-minute track came to be analysed as ending at
        // 61 seconds and transitioned out of at 56. Contiguous-from-zero is what
        // "fully cached" was always meant to mean.
        return cachedPrefixBytes(uri) >= contentLength
    }

    /**
     * The full size of [uri]'s rendition in bytes, or 0 when it isn't known yet.
     *
     * Read from the content metadata Media3 recorded from the upstream response
     * the first time anything opened this rendition, so it is available well
     * before the bytes are.
     */
    fun contentLengthOf(uri: Uri): Long {
        if (!::cache.isInitialized) return 0L
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        return ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
    }

    /**
     * How many bytes of [uri]'s rendition are on disk *contiguously from the
     * start*, which is the only measure a head-only decode can act on: a
     * rendition holding its last megabyte and nothing else has plenty of cached
     * bytes and no parsable beginning.
     *
     * Zero when the rendition's length isn't known yet, when nothing is cached,
     * or when the cached region doesn't start at byte 0.
     */
    fun cachedPrefixBytes(uri: Uri): Long {
        if (!::cache.isInitialized) return 0L
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        // Probed over a fixed span rather than the content length. The length is
        // only recorded once something has *opened* the rendition, and the whole
        // point of this measurement is a track nothing has opened yet — read-
        // ahead has written its opening bytes and nothing else has touched it.
        // Requiring the length here made this return zero for exactly the
        // tracks it exists to describe.
        val probe = contentLengthOf(uri).takeIf { it > 0 } ?: HEAD_PROBE_BYTES
        // Negative means "this many bytes of hole", i.e. position 0 isn't cached at all.
        return cache.getCachedLength(key, 0, probe).coerceAtLeast(0L)
    }

    /**
     * One rendition of a recording, as Automix's analyzer sees it.
     *
     * [key] is a cache key, not a URI: the point of this type is to name a
     * rendition the *player* is not using, which no URI in hand refers to.
     */
    data class Rendition(val key: String, val contentLength: Long, val cachedPrefix: Long) {
        val isComplete: Boolean get() = contentLength > 0 && cachedPrefix >= contentLength
    }

    /**
     * Every rendition of [uri]'s recording that is on disk, cheapest first.
     *
     * One videoId owns up to three cache entries — the Opus stream YouTube
     * serves, a substituted source's copy (`#alt`), and a quality upgrade
     * (`#<rendition>`); see [keyFactory]. They hold the same *music*, so an
     * analysis of any of them describes all of them, and analysing the smallest
     * one costs a fraction of what the largest does. This is what lets a track
     * be analysed off a 3 MB Opus stream that finished downloading a minute ago
     * instead of waiting on the 40 MB FLAC that replaced it.
     *
     * Ordered by size because decode time tracks bytes, and because the
     * lightest complete rendition is the one that was available earliest.
     *
     * Callers must still check the durations agree before treating two
     * renditions as interchangeable — see [TrackAnalyzer]. A `#alt` entry comes
     * from a different source and can be a different cut of the same song,
     * where a shared beat grid would put every anchor seconds off.
     */
    fun renditionsOf(uri: Uri): List<Rendition> {
        if (!::cache.isInitialized) return emptyList()
        val videoId = uri.getQueryParameter("v")
            ?: return listOfNotNull(renditionFor(keyFactory.buildCacheKey(DataSpec(uri))))
        return renditionKeysFor(videoId)
            .mapNotNull(::renditionFor)
            .sortedBy { it.contentLength }
    }

    /**
     * Which cache keys belong to [videoId], memoized.
     *
     * [SimpleCache.getKeys] copies the entire key set on every call, and this
     * runs on the main thread twice per crossfade tick — four times a second,
     * against a cache holding every track ever played. Which *renditions* exist
     * changes only when a new one starts downloading, so it is safe to hold for
     * a few seconds; how much of each is on disk is not memoized and is still
     * read fresh every time.
     */
    private fun renditionKeysFor(videoId: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        renditionKeys[videoId]?.let { (at, keys) ->
            if (now - at < RENDITION_KEYS_TTL_MS) return keys
        }
        val keys = cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        // An empty answer is never remembered. The head round asks this
        // question a moment before it starts writing — to log `held=` — and
        // a five-second "no keys" memo taken then hides the very bytes it is
        // about to write from [renditionsOf], while [alignmentPrefixBytes]'s
        // direct reads see them the moment they land and open the gate. The
        // aligner, which goes only through this list, would then measure an
        // empty disk for the rest of the memo's life: the switch fails, the
        // retap five seconds later succeeds, and the wait in between is this
        // entry. Cold is the one state where the list is guaranteed to change
        // next, so cold is the one state that cannot be cached.
        if (keys.isNotEmpty()) {
            renditionKeys[videoId] = now to keys
        } else {
            renditionKeys.remove(videoId)
        }
        return keys
    }

    private val renditionKeys = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    /** The cache key [uri] itself resolves to right now — the rendition the player is using. */
    fun cacheKeyOf(uri: Uri): String = keyFactory.buildCacheKey(DataSpec(uri))

    private fun renditionFor(key: String): Rendition? {
        val contentLength =
            ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
        val probe = contentLength.takeIf { it > 0 } ?: HEAD_PROBE_BYTES
        val prefix = cache.getCachedLength(key, 0, probe).coerceAtLeast(0L)
        return if (prefix <= 0L) null else Rendition(key, contentLength, prefix)
    }

    /**
     * A reader over one named [Rendition], whatever the player is currently
     * using.
     *
     * The key is pinned rather than derived, because [keyFactory] resolves a
     * YouTube URI to whichever rendition is live *now* — which is precisely the
     * heavy one this exists to avoid reading. The URI is still passed along for
     * [CacheDataSource] to open against; only the key decides which bytes come
     * back.
     */
    fun renditionDataSource(uri: Uri, rendition: Rendition): MediaDataSource =
        CacheMediaDataSource(
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(NoUpstream)
                .setCacheKeyFactory { rendition.key }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource(),
            uri,
        )

    /**
     * A random-access reader over [uri]'s cached bytes, for the analyzer to hand
     * to [android.media.MediaExtractor]. Null when the rendition isn't fully
     * cached yet — analysis always treats that as "not ready" rather than
     * reading a partial file.
     *
     * The returned source only ever reads from disk: its upstream throws if
     * touched at all, which should never happen once [isFullyCached] is true.
     * Callers must [MediaDataSource.close] it.
     */
    fun mediaDataSource(uri: Uri): MediaDataSource? {
        if (!isFullyCached(uri)) return null
        return CacheMediaDataSource(cacheFactory(NoUpstream).createDataSource(), uri)
    }

    /**
     * The same reader over a rendition that is still downloading, for Smart
     * Fade's head-only pass.
     *
     * Nothing here truncates explicitly: a read into a region that hasn't
     * arrived reaches [NoUpstream], which throws, and [CacheMediaDataSource]
     * turns that into an end-of-stream. So a partially cached rendition presents
     * itself to [android.media.MediaExtractor] as a short file that stops where
     * the cache does, which is exactly what a head-only decode wants. Its
     * declared size is still the real one, so a container whose header describes
     * the whole track parses normally.
     *
     * Callers must check [cachedPrefixBytes] first — this only refuses the case
     * where the rendition has no beginning on disk at all. Callers must
     * [MediaDataSource.close] it.
     */
    fun headMediaDataSource(uri: Uri): MediaDataSource? {
        if (cachedPrefixBytes(uri) <= 0L) return null
        return CacheMediaDataSource(cacheFactory(NoUpstream).createDataSource(), uri)
    }

    /**
     * Never fetches. For a fully cached rendition nothing should reach this; for
     * a partially cached one, throwing is how a read past the cached prefix
     * becomes an end-of-stream instead of a download.
     */
    private object NoUpstream : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long =
                throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
            override fun getUri(): Uri? = null
            override fun close() {}
        }
    }

    /**
     * Adapts a Media3 [DataSource] (reading only from [cache]) to the
     * [MediaDataSource] interface [android.media.MediaExtractor] wants.
     *
     * Keeps the underlying source open across consecutive sequential reads —
     * the pattern MediaExtractor actually uses — and only reopens at a new
     * position when the read pattern jumps, e.g. a seek.
     */
    private class CacheMediaDataSource(
        private val dataSource: DataSource,
        private val uri: Uri,
    ) : MediaDataSource() {
        private var isOpen = false
        private var openPosition = -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (!isOpen || position != openPosition) {
                closeUpstream()
                val available = try {
                    dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                } catch (error: IOException) {
                    return -1
                }
                isOpen = true
                openPosition = position
                if (available == 0L) return -1
            }
            val count = try {
                dataSource.read(buffer, offset, size)
            } catch (error: IOException) {
                closeUpstream()
                return -1
            }
            if (count == C.RESULT_END_OF_INPUT) {
                closeUpstream()
                return -1
            }
            openPosition += count
            return count
        }

        override fun getSize(): Long {
            closeUpstream()
            return try {
                dataSource.open(DataSpec(uri))
            } catch (error: IOException) {
                -1L
            } finally {
                closeUpstream()
            }
        }

        override fun close() {
            closeUpstream()
        }

        private fun closeUpstream() {
            if (isOpen) {
                runCatching { dataSource.close() }
                isOpen = false
            }
        }
    }

    /**
     * Pulls [length] bytes of [videoId] from [position] into the cache.
     * [CacheWriter] fetches only the gaps, so a range already partly on disk —
     * from a track played earlier, or skipped back to — costs only the rest.
     */
    private suspend fun fetch(videoId: String, position: Long, length: Long) =
        fetch(videoId, Uri.parse("bitchord://watch?v=$videoId"), position, length)

    /**
     * [pinKey] forces the write to land under [cacheKey] instead of wherever
     * [keyFactory] would put it.
     *
     * Worth spelling out because the two are not otherwise the same thing:
     * [cacheKey] is only consulted for the "is this already here" check below,
     * while the entry actually written is chosen by [keyFactory] from the spec —
     * and [keyFactory] answers partly from the global substitution setting, not
     * from what this request resolves to. A caller that knows exactly what it is
     * fetching, as [requestAnalysisHead] does, is better off saying so than
     * letting a global decide on its behalf.
     */
    private suspend fun fetch(
        cacheKey: String,
        uri: Uri,
        position: Long,
        length: Long,
        pinKey: Boolean = false,
    ) {
        if (upstreamFactory == null) return
        if (cache.getCachedBytes(cacheKey, position, length) >= length) return

        // A cheap first knock rather than the whole range on the door.
        // Losing this entry to another writer isn't something
        // [CacheDataSource] surfaces as a failure — it quietly falls through
        // to the network and hands the bytes to nobody, which looks exactly
        // like a real fetch until the write is checked afterwards, because
        // that check has always been the only way to tell "nobody's home"
        // from "got it". Measured without this: a fetch that had lost that
        // race read its whole range from the network, found nothing had
        // landed, and paid that again on every retry — nine megabytes for a
        // track that was never going to cache, because whoever held the entry
        // held it the whole time. A small probe reaches the same verdict for a
        // fraction of the cost, and only a probe that actually lands is worth
        // following with the rest of the range.
        if (length > LOCK_PROBE_BYTES) {
            pull(cacheKey, uri, position, LOCK_PROBE_BYTES, pinKey)
            if (cache.getCachedBytes(cacheKey, position, LOCK_PROBE_BYTES) < LOCK_PROBE_BYTES) return
        }
        pull(cacheKey, uri, position, length, pinKey)
    }

    /** The actual network pull behind [fetch], unconditional and unchecked. */
    private suspend fun pull(cacheKey: String, uri: Uri, position: Long, length: Long, pinKey: Boolean) {
        val upstream = upstreamFactory ?: return

        // Whose track this is, taken off the URI rather than off [cacheKey]:
        // the key splits a track's renditions apart on purpose, and reading
        // ahead is the clearest case there is of work logged nowhere near the
        // track it is for. See [TrackLog.about].
        val about = mediaIdIn(uri)
        // Read-ahead is the app's largest consumer of bandwidth and, until this
        // line existed, its most invisible: whole tracks were pulled down while
        // a listener waited on a resolve for the track in front of them, and
        // nothing in the log said so. Bracketing it is what makes the overlap
        // between "reading ahead" and "waiting for sound" readable at all.
        val fetchStart = SystemClock.elapsedRealtime()
        TrackLog.d(TAG, "read-ahead fetching $cacheKey [$position, ${position + length})", about = about)

        val source = readAheadCacheFactory(upstream)
            .apply { if (pinKey) setCacheKeyFactory { cacheKey } }
            .createDataSource()
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .setLength(length)
            .build()
        val writer = CacheWriter(source, spec, /* temporaryBuffer = */ null, /* listener = */ null)

        runCatching {
            withContext(Dispatchers.IO) {
                // CacheWriter blocks in a read loop and checks this flag between
                // reads; cancelling the coroutine alone would leave it running.
                val handle = coroutineContext.job.invokeOnCompletion { writer.cancel() }
                try {
                    writer.cache()
                } finally {
                    handle.dispose()
                }
            }
        }.onFailure {
            // Expected on a skip, and never worth failing playback over — see
            // [readAheadCacheFactory] for why this is now also the ordinary
            // shape of losing the race to the player.
            TrackLog.d(TAG, "read-ahead stopped for $cacheKey: ${it.message}", about = about)
        }.onSuccess {
            TrackLog.d(
                TAG,
                "read-ahead fetched $cacheKey [$position, ${position + length}) in " +
                    "${SystemClock.elapsedRealtime() - fetchStart}ms",
                about = about,
            )
        }
    }
}
