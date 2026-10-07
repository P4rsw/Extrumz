package com.music.bitchord.data.telegram

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.db.dao.ChatDao
import com.music.bitchord.data.db.dao.TrackDao
import com.music.bitchord.data.db.entity.ChatSource
import com.music.bitchord.data.db.entity.ChatType
import com.music.bitchord.data.db.entity.Track
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

data class ScanProgress(
    val isRunning: Boolean = false,
    val chatsScanned: Int = 0,
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

    /** Post-merge fingerprint: exactly what lands in the tracks row. */
    private data class Fingerprint(val performer: String, val title: String, val duration: Int)

    @Volatile
    private var fingerprintIndex: MutableMap<Fingerprint, String>? = null

    /** Serializes fp → keeper decisions: six chats scan in parallel. */
    private val fingerprintLock = Mutex()

    private fun fingerprintOf(track: Track) =
        Fingerprint(track.performer.lowercase(), track.title.lowercase(), track.duration)

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
        val rows = trackDao.loadAllTracks()
        val index = HashMap<Fingerprint, String>(rows.size)
        val losers = mutableListOf<String>()
        for ((fp, group) in rows.groupBy(::fingerprintOf)) {
            val ordered = group.sortedBy { it.fileUniqueId }
            val winner = ordered.first()
            index[fp] = winner.fileUniqueId
            if (ordered.size == 1) continue
            val collapsed = ordered.drop(1)
            losers += collapsed.map { it.fileUniqueId }
            collapsed.forEach { trackDao.repointToKeeper(it.fileUniqueId, winner.fileUniqueId) }
            trackDao.update(
                winner.copy(
                    isFavorite = ordered.any { it.isFavorite },
                    playCount = ordered.sumOf { it.playCount },
                    lastPlayed = ordered.mapNotNull { it.lastPlayed }.maxOrNull(),
                ),
            )
        }
        if (losers.isNotEmpty()) {
            trackDao.deleteByFileUniqueIds(losers)
            TrackLog.w(TAG, "F7: merged ${losers.size} duplicate rows away (reposts of one song)")
        }
        return index.also { fingerprintIndex = it }
    }

    suspend fun fullScan() {
        // F7: collapse pre-existing duplicates even when every chat is already
        // scanned to the latest message (batchIndex never runs then).
        fingerprintIndex()
        if (!scanGate.compareAndSet(false, true)) return
        _progress.value = ScanProgress(isRunning = true)
        try {
            // Right after Ready, TDLib is still pulling dialogs through the
            // proxy ("Save chat to database…" for minutes on a big account):
            // GetChats answers 0 there, and without the wait this scan would
            // end in silence with an empty list and no banner.
            val chats = awaitCandidateChats()
            if (chats.isEmpty()) {
                // lastError is what the tabs surface as the failure line — a
                // silent zero here is what made the first-run auto-scan look
                // like it had never run.
                val reason = "no candidate chats after ${CHATS_LOAD_TIMEOUT_MS}ms"
                _progress.value = ScanProgress(
                    lastError = reason,
                    lastErrorDetail = reason,
                )
                return
            }
            _progress.update { it.copy(totalChats = chats.size) }

            // Chats scan concurrently (bounded by the semaphore) but are
            // LAUNCHED in list order: GetChats answers newest-dialogs-first, so
            // the chats the user is most likely to open claim the first
            // permits. A failure stays inside its own chat (scanOneChat).
            val semaphore = Semaphore(SCAN_PARALLEL_CHATS)
            coroutineScope {
                chats.forEach { info ->
                    launch {
                        semaphore.withPermit { scanOneChat(info) }
                    }
                }
            }
            _progress.update { it.copy(isRunning = false) }
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
    suspend fun quietRepass() {
        for (delayMs in QUIET_REPASS_DELAYS_MS) {
            delay(delayMs)
            // A scan started meanwhile owns the candidates; retrying now would race it.
            if (!scanGate.compareAndSet(false, true)) return
            try {
                val candidates = withShelfExtras(client.listTrackedCandidateChats())
                if (candidates.isEmpty()) continue
                val unscanned = chatDao.getTrackedOnce()
                    .filter { it.lastScannedMessageId == 0L }
                    .map { it.chatId }
                    .toHashSet()
                val targets = candidates.filter { it.chatId in unscanned }
                if (targets.isEmpty()) return
                val semaphore = Semaphore(SCAN_PARALLEL_CHATS)
                coroutineScope {
                    targets.forEach { info ->
                        launch { semaphore.withPermit { scanOneChat(info) } }
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
    private suspend fun scanOneChat(info: ChatInfo) {
        try {
            val type = when {
                info.isChannel -> ChatType.CHANNEL
                info.isGroup -> ChatType.SUPERGROUP
                else -> ChatType.PRIVATE
            }
            val existing = chatDao.getById(info.chatId)
            // F8: preserve any HQ photoUrl the row already carries (covers/hq_*).
            // GetChats only returns inline minithumbnail; upsert(REPLACE) would
            // clobber the full-size avatar we downloaded earlier.
            val preservedPhotoUrl = existing?.photoUrl?.takeIf {
                it.isNotBlank() && it.contains(Covers.HQ_PREFIX)
            } ?: info.photoUrl?.takeIf { it.isNotBlank() }
                ?: existing?.photoUrl
            chatDao.upsert(
                ChatSource(
                    chatId = info.chatId,
                    title = info.title,
                    username = info.username,
                    photoUrl = preservedPhotoUrl,
                    type = type,
                    isTracked = true,
                    // REPLACE must not wipe the resume boundary or the shelf's
                    // track count — a zeroed count hides the channel from
                    // observeAllByTrackCount until this scan finishes.
                    lastScannedMessageId = existing?.lastScannedMessageId ?: 0L,
                    trackCount = existing?.trackCount ?: 0
                )
            )
            scanChat(
                chatId = info.chatId,
                fromMessageId = 0L,
                full = true,
                stopBefore = existing?.lastScannedMessageId ?: 0L,
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
                    // Gone from Telegram entirely: absent from every chat list and
                    // from TDLib's own database ("Failed to load chat … from
                    // database: Not found") across passes and relaunches — 30 shelf
                    // rows on 2026-10-06, while the one archived-but-intact chat
                    // beside them scanned fine. Retrying forever would put the same
                    // error on the banner every launch, so the row is untracked
                    // here; re-adding the chat through the app re-tracks it.
                    chatDao.getById(info.chatId)?.let { row ->
                        chatDao.upsert(row.copy(isTracked = false))
                    }
                    TrackLog.w(TAG, "chat ${info.chatId} is gone from Telegram — untracked")
                    return
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
     * with nothing. Retries every [CHATS_RETRY_DELAY_MS] until a dialog shows
     * up or [CHATS_LOAD_TIMEOUT_MS] runs out, then returns the (possibly
     * empty) list for the caller to surface.
     */
    private suspend fun awaitCandidateChats(): List<ChatInfo> {
        val deadline = timeSource() + CHATS_LOAD_TIMEOUT_MS
        var attempt = 0
        while (true) {
            attempt++
            // Published so the banners can show "attempt N" during the wait —
            // two minutes with no visible change reads as a hang.
            _progress.update { it.copy(attempts = attempt) }
            val chats = client.listTrackedCandidateChats()
            if (chats.isNotEmpty()) {
                if (attempt > 1) {
                    TrackLog.d(TAG, "candidate chats loaded after $attempt attempts")
                }
                return withShelfExtras(chats)
            }
            if (timeSource() >= deadline) {
                TrackLog.w(TAG, "candidate chats still empty after $attempt attempts")
                return withShelfExtras(emptyList())
            }
            TrackLog.d(
                TAG,
                "candidate chats not loaded yet (attempt $attempt); " +
                    "retrying in ${CHATS_RETRY_DELAY_MS}ms",
            )
            delay(CHATS_RETRY_DELAY_MS)
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
        val extras = chatDao.getTrackedOnce()
            .filter { it.lastScannedMessageId == 0L && it.chatId !in known }
        if (extras.isEmpty()) return slice
        TrackLog.d(TAG, "candidates: +${extras.size} tracked chats outside the main list")
        return slice + extras.map { it.asChatInfo() }
    }

    /** The shelf's own row, shaped for a pass — the main list never has these. */
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
        val index = fingerprintIndex()
        var reposts = 0
        for (audio in audios) {
            val existing = existingById[audio.uniqueFileId]
            val merged = MetadataMerger.merge(audio, now, existing = existing)
            if (existing != null) {
                toUpdate += merged
                fingerprintLock.withLock { index[fingerprintOf(merged)] = merged.fileUniqueId }
                continue
            }
            val fp = fingerprintOf(merged)
            val held = fingerprintLock.withLock {
                val keeper = index[fp]
                if (keeper == null || keeper == merged.fileUniqueId) {
                    index[fp] = merged.fileUniqueId
                    true
                } else {
                    false
                }
            }
            if (!held) {
                // F7: same song, different file id — a repost. Dropped at the
                // door so a rescan can never grow the duplicate set again. The
                // locked reserve makes six parallel chats agree on one owner.
                reposts++
                continue
            }
            toInsert += merged
        }
        var inserted = 0
        if (toInsert.isNotEmpty()) {
            // -1 rows are the IGNORE conflicts; only real inserts count, which
            // is what indexAudio counted one-by-one before.
            inserted = trackDao.insertAll(toInsert).count { it != -1L }
        }
        if (toUpdate.isNotEmpty()) trackDao.updateAll(toUpdate)
        if (inserted > 0) {
            _progress.update { it.copy(tracksIndexed = it.tracksIndexed + inserted) }
        }
        if (reposts > 0) {
            TrackLog.d(TAG, "F7: dropped $reposts reposts in this batch")
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
    private suspend fun scanChat(
        chatId: Long,
        fromMessageId: Long,
        full: Boolean,
        stopBefore: Long = 0L,
    ) {
        var cursor = fromMessageId
        var emptyPages = 0
        // Largest (most recent) message ID seen in this scan.
        var maxMessageId = fromMessageId
        while (emptyPages < 2) {
            val page = client.getChatAudio(chatId, cursor, limit = TelegramConfig.DEFAULT_PAGE_LIMIT)
            if (page.audios.isEmpty()) {
                emptyPages++
                if (page.nextFromMessageId == 0L || page.nextFromMessageId == cursor) break
                if (page.nextFromMessageId in 1..stopBefore) break
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
            if (!full) break
            val next = page.nextFromMessageId
            if (next == 0L || next == cursor) break
            if (next in 1..stopBefore) break
            cursor = next
        }
        // Persist the highest boundary ever scanned — never lower it, so a
        // boundary kept through the upsert survives even a chat that ends up
        // contributing nothing new (empty, all-below, failed mid-way).
        chatDao.updateScanProgress(
            chatId = chatId,
            messageId = maxOf(stopBefore, maxMessageId),
            trackCount = trackDao.countForChat(chatId)
        )
    }

    companion object {
        private const val TAG = "TelegramScan"

        /** How long to wait for TDLib's dialogs to load before giving up. */
        private const val CHATS_LOAD_TIMEOUT_MS = 120_000L

        /** Pause between GetChats attempts while dialogs are still loading. */
        private const val CHATS_RETRY_DELAY_MS = 1_000L

        /** Chats scanned at once by [fullScan] (approved range: 4–8). */
        private const val SCAN_PARALLEL_CHATS = 6

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
