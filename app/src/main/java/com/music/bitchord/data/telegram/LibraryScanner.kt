package com.music.bitchord.data.telegram

import com.music.bitchord.data.db.TelegramDb
import com.music.bitchord.data.db.dao.ChatDao
import com.music.bitchord.data.db.dao.TrackDao
import com.music.bitchord.data.db.entity.ChatSource
import com.music.bitchord.data.db.entity.ChatType
import com.music.bitchord.data.db.entity.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScanProgress(
    val isRunning: Boolean = false,
    val chatsScanned: Int = 0,
    val totalChats: Int = 0,
    val tracksIndexed: Int = 0,
    val lastError: String? = null
)

/**
 * Scans Telegram chats for audio messages and indexes them as [Track]s.
 * Supports full scans (all tracked chats) and incremental scans from the stored cursor.
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
            val chats = client.listTrackedCandidateChats()
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
            _progress.value = _progress.value.copy(
                isRunning = false,
                lastError = e.message ?: e::class.simpleName
            )
        }
    }

    suspend fun incrementalScan(chatId: Long) {
        val chat = chatDao.getById(chatId) ?: return
        scanChat(chatId, fromMessageId = chat.lastScannedMessageId, full = false)
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
        var indexed = 0
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
                indexed++
                if (audio.messageId > maxMessageId) maxMessageId = audio.messageId
            }
            if (!full) break
            val next = page.nextFromMessageId
            if (next == 0L || next == cursor) break
            cursor = next
        }
        // Store the NEWEST scanned message ID so incremental scan starts from there.
        chatDao.updateScanProgress(
            chatId = chatId,
            messageId = maxOf(fromMessageId, maxMessageId),
            trackCount = indexed
        )
    }

    companion object {
        /** App-wide scanner bound to the shared client and database. */
        fun create(): LibraryScanner =
            LibraryScanner(
                client = Telegram.client,
                trackDao = TelegramDb.trackDao,
                chatDao = TelegramDb.chatDao
            )
    }
}
