package com.extrumz.app.data.telegram

import com.extrumz.app.data.TrackLog
import com.extrumz.app.data.db.TelegramDb
import com.extrumz.app.data.db.dao.ChatDao
import com.extrumz.app.data.db.dao.TrackDao
import com.extrumz.app.data.db.entity.ChatSource
import com.extrumz.app.data.db.entity.ChatType
import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.db.entity.TrackSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import java.util.Collections
import java.io.File

/**
 * Which of the four sub-systems the scan is inside right now.
 *
 * A scan is not one operation. It talks to TDLib's dialog list, then resolves
 * candidate chats over the network, then paginates audio per chat, then
 * finishes. Treating that as one flat counter is what left the screen reading
 * "Attempt 4" while nothing at all was happening — the label counted a poll
 * that finishes early and then never reset.
 */
enum class ScanStage {
    IDLE,
    /** TDLib is still pulling dialogs; the count is settling. */
    SYNCING_DIALOGS,
    /** Candidate chats are being resolved (audio search, contacts, profiles). */
    RESOLVING_SOURCES,
    /** Audio is being paginated per chat and indexed. */
    INDEXING_TRACKS,
    FINISHED,
}

data class ScanProgress(
    val isRunning: Boolean = false,
    val stage: ScanStage = ScanStage.IDLE,
    /** The stable chat count the dialog poll settled on, 0 until it does. */
    val detectedChats: Int = 0,
    /** Resolved candidates over total discovered — RESOLVING_SOURCES. */
    val resolvedChats: Int = 0,
    val chatsScanned: Int = 0,
    /**
     * How far along the WALK actually is, 0f..1f, counting a chat's pages as
     * it fetches them instead of only when it finishes.
     *
     * [chatsScanned] moves in steps because it only ticks on completion: a
     * chat holding 2,000 tracks pages for ~20 network round trips and reports
     * nothing at all during that time, so the counter freezes. The freeze is
     * indistinguishable from a stall and it lands on the same values every
     * scan, because the heavy chats are the same chats. This advances on
     * every page, so the bar moves while the work is being done.
     */
    val workFraction: Float = 0f,

    /**
     * When the page now in flight was asked for, or null when none is.
     *
     * The counters move on completion, so a page that takes twenty seconds
     * reports nothing at all for twenty seconds, and a frozen counter is
     * indistinguishable from a hung scan. This is published *before* the call,
     * so the banner can count the wait out loud and say what is being waited
     * on. It is a start time rather than a duration because the scan cannot
     * publish while it is blocked inside the call it is timing.
     */
    val pageStartedAt: Long? = null,
    val totalChats: Int = 0,
    val tracksIndexed: Int = 0,
    /** GetChats attempts spent waiting for dialogs to load (0 = not waiting). */
    val attempts: Int = 0,
    /** Banner line — a short, possibly technical reason for the failure. The UI renders the translated title, never this raw; the untranslated cause sits in [lastErrorDetail]. */
    val lastError: String? = null,
    /**
     * The raw message behind [lastError] (e.g. "database disk image is
     * malformed"), shown small and muted under the banner title so a real
     * failure is not blamed on the Telegram connection alone.
     */
    val lastErrorDetail: String? = null
)

/**
 * Scans Telegram chats for audio messages and indexes them as [Track]s.
 * Full scans cover all tracked chats; [scanChat]'s cursor/paging is what a
 * future incremental watcher will build on (its one caller, the old
 * `incrementalScan`, had none and was removed — see PATCH_3_REPORT).
 */
class LibraryScanner(
    private val client: MusicTelegramClient,
    private val trackDao: TrackDao,
    private val chatDao: ChatDao,
    private val timeSource: () -> Long = { System.currentTimeMillis() }
) {

    /** Chats already file-probed this process — one GetFile verdict per chat. */
    private val probedChats: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    /**
     * One scan at a time. onReady's auto-scan and a manual tap arrive on
     * DIFFERENT threads milliseconds apart (observed in logcat), so the old
     * read-then-write on the StateFlow let both through — two scans would
     * race the same pages. CAS makes the loser a no-op.
     */
    private val scanGate = AtomicBoolean(false)

    /**
     * Chat ids TDLib itself reported for the pass in flight — main list plus
     * archive. Membership here is what separates "TDLib knows this chat but
     * the search failed" from "TDLib has no such chat at all", and only the
     * second one may untrack a row: reading a plain "Chat not found" as
     * "left Telegram" untracked 18 live archived channels on 2026-10-07,
     * because [withShelfExtras] sees archived dialogs as *missing from the
     * slice* — the same symptom a genuinely deleted chat has. Written by
     * [withShelfExtras] under [scanGate] and read by the scans that gate
     * launches, so a single field is enough.
     */
    @Volatile
    private var listedChats: Set<Long> = emptySet()

    /**
     * Live indexing: fresh audios are indexed the moment they arrive instead
     * of waiting for the next full scan. The client publishes EVERY incoming
     * audio message; only TRACKED chats contribute to the library, so a
     * stray private chat never leaks in. Started from the auth controller's
     * onReady — [liveStarted] makes repeat calls no-ops.
     */
    private val liveStarted = AtomicBoolean(false)

    fun startLiveIndexing(scope: CoroutineScope) {
        if (!liveStarted.compareAndSet(false, true)) return
        scope.launch {
            client.newAudioMessages.collect { audio ->
                runCatching {
                    if (chatDao.getById(audio.chatId)?.isTracked != true) return@collect
                    indexAudio(audio)
                }.onFailure {
                    TrackLog.w(TAG, "live index failed for chat ${audio.chatId}: ${it.message}")
                }
            }
        }
    }

    // --- F7: one row per song ------------------------------------------------

    /**
     * The dedupe key, delegated to [TrackFingerprinter] so both this file's
     * insert-time check and its whole-table pass agree on what counts as the
     * same song. See that object for why the raw strings were not enough.
     */
    private typealias Fingerprint = TrackFingerprinter.Fingerprint

    @Volatile
    /** Convergence runs once per process; see [convergeLeftoverDuplicates]. */
    private var convergedOnce = false

    private var fingerprintIndex: MutableMap<Fingerprint, String>? = null

    /** Serializes fp → keeper decisions: six chats scan in parallel. */
    private val fingerprintLock = Mutex()

    /**
     * In-flight work in thousandths of a chat, so the running total stays an
     * integer and needs no lock. Completed chats add 1000 outright; a chat
     * mid-walk adds its page fraction and then the remainder when it settles.
     * Monotonic by construction: every contribution is positive and none is
     * ever subtracted, so the bar cannot travel backwards.
     */
    private val workMilli = java.util.concurrent.atomic.AtomicLong(0)

    /** Chats this scan intends to walk; the denominator for [workFraction]. */
    private var scanChatTotal = 0

    /**
     * Publish [ScanProgress.workFraction]. Called on every page and on every
     * completion, so the value moves continuously instead of in steps.
     */
    private fun publishWork() {
        val total = scanChatTotal
        if (total <= 0) return
        val fraction = (workMilli.get() / 1000f) / total
        _progress.update { it.copy(workFraction = fraction.coerceIn(0f, 1f)) }
    }

    private fun fingerprintOf(track: Track): Fingerprint =
        TrackFingerprinter.fingerprintOf(track)

    private fun fingerprintOf(row: com.extrumz.app.data.db.dao.FingerprintRow): Fingerprint =
        TrackFingerprinter.fingerprintOf(row.performer, row.title, row.duration)

    /**
     * One whole-table load per process: collapses every pre-existing
     * duplicate group (same song under different Telegram file ids — the
     * reposts found in production) and returns fp → keeper id for the
     * insert-time drop. Keeper = lowest file id, deterministic; playlist and
     * history rows are re-pointed onto it and favorites/plays merged, so
     * nothing user-owned is lost. Afterwards the scanner owns every writer
     * of those columns and maintains the map incrementally; a row deleted
     * outside it is re-checked at drop time (getById), which can cost a
     * duplicate, never a song. Idempotent: a second load finds groups of
     * one and does zero writes.
     */
    private suspend fun fingerprintIndex(): MutableMap<Fingerprint, String> {
        fingerprintIndex?.let { return it }
        // Light projection first: this holds the whole library in memory for
        // the session, and the full row carries lyrics/caption/tags that never
        // reach the key. Every install after the first merge finds groups of
        // one here and pays nothing.
        val light = trackDao.loadFingerprintRows()
        val grouped = light.groupBy { TrackFingerprinter.fingerprintOf(it.performer, it.title, it.duration) }
        val index = HashMap<Fingerprint, String>(light.size)
        val duplicatedIds = mutableListOf<String>()
        for ((fp, group) in grouped) {
            val ordered = group.sortedBy { it.fileUniqueId }
            index[fp] = ordered.first().fileUniqueId
            if (ordered.size > 1) duplicatedIds += group.map { it.fileUniqueId }
        }
        if (duplicatedIds.isEmpty()) {
            return index.also { fingerprintIndex = it }
        }
        // Only now touch the heavy columns, and only for the rows that need it.
        val heavy = trackDao.getForMerge(duplicatedIds).groupBy {
            TrackFingerprinter.fingerprintOf(it.performer, it.title, it.duration)
        }
        val losers = mutableListOf<String>()
        for ((fp, group) in heavy) {
            val ordered = group.sortedBy { it.fileUniqueId }
            val winner = ordered.first()
            if (ordered.size == 1) continue
            val collapsed = ordered.drop(1)
            losers += collapsed.map { it.fileUniqueId }
            collapsed.forEach { trackDao.repointToKeeper(it.fileUniqueId, winner.fileUniqueId) }
            trackDao.update(
                winner.copy(
                    isFavorite = ordered.any { it.isFavorite },
                    playCount = ordered.sumOf { it.playCount },
                    lastPlayed = ordered.mapNotNull { it.lastPlayed }.maxOrNull(),
                    mediaAlbumId = winner.mediaAlbumId
                        ?: ordered.mapNotNull { it.mediaAlbumId }.firstOrNull(),
                ),
            )
            index[fp] = winner.fileUniqueId
        }
        if (losers.isNotEmpty()) {
            trackDao.deleteByFileUniqueIds(losers)
            TrackLog.w(TAG, "F7: merged ${losers.size} duplicate rows away (reposts of one song)")
        }
        return index.also { fingerprintIndex = it }
    }

    /**
     * Collapse what the inline pass could not see.
     *
     * batchIndex folds every duplicate it is shown, in RAM, on the way in.
     * Two things still escape it: rows written before the fingerprint index
     * existed, and a keeper chosen before a better copy of the same song
     * arrived. Measuring first is the whole point — on a library with no
     * collisions this costs one COUNT inside SQLite and nothing else, instead
     * of the whole-table load it used to pay unconditionally. When there is
     * something to do, the index is simply dropped and rebuilt, which reuses
     * the same merge path rather than a second implementation of it.
     */
    private suspend fun convergeLeftoverDuplicates() {
        // Once per process, not once per scan: the index is already folded by
        // then and the second run finds groups of one and writes nothing.
        //
        // The obvious gate was a SQL count over (performer, title, duration),
        // and it is wrong in a way that cost me a whole test run: F7 compares
        // NORMALIZED fingerprints, so "A & B", "A,B" and "a & b" are one song
        // to it and three different strings to SQLite. The query answered 0
        // while a duplicate group was sitting in the table. Only the
        // fingerprint can see that pair, and computing it needs the rows — so
        // the honest fix is to bound the work instead of pre-judging it.
        if (convergedOnce) return
        convergedOnce = true
        if (fingerprintIndex == null) return
        _progress.update { it.copy(stage = ScanStage.INDEXING_TRACKS) }
        TrackLog.w(TAG, "F7: post-scan convergence pass")
        fingerprintIndex = null
        fingerprintIndex()
    }

    suspend fun fullScan() {
        // F7: collapse pre-existing duplicates even when every chat is already
        // scanned to the latest message (batchIndex never runs then).
        fingerprintIndex()
        if (!scanGate.compareAndSet(false, true)) return
        // Heaviest first. getTrackedOnce() has no ORDER BY, so the shelf used
        // to arrive in whatever order SQLite happened to return rows — and the
        // first SCAN_PARALLEL_CHATS permits went to whichever chats landed at
        // the front. On a real account the music is concentrated in a handful
        // of heavy channels, so an arbitrary order could hand all six permits
        // to chats with nothing in them and leave the library looking empty
        // while the work was already done. Sorting by trackCount makes the
        // first pages produce tracks immediately, and the light chats — most
        // of them settled at -1 and one page each — cost almost nothing at the
        // end. Cheap rows are paid last, not first.
        val shelf = chatDao.getTrackedOnce()
            .filter { it.isTracked }
            .sortedByDescending { it.trackCount }
        val hasShelf = shelf.isNotEmpty()
        _progress.value = ScanProgress(
            isRunning = true,
            stage = if (hasShelf) ScanStage.INDEXING_TRACKS else ScanStage.SYNCING_DIALOGS,
            totalChats = shelf.size,
            attempts = 0,
        )
        try {
            val seenChatIds = Collections.synchronizedSet(shelf.map { it.chatId }.toHashSet())
        // Denominator for the fractional bar: every chat this scan will walk.
        // Grown as discovery finds more, and the numerator grows with it —
        // both sides move, so the ratio stays honest.
        scanChatTotal = shelf.size
        workMilli.set(0L)
            val channel = Channel<ChatInfo>(Channel.UNLIMITED)
            shelf.forEach { channel.trySend(it.asChatInfo()) }

            // No Semaphore here: launching exactly SCAN_PARALLEL_CHATS workers
            // that each take one of SCAN_PARALLEL_CHATS permits bounds nothing
            // a second time — the worker count already IS the bound, and the
            // semaphore only added a second suspend/resume on every chat.
            coroutineScope {
                val workers = List(SCAN_PARALLEL_CHATS) {
                    launch {
                        for (chat in channel) scanOneChat(chat)
                    }
                }
                launch {
                    val candidates = awaitCandidateChats()
                    // Channels and supergroups first, private chats last. On a
            // from-zero scan every trackCount is 0, so the shelf sort above
            // cannot rank anything — and discovery hands these over in
            // Main-list order, which is RECENCY: the chats a user talks in
            // most sit at the front and hold no music, while the archive
            // channels holding thousands of tracks sit at the back. A chat
            // type is the only heavy-signal available before the first page,
            // and it is a real one: channels carry the libraries, DMs almost
            // never do.
            val fresh = candidates
                .filter { seenChatIds.add(it.chatId) }
                .sortedByDescending { it.isChannel || it.isGroup }
            // Grow the denominator WITH the numerator. On a fresh install the
            // shelf is empty, so scanChatTotal starts at 0 and every
            // publishWork() returns early — the fraction would sit at 0f for
            // the entire first run. totalChats was grown here but this was
            // not, and that gap only shows on the one path a warm rescan
            // never takes.
            scanChatTotal += fresh.size
                    if (fresh.isNotEmpty()) {
                        _progress.update {
                            it.copy(
                                stage = ScanStage.INDEXING_TRACKS,
                                totalChats = it.totalChats + fresh.size,
                                attempts = 0,
                            )
                        }
                        fresh.forEach { channel.send(it) }
                    }
                    if (shelf.isEmpty() && fresh.isEmpty()) {
                        val reason = "no candidate chats after ${CHATS_LOAD_TIMEOUT_MS}ms"
                        _progress.update {
                            it.copy(
                                lastError = reason,
                                lastErrorDetail = reason,
                                attempts = 0,
                            )
                        }
                    }
                    channel.close()
                }
                workers.joinAll()
            }
            if (shelf.isNotEmpty() || seenChatIds.isNotEmpty()) {
                convergeLeftoverDuplicates()
            }
            _progress.update {
                it.copy(
                    isRunning = false,
                    stage = if (shelf.isEmpty() && seenChatIds.isEmpty()) ScanStage.IDLE else ScanStage.FINISHED,
                )
            }
        } catch (e: Exception) {
            // Cancelling the scan (scope shutdown) is not a scan failure and
            // must not be swallowed into lastError.
            if (e is CancellationException) throw e
            // Full exception (with stack) to the log; the banner gets the
            // message twice over — as the line and as the detail under it.
            TrackLog.w(TAG, "fullScan failed: ${e::class.simpleName}: ${e.message}", e)
            _progress.update {
                it.copy(
                    isRunning = false,
                    lastError = e.message ?: e::class.simpleName,
                    lastErrorDetail = e.message ?: e::class.simpleName,
                )
            }
        } finally {
            // Clear isRunning FIRST while the gate still excludes any other
            // scan (so the flag can only be ours), then release the gate — a
            // cancelled scan must not leave the banner/button stuck.
            _progress.update { if (it.isRunning) it.copy(isRunning = false) else it }
            scanGate.set(false)
        }
    }

    /**
     * A silent second pass, [QUIET_REPASS_DELAYS_MS] after the first scan: no
     * banner, no button state, no progress. A scan that starts seconds after
     * Ready races TDLib's dialog sync — the least-active chats answer the
     * audio search with an empty page, keep lastScannedMessageId = 0, and are
     * missed by EVERY auto-scan (which always starts at Ready + seconds).
     * Nothing fails and nothing logs: the same 1-4 chats stayed at bound 0
     * across launches while a scan minutes later found them all (device log,
     * 2026-10-06). Retrying them once the sync settles closes the hole
     * without touching the first pass's timing.
     *
     * Quietly gives up the whole pass if a user-started scan owns the gate.
     */
    /**
     * Clear a finished scan's failure line.
     *
     * The banner had no way out: once a scan failed, the red line sat on the
     * screen until the NEXT scan happened to overwrite it, so a failure from a
     * week ago was still being reported. Nothing else writes lastError, so
     * clearing it here cannot race a live scan.
     */
    fun dismissError() {
        _progress.update { if (it.lastError == null) it else it.copy(lastError = null, lastErrorDetail = null) }
    }

    suspend fun quietRepass() {
        for (delayMs in QUIET_REPASS_DELAYS_MS) {
            // Cheap Room check: if no tracked chat is left at boundary 0,
            // there is nothing to retry — exit immediately without delaying or scanning.
            val unscannedInitial = chatDao.getTrackedOnce()
                .filter { it.lastScannedMessageId == 0L }
                .map { it.chatId }
                .toHashSet()
            if (unscannedInitial.isEmpty()) return

            delay(delayMs)
            // A scan started meanwhile owns the candidates; retrying now would race it.
            if (!scanGate.compareAndSet(false, true)) return
            try {
                val unscanned = chatDao.getTrackedOnce()
                    .filter { it.lastScannedMessageId == 0L }
                    .map { it.chatId }
                    .toHashSet()
                if (unscanned.isEmpty()) return

                val candidates = withShelfExtras(client.listTrackedCandidateChats())
                if (candidates.isEmpty()) continue
                val targets = candidates.filter { it.chatId in unscanned }
                if (targets.isEmpty()) return
                val semaphore = Semaphore(SCAN_PARALLEL_CHATS)
                coroutineScope {
                    targets.forEach { info ->
                        launch { semaphore.withPermit { scanOneChat(info, settleEmpty = true) } }
                    }
                }
                TrackLog.d(TAG, "quiet re-pass: retried ${targets.size} unscanned candidates")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "quiet re-pass failed: ${e::class.simpleName}: ${e.message}", e)
            } finally {
                scanGate.set(false)
            }
        }
    }

    /**
     * Upserts one chat's row and scans it. A failure here is contained: it is
     * logged, surfaced on [ScanProgress.lastError], and the chat still counts
     * toward [ScanProgress.chatsScanned] so progress always reaches
     * [ScanProgress.totalChats]. Only cancellation escapes to cancel the run.
     */
    private suspend fun scanOneChat(info: ChatInfo, settleEmpty: Boolean = false) {
        try {
            val existing = chatDao.getById(info.chatId)
            // Honour the user's choice at the door. Discovery answers whether a
            // chat EXISTS, not whether it should be scanned — and the row's own
            // cursor is the resume boundary, read-only here.
            if (existing != null && !existing.isTracked) {
                TrackLog.d(TAG, "chat ${info.chatId} untracked — skipping")
                return
            }
            // The row write stays, but it PASSES every field it does not own.
            // Dropping it broke the resume boundary: updateScanProgress below
            // is an UPDATE, and an UPDATE against no row silently does
            // nothing. What made the old form dangerous was the fields it left
            // out — REPLACE rewrote them to the entity defaults, wiping
            // discoveredAt/lastSeenAt and forcing isTracked = true. Named
            // explicitly, the upsert creates a missing row and otherwise only
            // refreshes identity.
            val type = when {
                info.isChannel -> ChatType.CHANNEL
                info.isGroup -> ChatType.SUPERGROUP
                else -> ChatType.PRIVATE
            }
            chatDao.upsert(
                ChatSource(
                    chatId = info.chatId,
                    title = info.title,
                    username = info.username,
                    photoUrl = preservedAvatar(existing, info),
                    type = type,
                    isTracked = existing?.isTracked ?: true,
                    lastScannedMessageId = existing?.lastScannedMessageId ?: 0L,
                    trackCount = existing?.trackCount ?: 0,
                    // A row created here is new, so its discovery time is
                    // NOW — not 1970. Only a row that already existed keeps
                    // the baseline the migration seeded.
                    discoveredAt = existing?.discoveredAt ?: timeSource(),
                    lastSeenAt = existing?.lastSeenAt ?: timeSource(),
                ),
            )
            scanChat(
                chatId = info.chatId,
                fromMessageId = 0L,
                full = true,
                stopBefore = existing?.lastScannedMessageId ?: 0L,
                settleEmpty = settleEmpty,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is TelegramClientException && e.message == "Chat not found") {
                if (info.chatId in listedChats) {
                    // TDLib listed this chat in main or archive for THIS pass,
                    // so it exists — "Chat not found" is a cache/lookup miss on
                    // a chat TDLib already reported, not a chat that left
                    // Telegram. Untracking here is what silently removed 18
                    // live archived channels on 2026-10-07 (the scan side of
                    // the same hole withShelfExtras had already noted). Keep
                    // the row, let the failure reach the banner below, and
                    // retry on the next pass instead of dropping history.
                    TrackLog.w(
                        TAG,
                        "chat ${info.chatId} answered \"Chat not found\" while TDLib lists it — kept tracked",
                    )
                } else {
                    // "Chat not found" from the OFFLINE GetChat says nothing
                    // about existence for a chat outside every list —
                    // tdlib/td#3673: live dialogs that never enter GetChats
                    // answer exactly this while alive on Telegram, and the
                    // old unconditional untrack here read that cache miss as
                    // a death certificate. Probe the server before deciding
                    // anything:
                    //  - true  → alive AND force-fetched into TDLib's cache
                    //    by the probe itself: keep the row and retry the
                    //    scan once right here, so this pass still indexes it;
                    //  - false → the server itself confirmed it's gone — the
                    //    untrack below, exactly as before (the 30 dead shelf
                    //    rows of 2026-10-06 still qualify; re-adding the chat
                    //    through the app re-tracks it);
                    //  - null  → the probe was inconclusive (transient
                    //    failure): nothing decided — keep the row and let the
                    //    banner below carry the error.
                    when (client.chatAlive(info.chatId)) {
                        true -> {
                            TrackLog.w(
                                TAG,
                                "chat ${info.chatId} is alive outside every list — " +
                                    "force-fetched, rescanning",
                            )
                            try {
                                scanChat(
                                    chatId = info.chatId,
                                    fromMessageId = 0L,
                                    full = true,
                                    // `existing` is scoped to the try above; re-read
                                    // the row here for the resume boundary.
                                    stopBefore = chatDao.getById(info.chatId)?.lastScannedMessageId
                                        ?: 0L,
                                )
                                return
                            } catch (retry: CancellationException) {
                                throw retry
                            } catch (retry: Exception) {
                                // The chat is proven alive; a second failure
                                // cannot untrack it — surface and keep.
                                TrackLog.w(
                                    TAG,
                                    "chat ${info.chatId} still fails after the force fetch: " +
                                        "${retry::class.simpleName}: ${retry.message}",
                                    retry,
                                )
                                _progress.update {
                                    it.copy(
                                        lastError = "chat ${info.chatId}: " +
                                            (retry.message ?: retry::class.simpleName),
                                        lastErrorDetail = retry.message
                                            ?: retry::class.simpleName,
                                    )
                                }
                                return
                            }
                        }
                        false -> {
                            chatDao.getById(info.chatId)?.let { row ->
                                chatDao.upsert(row.copy(isTracked = false))
                            }
                            TrackLog.w(TAG, "chat ${info.chatId} is gone from Telegram — untracked")
                            return
                        }
                        null -> TrackLog.w(
                            TAG,
                            "chat ${info.chatId} not verified (probe inconclusive) — kept tracked",
                        )
                    }
                }
            }
            // Full exception (with stack) to the log; the state keeps both the
            // per-chat banner line and the raw message as the detail under it.
            TrackLog.w(TAG, "chat ${info.chatId} failed: ${e::class.simpleName}: ${e.message}", e)
            _progress.update {
                it.copy(
                    lastError = "chat ${info.chatId}: ${e.message ?: e::class.simpleName}",
                    lastErrorDetail = e.message ?: e::class.simpleName,
                )
            }
        } finally {
            // Counted even for the failed chat, so the banner's fraction can
            // never stall below totalChats.
            _progress.update { it.copy(chatsScanned = it.chatsScanned + 1) }
        }
    }

    /**
     * Waits for TDLib to finish loading dialogs after authorization: right
     * after Ready, GetChats answers 0 while "Save chat to database…" is still
     * running ([fullScan]'s note), and a scan started against that answers
     * with nothing. Retries every [CHATS_RETRY_DELAY_MS] until the count has
     * held still for [CHATS_STABLE_POLLS] rounds or [CHATS_LOAD_TIMEOUT_MS]
     * runs out, then returns the list for the caller to surface.
     *
     * Waiting for the count to hold still rather than for a non-empty answer
     * is the whole point. TDLib hands back the dialogs it has synced SO FAR
     * and keeps pulling the rest in the background, so the first non-empty
     * answer on a fresh install is a partial one — and a chat missing from
     * that first answer is missing for good, because the scan sees only what
     * this returns and the quiet re-pass retries only chats the scan already
     * saw. That hole is what silently dropped channels from every scan until
     * this waited for stability.
     *
     * The stability poll is [MusicTelegramClient.countCandidateChatIds] — the
     * id set only. Asking for the full listing each round would repeat the
     * per-chat GetChat that dominates its cost, once per second.
     */
    /**
     * One scan's candidate set, in three steps that used to be one.
     *
     * 1. [discoverFromLists] asks TDLib what its chat lists hold right now —
     *    the only way to see a chat joined since the last run.
     * 2. [ingest] writes that into Room, stamping `discoveredAt` once and
     *    `lastSeenAt` on every pass, so discovery contributes evidence rather
     *    than authority.
     * 3. [withShelfExtras] unions the result with EVERY tracked row, built
     *    from the stored row rather than re-fetched — which is both what makes
     *    a chat survive a list that forgot it, and what keeps the per-scan cost
     *    off the GetChat path for chats nothing has changed.
     */
    private suspend fun awaitCandidateChats(): List<ChatInfo> {
        // The dialog poll is done; the network resolution is what the user is
        // waiting on now. The stage existed but was never published anywhere,
        // so the banner could not tell the two apart.
        _progress.update { it.copy(stage = ScanStage.RESOLVING_SOURCES) }
        val discovered = discoverFromLists()
        // How many candidates discovery actually resolved — the number a user
        // can watch while this phase runs.
        _progress.update { it.copy(resolvedChats = discovered.size) }
        ingest(discovered)
        return withShelfExtras(discovered)
    }

    /**
     * The polling wait: TDLib answers 0 while it is still pulling dialogs, and
     * a scan started against that would end in silence. Polls the cheap count
     * until it holds still, then takes the real listing once.
     *
     * When the count is 0 the real listing is asked instead: only it can fail,
     * and its failure is what the error banner exists to show — polling the
     * cheap count around a broken listing would burn the whole timeout with
     * nothing reaching the UI.
     */
    private suspend fun discoverFromLists(): List<ChatInfo> {
        // The wait is only worth its cost on a first run. With rows already in
        // Room the scan knows what to index before it starts, and the thing
        // the poll is really for — catching a chat joined since last time — is
        // something the quiet re-pass does anyway on its own schedule. Paying
        // the full two minutes on every launch was the longest stretch in the
        // whole scan and it bought almost nothing after the first one.
        val known = runCatching { chatDao.getTrackedOnce().size }.getOrDefault(0)
        val deadline = timeSource() +
            if (known == 0) CHATS_LOAD_TIMEOUT_MS else CHATS_SETTLED_TIMEOUT_MS
        var attempt = 0
        var lastCount = -1
        var stable = 0
        val recent = ArrayDeque<Int>()
        // NOTE: an earlier version compared countCandidateChatIds() against
        // chatDao.getTrackedOnce().size here, and it could never fire:
        // countCandidateChatIds() reads Main+Archive only (~240 on this
        // account) while Room holds the merged total from lists + folders +
        // contacts + audio (~287). 240 >= 287 is false on every launch. The
        // comparison has to be against the same universe it measures, so it
        // is left out until Room records where each chat came from.
        while (true) {
            attempt++
            // Published so the banners can show "attempt N" during the wait —
            // two minutes with no visible change reads as a hang.
            _progress.update {
                if (it.stage == ScanStage.SYNCING_DIALOGS) {
                    it.copy(attempts = attempt)
                } else {
                    it
                }
            }
            val count = client.countCandidateChatIds()
            if (count == 0) {
                return client.listTrackedCandidateChats()
            }
            // Growth, not equality. An exact-match test needs the count to
            // repeat, and on this account it does not: TDLib drips entities in
            // one and two at a time, so `stable` was reset on almost every
            // poll and the loop always ran to its deadline. A window that has
            // stopped moving *meaningfully* is the same evidence with a
            // trigger that can actually fire. The last few counts are kept and
            // the poll settles when their spread is within CHATS_SETTLE_SLACK.
            recent.addLast(count)
            if (recent.size > CHATS_SETTLE_WINDOW) recent.removeFirst()
            if (recent.size == CHATS_SETTLE_WINDOW) {
                val spread = (recent.max() - recent.min())
                if (spread <= CHATS_SETTLE_SLACK) stable++
            }
            lastCount = count
            // One honest number per poll instead of a bare attempt counter.
            // The old label kept its last value forever, so "Attempt 4" stayed
            // on screen long after the poll had finished.
            _progress.update {
                if (it.stage == ScanStage.SYNCING_DIALOGS) {
                    it.copy(attempts = attempt, detectedChats = count)
                } else {
                    it.copy(detectedChats = count)
                }
            }
            // Two matching samples after an ACTIVE loadChatList pump: the
            // pump itself reports 404 once TDLib has surfaced everything it
            // holds, so two seconds of zero growth under an active pump is
            // evidence, not a guess. The third only cost another full second.
            if (stable >= CHATS_STABLE_POLLS) {
                if (attempt > CHATS_STABLE_POLLS) {
                    TrackLog.d(TAG, "candidate chats settled at $count after $attempt attempts")
                }
                return client.listTrackedCandidateChats()
            }
            if (timeSource() >= deadline) {
                TrackLog.w(TAG, "candidate chats never settled ($count after $attempt attempts)")
                return client.listTrackedCandidateChats()
            }
            TrackLog.d(
                TAG,
                "candidate chats still loading (attempt $attempt, $count so far); " +
                    "retrying in ${CHATS_RETRY_DELAY_MS}ms",
            )
            delay(CHATS_RETRY_DELAY_MS)
        }
    }

    /**
     * Writes what discovery just saw into Room.
     *
     * Discovery answers with whatever its lists hold THIS pass, and on this
     * account that set is provably incomplete — TDLib reports its lists
     * complete while returning well under the account's real chat count. Left
     * unrecorded, a chat absent from one pass is absent from every later one.
     * Room becomes the memory: `discoveredAt` is written once and never moves,
     * `lastSeenAt` tracks freshness, and everything the row already owns (scan
     * cursor, track count, the user's tracked flag) is preserved.
     */
    private suspend fun ingest(discovered: List<ChatInfo>) {
        if (discovered.isEmpty()) return
        val now = timeSource()
        val written = chatDao.ingestDiscovered(discovered.map { it.asDiscovered(now) }, now)
        if (written > 0) {
            TrackLog.d(TAG, "ingest: $written of ${discovered.size} discovered rows changed")
        }
    }



    /**
     * Candidate set for one pass: whatever the main chat list reports, plus
     * every tracked chat that is still unscanned but missing from that list.
     * Archived or left dialogs never appear in `GetChats(ChatListMain)` — the
     * 2026-10-06 device run proved the hole: 31 tracked chats (16 of them with
     * music) received ZERO `SearchChatMessages` requests across three passes
     * while the other 129 were searched, and the same log carries a
     * `ChatListArchive` dump. The shelf row is synthesized here so those chats
     * stay scannable; if TDLib truly doesn't know one, `scanOneChat` fails out
     * loud instead of vanishing silently.
     */
    private suspend fun withShelfExtras(slice: List<ChatInfo>): List<ChatInfo> {
        val known = slice.mapTo(HashSet()) { it.chatId }
        listedChats = known
        // F8: a row with history that TDLib never lists is out of reach of
        // every repair path (the extras below only cover TRACKED rows) — no
        // scan means no cover refill, no file-id refresh, and (after the
        // stale-id guard landed) no playback refresh either. Probe each one:
        // GetChat answers from TDLib's database for any chat it still knows,
        // listed or not, so a reachable answer means the dialog was only
        // deleted/hidden from the lists while the chat and its messages
        // survived. Re-track those (and reset the scan cursor) so THIS pass
        // rescans them: covers refill, file ids refresh, playback returns.
        // A chat that fails GetChat logs its real error inside getChatInfo
        // ("getChatInfo(<id>) failed: …") and stays untouched — retrying a
        // genuinely gone chat forever would only re-log it on every launch.
        val orphans = chatDao.getAllOnce()
            .filter { it.trackCount > 0 && it.chatId !in known }
        if (orphans.isNotEmpty()) {
            var repaired = 0
            val unreachable = mutableListOf<String>()
            val unreachableIds = mutableListOf<Long>()
            for (row in orphans) {
                val info = client.getChatInfo(row.chatId)
                if (info == null) {
                    unreachable += "${row.chatId}(${row.trackCount})"
                    unreachableIds += row.chatId
                    continue
                }
                // Only a real defect triggers repair. `lastScannedMessageId != 0`
                // used to count as one, which meant every pass reset a healthy
                // orphan's cursor to 0 and rescanned its WHOLE history — an
                // unlisted chat with music was never caught up, only replayed.
                // A scanned cursor is a record, not a fault.
                // Avatar ONLY. The old `!row.isTracked` branch force-retracked
                // an unlisted chat on every pass, which made the UI's untrack
                // button a no-op for exactly the chats it was pressed on. The
                // 10-07 bug that motivated it ("Chat not found" marking live
                // channels untracked) is fixed at the source now, and
                // scanOneChat skips untracked rows — consent wins everywhere.
                val needsAvatar = row.photoUrl.isNullOrBlank() && !info.photoUrl.isNullOrBlank()
                if (needsAvatar) {
                    chatDao.upsert(
                        row.copy(
                            // The resume boundary is preserved: repair fixes a
                            // missing avatar or an untracked row, never the
                            // cursor.
                            photoUrl = if (row.photoUrl.isNullOrBlank()) {
                                info.photoUrl ?: row.photoUrl
                            } else {
                                row.photoUrl
                            },
                        ),
                    )
                    repaired++
                }
            }
            TrackLog.w(
                TAG,
                "orphans: ${orphans.size} rows with history absent from every TDLib list: " +
                    orphans.joinToString(",") { "${it.chatId}(${it.trackCount})" } +
                    " — repaired=$repaired reachable, unreachable=${unreachable.size}" +
                    if (unreachable.isEmpty()) "" else ": ${unreachable.joinToString(",")}",
            )
            // One GetFile per dead chat answers from TDLib's file table
            // without downloading anything — the verdict says whether tier-3
            // playback can still pull these rows although the chat itself is
            // gone (covers still need the message, so they stay impossible).
            if (unreachableIds.isNotEmpty()) {
                val sample = trackDao.observeAllOnce()
                    .filter { it.sourceChatId in unreachableIds }
                    .groupBy { it.sourceChatId }
                for ((cid, rows) in sample) {
                    if (!probedChats.add(cid)) continue
                    val verdict = client.probeFile(rows.first().fileId)
                    TrackLog.w(
                        TAG,
                        "file probe chat=$cid -> " +
                            (verdict ?: "file ALIVE - tier-3 can pull it"),
                    )
                    // Local-art rescue: with the chat gone nothing can fetch a
                    // cover or an avatar, but an inline thumbnail already saved
                    // under covers/<fileId>.jpg survives — point the empty rows
                    // at it and give the shelf the first one as its avatar.
                    runCatching {
                        val covers = File(Telegram.context.filesDir, "covers")
                        var shelf: String? = null
                        for (track in rows) {
                            if (track.coverUrl.isNotBlank()) {
                                if (shelf == null) shelf = track.coverUrl
                                continue
                            }
                            val cached = File(covers, "${track.fileId}.jpg")
                            if (!cached.isFile) continue
                            val url = "file://${cached.absolutePath}"
                            trackDao.setCoverIfEmpty(
                                track.sourceChatId,
                                track.sourceMessageId,
                                url,
                            )
                            if (shelf == null) shelf = url
                        }
                        if (shelf != null) chatDao.setPhotoUrlIfEmpty(cid, shelf)
                    }.onFailure {
                        TrackLog.w(TAG, "local art rescue failed for chat=$cid: ${it.message}")
                    }
                }
            }
        }
        // EVERY tracked row outside this pass's lists, not only the ones that
        // have never been scanned. The old `lastScannedMessageId == 0L` gate
        // meant a chat that HAD been scanned and then dropped out of TDLib's
        // lists was never offered as a candidate again — no rescan, no cover
        // refill, no playback refresh, and a row that quietly decayed forever.
        // Discovery deciding what exists is not the same as it deciding what
        // is still worth scanning: the row itself is the memory.
        // Built from the stored row (asChatInfo), so this costs a query, not
        // one GetChat per candidate.
        val extras = chatDao.getTrackedOnce()
            .filter { it.isTracked && it.chatId !in known }
        if (extras.isEmpty()) return slice
        TrackLog.d(TAG, "candidates: +${extras.size} tracked chats outside the main list")
        return slice + extras.map { it.asChatInfo() }
    }

    /** The shelf's own row, shaped for a pass — the main list never has these. */
    /** A discovery answer as a row to be stamped, before Room decides its history. */
    private fun ChatInfo.asDiscovered(now: Long) = ChatSource(
        chatId = chatId,
        title = title,
        username = username,
        photoUrl = photoUrl,
        type = when {
            isChannel -> ChatType.CHANNEL
            isGroup -> ChatType.SUPERGROUP
            else -> ChatType.PRIVATE
        },
        discoveredAt = 0L,
        lastSeenAt = now,
    )

    private fun ChatSource.asChatInfo() = ChatInfo(
        chatId = chatId,
        title = title,
        username = username,
        isChannel = type == ChatType.CHANNEL,
        isGroup = type == ChatType.SUPERGROUP,
    )

    suspend fun indexAudio(audio: RemoteAudio) {
        val existing = trackDao.getById(audio.uniqueFileId)
        val track = MetadataMerger.merge(audio, timeSource(), existing = existing)
        if (existing == null) {
            val index = fingerprintIndex()
            val fp = fingerprintOf(track)
            val keeper = fingerprintLock.withLock {
                val held = index[fp]
                if (held == null || held == track.fileUniqueId) {
                    index[fp] = track.fileUniqueId
                    null
                } else {
                    held
                }
            }
            if (keeper != null) {
                // F7: the song already lives in the library under another file
                // id — a repost, not a new track. The index is authoritative:
                // keeper==self passes, so a batch whose insert failed retries
                // cleanly on the next pass.
                TrackLog.d(TAG, "F7: repost of ${track.title} skipped (keeper=$keeper)")
                return
            }
            trackDao.insert(track)
            _progress.update { it.copy(tracksIndexed = it.tracksIndexed + 1) }
        } else {
            fingerprintLock.withLock { fingerprintIndex()[fingerprintOf(track)] = track.fileUniqueId }
            trackDao.update(track)
        }
    }

    /**
     * Indexes one page with one lookup + one insert instead of the two
     * per-song queries [indexAudio] spends: a 1 800-track channel used to cost
     * ~3 600 queries, now ~2 per page. Existing rows are merged and updated so
     * favorites/play counts survive a rescan, exactly like [indexAudio].
     * [TrackDao.insertAll]'s IGNORE makes a file shared by two chats racing
     * each other harmless (first writer wins).
     */
    private suspend fun batchIndex(audios: List<RemoteAudio>) {
        if (audios.isEmpty()) return
        val now = timeSource()
        val existingById =
            trackDao.getByIds(audios.map { it.uniqueFileId }).associateBy { it.fileUniqueId }
        val toInsert = mutableListOf<Track>()
        val toUpdate = mutableListOf<Track>()
        val toSources = mutableListOf<TrackSource>()
        val index = fingerprintIndex()
        var reposts = 0

        // Every occurrence records where it was seen, win or lose. This used
        // to happen only for rows that became tracks, so the loser of a dedupe
        // carried its chat association to the grave with it — which is how a
        // song found in a bot chat disappeared from that chat's filter while
        // still listing in the main library. The chat now survives the merge.
        fun sourceFor(trackFileUniqueId: String, audio: RemoteAudio) = TrackSource(
            trackFileUniqueId = trackFileUniqueId,
            chatId = audio.chatId,
            messageId = audio.messageId,
            fileId = audio.remoteFileId,
            dateAdded = audio.dateSeconds * 1000L,
        )

        for (audio in audios) {
            val existing = existingById[audio.uniqueFileId]
            val merged = MetadataMerger.merge(audio, now, existing = existing)
            if (existing != null) {
                toUpdate += merged
                toSources += sourceFor(merged.fileUniqueId, audio)
                fingerprintLock.withLock { index[fingerprintOf(merged)] = merged.fileUniqueId }
                continue
            }
            val fp = fingerprintOf(merged)
            val keeper = fingerprintLock.withLock {
                val held = index[fp]
                if (held == null || held == merged.fileUniqueId) {
                    index[fp] = merged.fileUniqueId
                    null
                } else {
                    held
                }
            }
            if (keeper != null) {
                // F7: same song, different file id — a repost. No new track row,
                // but THIS chat's copy is still recorded, pointed at the keeper.
                // The locked reserve makes six parallel chats agree on one owner.
                toSources += sourceFor(keeper, audio)
                reposts++
                continue
            }
            toInsert += merged
            toSources += sourceFor(merged.fileUniqueId, audio)
        }
        var inserted = 0
        if (toInsert.isNotEmpty()) {
            // -1 rows are the IGNORE conflicts; only real inserts count, which
            // is what indexAudio counted one-by-one before.
            inserted = trackDao.insertAll(toInsert).count { it != -1L }
        }
        if (toUpdate.isNotEmpty()) trackDao.updateAll(toUpdate)
        // Written after the tracks so the foreign key has something to point
        // at, and filtered to ids that actually exist: the fingerprint index
        // can outlive a row deleted outside the scanner, and a source row for
        // a missing track would abort the whole batch.
        if (toSources.isNotEmpty()) {
            val known = trackDao
                .getByIds(toSources.map { it.trackFileUniqueId }.distinct())
                .map { it.fileUniqueId }
                .toHashSet()
            val writable = toSources.filter { it.trackFileUniqueId in known }
            if (writable.size != toSources.size) {
                TrackLog.w(
                    TAG,
                    "F7: ${toSources.size - writable.size} sources skipped (keeper no longer exists)",
                )
            }
            if (writable.isNotEmpty()) trackDao.insertSources(writable)
        }
        if (inserted > 0) {
            _progress.update { it.copy(tracksIndexed = it.tracksIndexed + inserted) }
        }
        if (reposts > 0) {
            TrackLog.d(
                TAG,
                "F7: folded $reposts reposts onto their keepers, " +
                    "keeping ${toSources.size - inserted} source rows",
            )
        }
    }

    /**
     * Pages one chat from the newest message backwards ([fromMessageId] 0 =
     * newest; TDLib answers newest-first and walks older via
     * `nextFromMessageId`) and indexes what it finds.
     *
     * [stopBefore] is the resume boundary: only messages above it are indexed,
     * and paging stops as soon as the next cursor reaches it. A rescan thus
     * walks the SAME newest-first path and halts at the boundary — it must not
     * START at the boundary, which would collect only older messages (the
     * wrong direction for "what has arrived since").
     */
    /**
     * What avatar this chat should keep.
     *
     * Anything already on the row wins — it may be a full-size HQ cover or a
     * local rescue file, and TDLib's inline minithumbnail is always the worse
     * one. The row's value is only replaced when it has none.
     */
    private fun preservedAvatar(existing: ChatSource?, info: ChatInfo): String? =
        existing?.photoUrl?.takeIf { it.isNotBlank() }
            ?: info.photoUrl?.takeIf { it.isNotBlank() }

    private suspend fun scanChat(
        chatId: Long,
        fromMessageId: Long,
        full: Boolean,
        stopBefore: Long = 0L,
        settleEmpty: Boolean = false,
    ) {

        val startedAt = timeSource()
        TrackLog.d(TAG, "chat $chatId START")
        var cursor = fromMessageId
        var emptyPages = 0
        // Largest (most recent) message ID seen in this scan.
        var maxMessageId = fromMessageId
        // Set only when the walk terminated at the end of history — the one
        // signal that separates "I got to the bottom and there is nothing"
        // from a page that merely came back empty.
        var reachedHistoryEnd = false
        // Tracks this chat has contributed so far, for its own fraction.
        var indexedInChat = 0
        // The last totalCount TDLib reported for this chat; used to settle the
        // unit without needing the page value after the loop.
        var lastTotalCount = 0
        var pagesFetched = 0
        while (emptyPages < 2) {
            pagesFetched++
            val pageAt = timeSource()
            _progress.update { it.copy(pageStartedAt = pageAt) }
            val page = client.getChatAudio(chatId, cursor, limit = TelegramConfig.DEFAULT_PAGE_LIMIT)
            _progress.update { it.copy(pageStartedAt = null) }
            TrackLog.d(
                TAG,
                "chat $chatId page: got=${page.audios.size} total=${page.totalCount} " +
                    "took=${timeSource() - pageAt}ms cursor=$cursor",
            )
            if (page.audios.isEmpty()) {
                emptyPages++
                if (page.nextFromMessageId == 0L || page.nextFromMessageId == cursor) {
                    // The walk ended on an empty page: this is the end of the
                    // chat's history as far as TDLib is willing to serve it.
                    reachedHistoryEnd = true
                    break
                }
                if (page.nextFromMessageId in 1..stopBefore) break
                // A settled empty chat only needs a single page to answer "anything new?".
                // If page 1 is empty, there is no new audio above its boundary: stop immediately.
                if (stopBefore == SCAN_COMPLETE_NO_TRACKS) {
                    reachedHistoryEnd = true
                    break
                }
                if (emptyPages >= 2) break
                cursor = page.nextFromMessageId
                continue
            }
            emptyPages = 0
            val fresh = page.audios.filter { it.messageId > stopBefore }
            for (audio in fresh) {
                if (audio.messageId > maxMessageId) maxMessageId = audio.messageId
            }
            batchIndex(fresh)
            // Charge this page's share of the chat as it lands. totalCount is
            // what TDLib already reported for the whole chat on page 1, so a
            // 2,000-track chat paging ten times moves the bar ten times.
            lastTotalCount = page.totalCount

            // If that page took this long, Telegram was not serving it — it was
            // making us wait. The healthy median on this account is 211ms; the
            // stalls land at 19-22 seconds, with nothing in between, and the
            // device log puts FLOOD_WAIT_19/20/21 at exactly those durations.
            // Telegram escalates when pushed: the same log carries penalties up
            // to 9901 seconds, which is two and three quarter hours on one
            // address. So the answer is to stop asking for a moment, not to ask
            // again from another worker.
            //
            // Read from the page's own duration rather than from TDLib because
            // TDLib's NetQueryDelayer swallows a flood internally and retries:
            // the slow call is the only evidence that reaches this side.
            //
            // Nothing fires under the scanner's tests: they drive a fake client
            // that answers in microseconds, so a page there is measured at ~0
            // and the branch is never taken. The pause is real only where a
            // page can really be slow.
            if (timeSource() - pageAt > FLOOD_SUSPECT_MS) {
                delay(FLOOD_BACKOFF_MS)
            }
            // If that page took a long time, we were almost certainly flooded
            // rather than served: the healthy median on this account is ~200ms
            // and the flooded ones land at 19-22 seconds, with nothing in
            // between. Telegram answers a flood by escalating — the log carries
            // FLOOD_WAIT values up to 9901 seconds — so the way out is to stop
            // pushing, not to push from another worker. Measured rather than
            // read from TDLib because TDLib's NetQueryDelayer swallows the
            // flood internally; the delay is the only evidence that reaches us.

            if (page.totalCount > 0) {
                val counted = indexedInChat.toLong() * 1000L / page.totalCount
                indexedInChat += fresh.size
                val now = indexedInChat.toLong() * 1000L / page.totalCount
                if (now > counted) {
                    workMilli.addAndGet(now - counted)
                    publishWork()
                }
            }
            if (!full) break
            val next = page.nextFromMessageId
            if (next == 0L || next == cursor) {
                reachedHistoryEnd = true
                break
            }
            if (next in 1..stopBefore) break
            cursor = next
        }
        // Persist the highest boundary ever scanned — never lower it, so a
        // boundary kept through the upsert survives even a chat that ends up
        // contributing nothing new (empty, all-below, failed mid-way).
        //
        // A chat that genuinely holds no audio used to store 0 here, and 0 is
        // also what an unscanned chat holds. The scanner could not tell "I
        // looked and there is nothing" from "I have never looked", so it
        // walked all 164 empty chats from message 0 on every single scan —
        // the constant re-work behind the scan never getting faster. The
        // sentinel separates the two states without inventing a column.
        val boundary = when {
            maxMessageId > 0L -> maxOf(stopBefore, maxMessageId)
            // != 0, not > 0: a settled empty chat already holds -1 and must
            // keep it. The old `> 0L` dropped it straight back to 0, so the
            // very next scan re-walked it from message 0 — which is why the
            // sentinel appeared to change nothing.
            stopBefore != 0L -> stopBefore
            // Only a walk that actually served a page and reached its end is
            // settled. An empty page from a launch-time sync race stays at 0
            // so the quiet re-pass can retry it — that retry is the fix for a
            // real bug and must not be turned off by this one.
            // Only the RE-PASS is allowed to settle. On the first pass an
            // empty answer is indistinguishable from a launch-time sync
            // race — identical bytes — so leaving it at 0 keeps the retry
            // window that fixes a real bug. After the sync window has passed
            // and it is STILL empty, the chat is genuinely empty.
            settleEmpty && reachedHistoryEnd -> SCAN_COMPLETE_NO_TRACKS
            // Not served: TDLib answered nothing for this chat, which on a
            // launching account means the dialog had not synced yet. Leave it
            // at 0 so the quiet re-pass finds it again.
            else -> 0L
        }
        val count = trackDao.countForChat(chatId)
        chatDao.updateScanProgress(
            chatId = chatId,
            messageId = boundary,
            trackCount = count,
        )
        TrackLog.d(
            TAG,
            "scanned chat $chatId: boundary=$boundary, tracks=$count " +
                "pages=${pagesFetched} in=${timeSource() - startedAt}ms",
        )
        // Settle this chat's unit exactly, once, whatever path it took. A chat
        // that produced no audio never entered the per-page loop, and one that
        // did can stop short of totalCount when its resume boundary already
        // claimed the rest — either way the unit is owed in full, or the sum
        // can never reach 1f and the bar stops short of 100%.
        val earned = if (lastTotalCount > 0) {
            (indexedInChat.toLong() * 1000L / lastTotalCount).coerceIn(0L, 1000L)
        } else {
            0L
        }
        workMilli.addAndGet(1000L - earned)
        publishWork()
    }

    companion object {
        private const val TAG = "TelegramScan"

        /**
         * A chat that was walked to its end and holds no audio.
         *
         * Telegram message ids start at 1, so a negative value is a free slot
         * for a second state. 0 keeps meaning "not scanned yet", which is what
         * the resume boundary, the quiet re-pass and the candidate filter all
         * read it as.
         */
        const val SCAN_COMPLETE_NO_TRACKS = -1L

        /** How long to wait for TDLib's dialogs to load before giving up. */
        private const val CHATS_LOAD_TIMEOUT_MS = 120_000L
    /** With rows already in Room: long enough for a fresh answer, not for a full sync. */
    private const val CHATS_SETTLED_TIMEOUT_MS = 8_000L
    /** Polls kept for the settle test: six samples, one a second. */
    private const val CHATS_SETTLE_WINDOW = 6
    /**
     * How much the candidate count may drift across [CHATS_SETTLE_WINDOW]
     * polls and still count as settled. One, because TDLib's drips are single
     * chats — a real wave arrives in tens.
     */
    private const val CHATS_SETTLE_SLACK = 1

    /**
     * A page slower than this was a flood, not a fetch. 211ms is a healthy
     * median on this account and the flooded ones are 19-22 seconds, so
     * anything in this gap is unambiguous — there is no middle ground to
     * misjudge.
     */
    private const val FLOOD_SUSPECT_MS = 3_000L

    /** How long to hold off before asking again, once a flood is suspected. */
    private const val FLOOD_BACKOFF_MS = 1_500L

        /** Pause between GetChats attempts while dialogs are still loading. */
        private const val CHATS_RETRY_DELAY_MS = 1_000L

        /**
     * Consecutive polls at the same candidate count that count as "TDLib has
     * finished". Three seconds of stillness: the dialog pull runs in bursts,
     * so a single unchanged poll could still be a gap between them.
     */
    /**
     * Matching polls required before discovery stops waiting.
     *
     * 2, not 3: the poll actively pumps `loadChatList()`, which answers 404
     * once every dialog TDLib holds has been surfaced — so a second identical
     * count means a full second of no growth under an active pump. The third
     * sample bought nothing but another `delay(CHATS_RETRY_DELAY_MS)` on every
     * launch, which is a full second of the dead air the user sees before a
     * scan begins. 1 is not enough: a bursty connection can pause between two
     * `messages.getDialogs` pages for over a second and fake stability while
     * half the account is still unsurfaced.
     */
    private const val CHATS_STABLE_POLLS = 2

        /** Chats scanned at once by [fullScan] (approved range: 4–8). */
        /**
         * Three, not six. Network was never the bottleneck — every worker
         * writes pages into one Room database, and each write invalidates the
         * tracks table, so six workers meant six times the wake-ups for the
         * list the user is looking at. Halving them halves the churn and costs
         * no throughput.
         */
        private const val SCAN_PARALLEL_CHATS = 3

        /**
         * Quiet re-pass schedule: one round a minute after the scan (the
         * observed dialog-settle window) and one at 4 min for slow proxies.
         */
        private val QUIET_REPASS_DELAYS_MS = longArrayOf(60_000L, 240_000L)

        /** App-wide scanner bound to the shared client and database. */
        fun create(): LibraryScanner =
            LibraryScanner(
                client = Telegram.client,
                trackDao = TelegramDb.trackDao,
                chatDao = TelegramDb.chatDao
            )
    }
}
