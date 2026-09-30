package com.music.bitchord.data.telegram

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.db.dao.ChatDao
import com.music.bitchord.data.db.dao.TrackDao
import com.music.bitchord.data.db.entity.ChatSource
import com.music.bitchord.data.db.entity.ChatType
import com.music.bitchord.data.db.entity.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScanProgress(
    val isRunning: Boolean = false,
    val chatsScanned: Int = 0,
    val totalChats: Int = 0,
    val tracksIndexed: Int = 0,
    /** GetChats attempts spent waiting for dialogs to load (0 = not waiting). */
    val attempts: Int = 0,
    val lastError: String? = null
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

    suspend fun fullScan() {
        if (_progress.value.isRunning) return
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
                _progress.value = ScanProgress(
                    lastError = "no candidate chats after ${CHATS_LOAD_TIMEOUT_MS}ms",
                )
                return
            }
            _progress.value = _progress.value.copy(totalChats = chats.size)

            chats.forEach { info ->
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
                        photoUrl = null,
                        type = type,
                        isTracked = true,
                        lastScannedMessageId = 0L,
                        trackCount = 0
                    )
                )
                scanChat(info.chatId, fromMessageId = 0L, full = true)
                _progress.value =
                    _progress.value.copy(chatsScanned = _progress.value.chatsScanned + 1)
            }
            _progress.value = _progress.value.copy(isRunning = false)
        } catch (e: Exception) {
            // Cancelling the scan (scope shutdown) is not a scan failure and
            // must not be swallowed into lastError.
            if (e is CancellationException) throw e
            TrackLog.w(TAG, "fullScan failed: ${e::class.simpleName}: ${e.message}")
            _progress.value = _progress.value.copy(
                isRunning = false,
                lastError = e.message ?: e::class.simpleName
            )
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
            _progress.value = _progress.value.copy(attempts = attempt)
            val chats = client.listTrackedCandidateChats()
            if (chats.isNotEmpty()) {
                if (attempt > 1) {
                    TrackLog.d(TAG, "candidate chats loaded after $attempt attempts")
                }
                return chats
            }
            if (timeSource() >= deadline) {
                TrackLog.w(TAG, "candidate chats still empty after $attempt attempts")
                return emptyList()
            }
            TrackLog.d(
                TAG,
                "candidate chats not loaded yet (attempt $attempt); " +
                    "retrying in ${CHATS_RETRY_DELAY_MS}ms",
            )
            delay(CHATS_RETRY_DELAY_MS)
        }
    }

    suspend fun indexAudio(audio: RemoteAudio) {
        val existing = trackDao.getById(audio.uniqueFileId)
        val track = MetadataMerger.merge(audio, timeSource(), existing = existing)
        if (existing == null) {
            trackDao.insert(track)
            _progress.value =
                _progress.value.copy(tracksIndexed = _progress.value.tracksIndexed + 1)
        } else {
            trackDao.update(track)
        }
    }

    private suspend fun scanChat(chatId: Long, fromMessageId: Long, full: Boolean) {
        var cursor = fromMessageId
        var emptyPages = 0
        // Largest (most recent) message ID seen in this scan.
        var maxMessageId = fromMessageId
        while (emptyPages < 2) {
            val page = client.getChatAudio(chatId, cursor, limit = TelegramConfig.DEFAULT_PAGE_LIMIT)
            if (page.audios.isEmpty()) {
                emptyPages++
                if (page.nextFromMessageId == 0L || page.nextFromMessageId == cursor) break
                if (emptyPages >= 2) break
                cursor = page.nextFromMessageId
                continue
            }
            emptyPages = 0
            for (audio in page.audios) {
                indexAudio(audio)
                if (audio.messageId > maxMessageId) maxMessageId = audio.messageId
            }
            if (!full) break
            val next = page.nextFromMessageId
            if (next == 0L || next == cursor) break
            cursor = next
        }
        // Store the NEWEST scanned message ID so an incremental pass (a
        // future watcher) starts from there, and a *cumulative* track count —
        // rows this chat has in the database — not what this one pass saw,
        // which would reset the number on every rescan.
        chatDao.updateScanProgress(
            chatId = chatId,
            messageId = maxOf(fromMessageId, maxMessageId),
            trackCount = trackDao.countForChat(chatId)
        )
    }

    companion object {
        private const val TAG = "TelegramScan"

        /** How long to wait for TDLib's dialogs to load before giving up. */
        private const val CHATS_LOAD_TIMEOUT_MS = 120_000L

        /** Pause between GetChats attempts while dialogs are still loading. */
        private const val CHATS_RETRY_DELAY_MS = 8_000L

        /** App-wide scanner bound to the shared client and database. */
        fun create(): LibraryScanner =
            LibraryScanner(
                client = Telegram.client,
                trackDao = TelegramDb.trackDao,
                chatDao = TelegramDb.chatDao
            )
    }
}
