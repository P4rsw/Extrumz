package com.music.bitchord.data.telegram

import android.graphics.BitmapFactory
import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.sources.SourceRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Artwork, fetched the way a channel scan is run: only for whatever is actually
 * on screen plus a small window ahead of it, deduplicated so the same cover is
 * never in flight twice, throttled to four requests at once — a fast scroll
 * costs cancelled requests, not a queue and not a data bill. Not silenced while
 * a scan indexes: a launch-time scan would otherwise freeze covers in the exact
 * minutes the user is looking.
 *
 * Three guards keep a scrolling list from paying for the same absence twice:
 *
 *  - **Auth wait**: rows compose before TDLib finishes connecting. The old
 *    "return null until Ready" never retried (the caller's effect keys don't
 *    change when auth flips), which is how a channel could sit coverless for a
 *    whole session. [fetchOnce] now suspends until Ready instead; the wait dies
 *    with the row if it scrolls away.
 *  - **Miss memo** ([MissMemo]): a fetch that completed and found NOTHING (no
 *    full-size file, no inline bytes, message gone) is remembered for
 *    [MISS_TTL_MS] — coverless rows, which are most of a scanned library, cost
 *    one pair of RPCs per TTL instead of one per rebind. A *cancelled* fetch is
 *    never memoized: a fling is not an answer.
 *  - **Prefetch** ([prefetchTrackCovers]): on scroll settle the window ahead of
 *    the viewport is warmed in an app-lifetime scope, so covers are already on
 *    disk by the time their rows arrive instead of popping in one by one.
 *
 * Two tiers:
 *
 *  - Tier 1 (`ensure*`) fills an EMPTY row avatar from Telegram's inline
 *    minithumbnail — one small local RPC, no file download. Each row pays at
 *    most one of these ever; a URL already on the row short-circuits before
 *    any work.
 *  - Tier 2 (`*Hq`) downloads the full-size photo/cover into `covers/` — once
 *    per unique file across sessions, cached on disk — and rewrites that row's
 *    cover/photo column, so one write reaches every surface reading the Flow
 *    (rows, shelf cards, player) at once. Callers pass the URL they already
 *    hold, so an already-upgraded row never issues an RPC.
 */
object Covers {

    /** A covers/hq_* URL means this row already carries full-size artwork. */
    const val HQ_PREFIX = "covers/hq_"

    /**
     * How long a completed "nothing there" answer stands. Long enough that a
     * coverless row scrolled past repeatedly costs one attempt, short enough
     * that a miss caused by being offline resolves itself within one session.
     */
    private const val MISS_TTL_MS = 5 * 60_000L

    private val active = ConcurrentHashMap<String, Boolean>()
    private val missed = MissMemo(MISS_TTL_MS)

    /** Four parallel covers fill a screenful in about one round of fetches. */
    private val gate = Semaphore(4)

    /**
     * App-lifetime scope for prefetches: a cover paid for while scrolling must
     * finish even if the row that asked for it has already left composition —
     * cancelling it would throw the download away and re-issue it next pass.
     */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** (channelId, messageId) from a track id ("tg_-100…_12345") or a videoId. */
    fun tgIds(videoId: String?): Pair<Long, Long>? {
        if (videoId.isNullOrBlank()) return null
        val trackId = SourceRegistry.parseTrackKey(videoId)?.second ?: videoId
        if (!trackId.startsWith("tg_")) return null
        val body = trackId.removePrefix("tg_")
        val cut = body.lastIndexOf('_')
        if (cut <= 0) return null
        val chatId = body.substring(0, cut).toLongOrNull() ?: return null
        val messageId = body.substring(cut + 1).toLongOrNull() ?: return null
        if (chatId == 0L || messageId <= 0L) return null
        return chatId to messageId
    }

    /** Tier 1 for one track; [current] already on the row short-circuits. */
    suspend fun ensureTrackCover(chatId: Long, messageId: Long, current: String?): String? {
        if (!current.isNullOrBlank()) return current
        return fetchOnce("t:$chatId:$messageId") {
            val url = Telegram.client.getAudioByMessage(chatId, messageId)?.coverUrl
            com.music.bitchord.data.TrackLog.w(
                "Covers",
                "coverProbeT1[$chatId/$messageId] inline coverUrl=${url ?: "null"}"
            )
            if (url.isNullOrBlank()) return@fetchOnce null
            TelegramDb.trackDao.setCoverIfEmpty(chatId, messageId, url)
            url
        }
    }

    /** Tier 1 driven by whatever videoId/Song a row was handed. */
    suspend fun ensureTrackCover(videoId: String?, current: String?): String? {
        val ids = tgIds(videoId) ?: return current
        return ensureTrackCover(ids.first, ids.second, current)
    }

    /** Tier 1 for one chat avatar; GetChat itself is served locally by TDLib. */
    suspend fun ensureChatPhoto(chatId: Long, current: String?): String? {
        if (!current.isNullOrBlank()) return current
        return fetchOnce("p:$chatId") {
            val url = Telegram.client.getChatInfo(chatId)?.photoUrl
            if (url.isNullOrBlank()) return@fetchOnce null
            TelegramDb.chatDao.setPhotoUrlIfEmpty(chatId, url)
            url
        }
    }

    /** Tier 2: the full-size cover behind a track id; falls back to [current]. */
    suspend fun trackCoverHq(videoId: String?, current: String?): String? {
        val ids = tgIds(videoId) ?: return current
        return trackCoverHq(ids.first, ids.second, current)
    }

    /** Tier 2: the full-size cover for one track; falls back to [current]. */
    suspend fun trackCoverHq(chatId: Long, messageId: Long, current: String?): String? {
        if (!current.isNullOrBlank() && current.contains(HQ_PREFIX)) return current
        return fetchOnce("ht:$chatId:$messageId") {
            // Null on purpose, not `current`: a null is what the miss memo
            // sees, so a row whose message has no full-size file stops asking
            // for five minutes instead of once per rebind. The caller's
            // `?: current` still shows the inline cover it already holds.
            val url = Telegram.client.downloadTrackCover(chatId, messageId) ?: return@fetchOnce null
            TelegramDb.trackDao.setCover(chatId, messageId, url)
            url
        } ?: current
    }

    /** Tier 2: the full-size photo for one chat; falls back to [current]. */
    suspend fun chatPhotoHq(chatId: Long, current: String?): String? {
        if (!current.isNullOrBlank() && current.contains(HQ_PREFIX)) return current
        return fetchOnce("hp:$chatId") {
            val url = Telegram.client.downloadChatPhoto(chatId) ?: return@fetchOnce null
            TelegramDb.chatDao.setPhotoUrl(chatId, url)
            url
        } ?: current
    }

    /**
     * What a row calls: Tier 2 first — the full-size cover, written back to the
     * row so every surface sharpens from one fetch. Tier 1 runs only when HQ
     * could not land (no full-size file, unauthorized), filling the empty cell
     * from the inline minithumbnail. A URL already on the row short-circuits
     * both before any RPC.
     */
    suspend fun improveTrackCover(
        videoId: String?,
        current: String?,
        title: String? = null,
        artist: String? = null,
    ): String? {
        val hq = trackCoverHq(videoId, current)
        if (hq != current) return hq
        val base = ensureTrackCover(videoId, current)
        if (!base.isNullOrBlank()) return base
        // Tier 3: the message carries no album cover at all (files uploaded
        // without embedded art) — ask an external sleeve lookup (iTunes).
        // fetchOnce keys per track, so a miss stays memoized and a hit lands
        // on the row through setCoverIfEmpty like any other tier.
        if (videoId == null) return current
        val ids = tgIds(videoId) ?: return current
        return fetchOnce("x:${ids.first}:${ids.second}") {
            val url = Telegram.client.externalCoverUrl(ids.first, ids.second, title, artist)
            if (url.isNullOrBlank()) return@fetchOnce null
            TelegramDb.trackDao.setCoverIfEmpty(ids.first, ids.second, url)
            url
        } ?: current
    }

    /**
     * Below this, the full-bleed player sleeve is upscale territory: the hero
     * region is most of the screen (~1080 px on this phone) and a 90 px file
     * stretched across it is a 12× blow-up — the blur the player was noticed
     * for. 400 is deliberately the size tier-3 already lands, so those covers
     * pass untouched and the threshold never spends a byte on artwork that is
     * already good enough.
     */
    const val PLAYER_MIN_PX = 400

    /**
     * Path behind a LOCAL cover URL (`file:///…`), or null when the URL is
     * remote or the file is gone — only a file on disk can be measured for
     * free, which is what [localCoverSize] and [upgradePlayerCover] need.
     */
    fun localCoverPath(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (!raw.startsWith("file://")) return null
        val file = File(raw.removePrefix("file://"))
        return if (file.isFile) file.path else null
    }

    /**
     * The cover's native pixel size: a bounds-only decode (header read — no
     * pixels, no network), or null when it cannot be had: remote URL, missing
     * file, unparseable image.
     */
    suspend fun localCoverSize(url: String?): Pair<Int, Int>? {
        val path = localCoverPath(url) ?: return null
        return withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // decodeFile answers null for a bounds-only read by design; the
            // options it filled in are the answer.
            runCatching { BitmapFactory.decodeFile(path, opts) }
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        }
    }

    /**
     * Tier 4 — the player's ask, and the only caller that spends it:
     * [improveTrackCover] first, then — only when the cover that landed is
     * still smaller than [PLAYER_MIN_PX], measured off the local file with no
     * request at all — the same external catalogues at ~1000 px (≈80 KB, and
     * only once per track ever: the file lands in covers/itl_). The row is
     * OVERWRITTEN rather than filled-if-empty, because the whole point is to
     * replace the small cover; every other surface sharpens from that one
     * write. Covers already large, still on http, or with nothing to match
     * return with zero extra requests.
     */
    suspend fun upgradePlayerCover(
        videoId: String?,
        current: String?,
        title: String? = null,
        artist: String? = null,
    ): String? {
        val base = improveTrackCover(videoId, current, title, artist)
        val ids = tgIds(videoId) ?: return base
        // An `it_*` file is tier-3's own product at the catalogue's SMALL
        // size: it clears the pixel gate on purpose, but the large twin is
        // exactly what this tier exists for — `itl_` already on disk answers
        // in one bounds read with zero requests, and a first-ever large
        // lookup is one memoised pass per track. Telegram's own covers must
        // still clear the pixel gate, or every play would re-run the lookup
        // tier-3 already answered.
        val tier3Sleeve = localCoverPath(base ?: current)?.contains("/it_") == true
        val size = localCoverSize(base ?: current) ?: return base
        if (minOf(size.first, size.second) >= PLAYER_MIN_PX && !tier3Sleeve) return base
        // Tier 0: the artwork carried inside the audio file itself — the
        // sleeve the release shipped with, read off the very bytes being
        // played, so no network and no catalogue guess can put the wrong
        // picture on the row. This is what turns a title posted WITHOUT a
        // cover (message thumb only, ~90 px) back into a full-size sleeve
        // on the next play. Deliberately NOT miss-memoized: GetFile answers
        // locally and the parse reads only the header, so a file still
        // streaming simply fails THIS pass and the next play finds the
        // finished bytes — a memo would freeze the row on a temporary state.
        val ekey = "e:${ids.first}:${ids.second}"
        val embedded = if (active.putIfAbsent(ekey, true) != null) {
            null // another upgrade of this track is already mid-flight
        } else {
            try {
                withContext(Dispatchers.IO) {
                    val track = TelegramDb.trackDao.getByChatAndMessage(ids.first, ids.second)
                        ?: return@withContext null
                    val art = Telegram.client.embeddedTrackCoverUrl(track.fileId, track.fileUniqueId)
                    if (art != null) {
                        // The row keeps it for lists and every future
                        // session: the cover belongs to the release, not
                        // to this player instance.
                        TelegramDb.trackDao.setCover(ids.first, ids.second, art)
                    }
                    art
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            } finally {
                active.remove(ekey)
            }
        }
        if (embedded != null) {
            val embeddedSize = localCoverSize(embedded)
            // Below the player gate it is no better than what is already
            // there — carry on to the external catalogues instead.
            if (embeddedSize != null && minOf(embeddedSize.first, embeddedSize.second) >= PLAYER_MIN_PX) {
                return embedded
            }
        }
        return fetchOnce("xl:${ids.first}:${ids.second}") {
            val big = Telegram.client.externalCoverUrl(
                ids.first, ids.second, title, artist, large = true,
            )
            if (big.isNullOrBlank()) return@fetchOnce null
            TelegramDb.trackDao.setCover(ids.first, ids.second, big)
            big
        } ?: base
    }

    /** [improveTrackCover] for a channel avatar. */
    suspend fun improveChatPhoto(chatId: Long, current: String?): String? {
        val hq = chatPhotoHq(chatId, current)
        return if (hq != current) hq else ensureChatPhoto(chatId, current)
    }

    /**
     * Warms covers for [rows] — id, the url the row already holds, and the
     * title/performer a Tier 3 external lookup needs — ahead of the viewport,
     * called when scrolling settles. Rows that already carry full-size art are
     * skipped outright; the rest join the same [gate] and dedupe as any row
     * fetch, so a prefetch racing the row's own effect costs one fetch, not
     * two. Skipped when unauthorized: the rows' own effects wait for Ready
     * themselves, and queueing prefetches behind a login nobody has performed
     * yet would only park coroutines.
     */
    fun prefetchTrackCovers(rows: List<Row>) {
        if (Telegram.client.authState.value !is TelegramAuthState.Ready) return
        for ((videoId, current, title, artist) in rows) {
            if (!current.isNullOrBlank() && current.contains(HQ_PREFIX)) continue
            prefetchScope.launch { improveTrackCover(videoId, current, title, artist) }
        }
    }

    /** One prefetch row; title/artist feed the Tier 3 external sleeve lookup. */
    data class Row(
        val videoId: String,
        val url: String?,
        val title: String? = null,
        val artist: String? = null,
    )

    /**
     * Runs [block] once per key: a second caller for the same cover finds the
     * key taken and returns null instead of racing the first — the winner's DB
     * write reaches the loser's row through its Flow anyway. Null also means
     * "keep what you have" — a request is already in flight, or the memo says
     * there is nothing to fetch.
     *
     * Auth is *awaited*, not checked: rows compose while TDLib is still
     * connecting, and the caller's effect keys (id, url) don't change when
     * Ready lands — an early return stranded the row coverless for the whole
     * session, which is how "some channels never load" read from the UI. The
     * wait holds no gate permit and no key, so it costs one suspended
     * coroutine per visible row and resolves the moment authorization does.
     *
     * Deliberately NOT silenced while a scan is indexing — fullScan() runs for
     * minutes on every launch, so a scan gate would freeze covers exactly when
     * the user first opens the app, which is when the blur was noticed. What
     * keeps this light instead: the gate caps all cover traffic at four files
     * in flight, only rows on screen and one prefetch window call in (never
     * the whole library), and the key, the miss memo, and the callers' URL
     * short-circuit cap each file at one fetch per TTL. GetChat/GetMessage
     * answer from TDLib's local cache; only the final small photo file touches
     * the net. Disk work happens off the main thread; everything the caller
     * wanted it for (a row's avatar) has already failed over to the placeholder
     * by then.
     *
     * A CancellationException is rethrown WITHOUT memoizing: a row flung past
     * says nothing about whether its cover exists, and memoizing it would
     * blank that cover for the rest of the TTL.
     */
    private suspend fun fetchOnce(key: String, block: suspend () -> String?): String? {
        if (missed.isMissed(key)) return null
        if (Telegram.client.authState.value !is TelegramAuthState.Ready) {
            Telegram.client.authState.first { it is TelegramAuthState.Ready }
        }
        if (active.putIfAbsent(key, true) != null) return null
        return try {
            val url = withContext(Dispatchers.IO) { gate.withPermit { block() } }
            if (url == null) missed.record(key)
            url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            missed.record(key)
            null
        } finally {
            active.remove(key)
        }
    }
}

/**
 * Session memo of "this cover does not exist": key → expiry epoch millis.
 * Injectable clock so the TTL rule is unit-testable without sleeping.
 */
internal class MissMemo(
    private val ttlMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val expiry = ConcurrentHashMap<String, Long>()

    fun isMissed(key: String): Boolean = expiry[key]?.let { now() < it } ?: false

    fun record(key: String) {
        expiry[key] = now() + ttlMs
    }
}
