package com.music.bitchord

import androidx.room.RoomRawQuery
import com.music.bitchord.data.db.dao.AlbumCount
import com.music.bitchord.data.db.dao.ArtistCount
import com.music.bitchord.data.db.dao.ArtistPlayCount
import com.music.bitchord.data.db.dao.ChatDao
import com.music.bitchord.data.db.dao.ChatTrackCount
import com.music.bitchord.data.db.dao.MediaAlbumCount
import com.music.bitchord.data.db.dao.TrackDao
import com.music.bitchord.data.db.entity.ChatSource
import com.music.bitchord.data.db.entity.Track
import com.music.bitchord.data.telegram.ChatInfo
import com.music.bitchord.data.telegram.LibraryScanner
import com.music.bitchord.data.telegram.MusicTelegramClient
import com.music.bitchord.data.telegram.RemoteAudio
import com.music.bitchord.data.telegram.RemoteAudioPage
import com.music.bitchord.data.telegram.TelegramAuthState
import com.music.bitchord.data.telegram.TelegramConnectionState
import com.music.bitchord.data.telegram.TelegramCredentials
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan-speed contract of [LibraryScanner] (stage 2): bounded chat-level
 * parallelism, one batched DB round-trip per page instead of two queries per
 * song, and a failure in one chat that stays in that chat.
 *
 * Faked by hand — the project has no mocking library — so the track DAO models
 * the one semantic production leans on (Room's INSERT IGNORE) and the client
 * models TDLib's newest-first paging ([LibraryScanner]'s boundary note).
 */
class LibraryScannerSpeedTest {

    // --- fakes ---------------------------------------------------------------

    private class FakeChatDao : ChatDao {
        val rows = linkedMapOf<Long, ChatSource>()
        var upsertCount = 0

        /** The ChatSource handed to the most recent upsert — the boundary/trackCount claims. */
        var lastUpserted: ChatSource? = null

        override fun observeTracked(): Flow<List<ChatSource>> = flowOf(rows.values.toList())
        override suspend fun getById(chatId: Long): ChatSource? = rows[chatId]
        override suspend fun upsert(chat: ChatSource) {
            upsertCount++
            lastUpserted = chat
            rows[chat.chatId] = chat
        }

        override suspend fun upsertAll(chats: List<ChatSource>) = chats.forEach { upsert(it) }
        override suspend fun updateScanProgress(chatId: Long, messageId: Long, trackCount: Int) {
            // Mirrors UPDATE ... WHERE chatId = :chatId — no row, no effect.
            rows[chatId]?.let {
                rows[chatId] = it.copy(lastScannedMessageId = messageId, trackCount = trackCount)
            }
        }

        override suspend fun getTrackedOnce(): List<ChatSource> = rows.values.filter { it.isTracked }
        override suspend fun refreshTrackCount(chatId: Long) {}
        override suspend fun setTracked(chatId: Long, tracked: Boolean) {}
        override fun observeAllByTrackCount(): Flow<List<ChatSource>> =
            flowOf(rows.values.filter { it.trackCount > 0 }.sortedByDescending { it.trackCount })
    }

    private class FakeTrackDao : TrackDao {
        val rows = linkedMapOf<String, Track>()
        var getByIdsCount = 0
        var getByIdCount = 0
        var insertAllCount = 0
        var insertCount = 0
        var updateCount = 0
        var ignoredCount = 0

        override suspend fun getById(id: String): Track? {
            getByIdCount++
            return rows[id]
        }

        override suspend fun getByIds(ids: List<String>): List<Track> {
            getByIdsCount++
            val found = ids.mapNotNull { rows[it] }
            // Yield AFTER the read: two parallel chats can both observe
            // "not there yet" before either writes — the window where
            // INSERT IGNORE has to absorb the duplicate.
            delay(1)
            return found
        }

        override suspend fun insertAll(tracks: List<Track>): List<Long> {
            insertAllCount++
            return tracks.map { track ->
                if (rows.containsKey(track.fileUniqueId)) {
                    ignoredCount++
                    -1L
                } else {
                    rows[track.fileUniqueId] = track.copy(id = rows.size + 1L)
                    rows.size.toLong()
                }
            }
        }

        override suspend fun insert(track: Track) {
            insertCount++
            rows.putIfAbsent(track.fileUniqueId, track)
        }

        override suspend fun update(track: Track) {
            updateCount++
            rows[track.fileUniqueId] = track
        }

        override suspend fun count(): Int = rows.size
        override suspend fun countForChat(chatId: Long): Int =
            rows.values.count { it.sourceChatId == chatId }

        override suspend fun getByChatAndMessage(chatId: Long, messageId: Long): Track? =
            rows.values.firstOrNull {
                it.sourceChatId == chatId && it.sourceMessageId == messageId
            }

        override suspend fun setFavorite(id: String, favorite: Boolean) = TODO()
        override suspend fun recordPlay(id: String, playedAt: Long) = TODO()
        override fun observeAll(): Flow<List<Track>> = TODO()
        override suspend fun observeAllOnce(): List<Track> = TODO()
        override fun observeRecent(limit: Int): Flow<List<Track>> = TODO()
        override fun observeFavorites(): Flow<List<Track>> = TODO()
        override fun search(query: String): Flow<List<Track>> = TODO()
        override fun searchFtsRaw(query: RoomRawQuery): Flow<List<Track>> = TODO()
        override fun observeByArtist(performer: String): Flow<List<Track>> = TODO()
        override fun observeByAlbum(album: String): Flow<List<Track>> = TODO()
        override fun observeArtists(): Flow<List<ArtistCount>> = TODO()
        override fun observeAlbums(): Flow<List<AlbumCount>> = TODO()
        override fun observeMediaAlbums(): Flow<List<MediaAlbumCount>> = TODO()
        override fun observeByMediaAlbum(albumId: Long): Flow<List<Track>> = TODO()
        override fun observeTopArtists(limit: Int): Flow<List<ArtistPlayCount>> = TODO()
        override fun observeMostPlayed(limit: Int): Flow<List<Track>> = TODO()
        override fun observeTopSources(limit: Int): Flow<List<ChatTrackCount>> = TODO()
        override fun observeTopFromChat(chatId: Long, limit: Int): Flow<List<Track>> = TODO()
        override fun observeByChat(chatId: Long): Flow<List<Track>> = TODO()
        override fun observeHistory(limit: Int): Flow<List<Track>> = TODO()
    }

    private class FakeTelegramClient(
        chats: List<ChatInfo>,
        private val messagesByChat: MutableMap<Long, List<RemoteAudio>> = mutableMapOf(),
        private val failingChats: Set<Long> = emptySet(),
        private val hangingChats: Set<Long> = emptySet(),
        private val listFailsWith: String? = null,
    ) : MusicTelegramClient {
        private val chats = chats
        override val authState: StateFlow<TelegramAuthState> =
            MutableStateFlow(TelegramAuthState.Ready)
        override val connectionState: StateFlow<TelegramConnectionState> =
            MutableStateFlow(TelegramConnectionState.READY)
        override val newAudioMessages: Flow<RemoteAudio> = emptyFlow()

        /** Every (chatId, cursor) page request, in order. */
        val pageRequests = mutableListOf<Pair<Long, Long>>()

        /** Chats in the order they issued their FIRST page request. */
        val firstRequestOrder = mutableListOf<Long>()
        private val seen = mutableSetOf<Long>()
        private var inFlight = 0
        var maxInFlight = 0
            private set

        fun setMessages(chatId: Long, messages: List<RemoteAudio>) {
            messagesByChat[chatId] = messages
        }

        override suspend fun getChatAudio(
            chatId: Long,
            fromMessageId: Long,
            limit: Int,
        ): RemoteAudioPage {
            if (chatId in failingChats) error("boom $chatId")
            if (chatId in hangingChats) awaitCancellation()
            pageRequests += chatId to fromMessageId
            if (seen.add(chatId)) firstRequestOrder += chatId
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                delay(RPC_DELAY_MS)
            } finally {
                inFlight--
            }
            val list = messagesByChat[chatId].orEmpty()
            if (list.isEmpty()) return RemoteAudioPage(emptyList(), 0L)
            val start =
                if (fromMessageId == 0L) 0 else list.indexOfFirst { it.messageId < fromMessageId }
            if (start < 0 || start >= list.size) return RemoteAudioPage(emptyList(), 0L)
            val end = minOf(start + limit, list.size)
            // TDLib's offsetId is "the message before which" results come back,
            // so the cursor is the last id of THIS page — using the first id of
            // the next page under strict-less-than would skip exactly that one.
            val next = if (end < list.size) list[end - 1].messageId else 0L
            return RemoteAudioPage(list.subList(start, end), next)
        }

        override suspend fun listTrackedCandidateChats(): List<ChatInfo> {
            listFailsWith?.let { error(it) }
            return chats
        }

        override suspend fun getChatInfo(chatId: Long): ChatInfo? =
            chats.firstOrNull { it.chatId == chatId }

        override suspend fun searchAllAudio(query: String, limit: Int): List<RemoteAudio> =
            emptyList()

        override suspend fun start(credentials: TelegramCredentials) = TODO()
        override suspend fun submitPhoneNumber(phoneNumber: String) = TODO()
        override suspend fun submitCode(code: String) = TODO()
        override suspend fun submitPassword(password: String) = TODO()
        override suspend fun requestQrCodeAuthentication() = TODO()
        override suspend fun confirmLoginLink(link: String) = TODO()
        override suspend fun logout() = TODO()
        override suspend fun getAudioByMessage(chatId: Long, messageId: Long): RemoteAudio? = TODO()
        override suspend fun getStreamPath(fileId: String, offset: Long): String = TODO()
        override suspend fun downloadFile(fileId: String, synchronous: Boolean): String = TODO()
        override suspend fun createPlaylistChannel(title: String, description: String): Long = TODO()

        override suspend fun addToPlaylistChannel(
            playlistChatId: Long,
            chatId: Long,
            messageId: Long,
        ): Long = TODO()

        override suspend fun createInviteLink(chatId: Long): String = TODO()
        override suspend fun addChatAdmin(chatId: Long, userChatId: Long): Boolean = TODO()
        override suspend fun searchPublicChannels(query: String, limit: Int): List<ChatInfo> = TODO()
    }

    // --- helpers -------------------------------------------------------------

    private fun chatInfo(id: Long) = ChatInfo(
        chatId = id,
        title = "chat $id",
        username = null,
        isChannel = true,
        isGroup = false,
    )

    private fun audio(chatId: Long, messageId: Long, uniqueId: String = "u-$chatId-$messageId") =
        RemoteAudio(
            chatId = chatId,
            messageId = messageId,
            remoteFileId = "f-$messageId",
            uniqueFileId = uniqueId,
            title = "title $messageId",
            performer = "performer",
            fileName = null,
            mimeType = "audio/ogg",
            durationSeconds = 1,
            sizeBytes = 1L,
            caption = "",
            dateSeconds = 1_700_000_000L,
            mediaAlbumId = null,
        )

    /** Newest-first message list for one chat: [count] messages, ids count..1. */
    private fun descendingMessages(chatId: Long, count: Int): List<RemoteAudio> =
        (count downTo 1).map { audio(chatId, it.toLong()) }

    // --- س۱: chat-level parallelism -----------------------------------------

    @Test
    fun `chats scan in parallel bounded by six and start in list order`() = runTest {
        val ids = (1L..12L).toList()
        val client = FakeTelegramClient(
            chats = ids.map { chatInfo(it) },
            messagesByChat = ids.associateWith { listOf(audio(it, 100L)) }.toMutableMap(),
        )
        val tracks = FakeTrackDao()
        val chats = FakeChatDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()

        val progress = scanner.progress.value
        assertFalse(progress.isRunning)
        assertEquals(12, progress.totalChats)
        assertEquals(12, progress.chatsScanned)
        assertNull(progress.lastError)
        // The semaphore bound holds and the chats really did overlap.
        assertEquals(6, client.maxInFlight)
        assertEquals(ids, client.firstRequestOrder)
        assertEquals(12, chats.upsertCount)
        assertEquals(12, tracks.rows.size)
        assertEquals(12, progress.tracksIndexed)
    }

    // --- س۱: error policy ----------------------------------------------------

    @Test
    fun `one failing chat is contained and the rest still scan`() = runTest {
        val ids = listOf(1L, 2L, 3L)
        val client = FakeTelegramClient(
            chats = ids.map { chatInfo(it) },
            messagesByChat = ids.associateWith { listOf(audio(it, 10L)) }.toMutableMap(),
            failingChats = setOf(2L),
        )
        val tracks = FakeTrackDao()
        val chats = FakeChatDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()

        val progress = scanner.progress.value
        assertFalse(progress.isRunning)
        assertEquals(3, progress.totalChats)
        assertEquals(3, progress.chatsScanned)
        assertNotNull(progress.lastError)
        assertTrue(progress.lastError!!.contains("boom"))
        // The raw exception survives for the detail line under the banner title.
        assertEquals("boom 2", progress.lastErrorDetail)
        // The healthy chats made it in; the failing chat still upserted + counted.
        assertEquals(setOf(1L, 3L), tracks.rows.values.map { it.sourceChatId }.toSet())
        assertEquals(3, chats.upsertCount)
    }

    @Test
    fun `a candidate-list failure keeps the raw exception text for the banner`() = runTest {
        val client = FakeTelegramClient(chats = emptyList(), listFailsWith = "list broken")
        val scanner = LibraryScanner(client, FakeTrackDao(), FakeChatDao())

        scanner.fullScan()

        val progress = scanner.progress.value
        assertFalse(progress.isRunning)
        assertEquals("list broken", progress.lastError)
        assertEquals("list broken", progress.lastErrorDetail)
    }

    @Test
    fun `cancelling the scan propagates instead of becoming lastError`() = runTest {
        val ids = listOf(1L, 2L, 3L)
        val client = FakeTelegramClient(
            chats = ids.map { chatInfo(it) },
            messagesByChat = mutableMapOf(
                1L to listOf(audio(1L, 10L)),
                3L to listOf(audio(3L, 10L)),
            ),
            hangingChats = setOf(2L),
        )
        val scanner = LibraryScanner(client, FakeTrackDao(), FakeChatDao())
        val job = launch { scanner.fullScan() }
        // Chat 2 hangs forever; 1 and 3 finish around it.
        scanner.progress.first { it.chatsScanned == 2 }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        // A swallowed CancellationException would have painted lastError here.
        assertNull(scanner.progress.value.lastError)
    }

    // --- س۲: batched DB writes ----------------------------------------------

    @Test
    fun `each page costs one batch lookup and one insert instead of per-song queries`() =
        runTest {
            // 150 messages → pages of 100 + 50.
            val client = FakeTelegramClient(
                chats = listOf(chatInfo(1L)),
                messagesByChat = mutableMapOf(1L to descendingMessages(1L, 150)),
            )
            val tracks = FakeTrackDao()
            val chats = FakeChatDao()
            val scanner = LibraryScanner(client, tracks, chats)

            scanner.fullScan()

            assertEquals(2, client.pageRequests.size)
            assertEquals(2, tracks.getByIdsCount)
            assertEquals(2, tracks.insertAllCount)
            // The scanner itself never drops to the per-song path.
            assertEquals(0, tracks.getByIdCount)
            assertEquals(0, tracks.insertCount)
            assertEquals(150, tracks.rows.size)
            assertEquals(150, scanner.progress.value.tracksIndexed)
        }

    @Test
    fun `the same file in two parallel chats lands as a single row`() = runTest {
        val shared = audio(1L, 10L, uniqueId = "shared-file")
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L), chatInfo(2L)),
            messagesByChat = mutableMapOf(
                1L to listOf(shared),
                2L to listOf(shared.copy(chatId = 2L)),
            ),
        )
        val tracks = FakeTrackDao()
        val scanner = LibraryScanner(client, tracks, FakeChatDao())

        scanner.fullScan()

        assertEquals(1, tracks.rows.size)
        // Both chats really did try to write it; the loser was IGNOREd.
        assertEquals(2, tracks.insertAllCount)
        assertEquals(1, tracks.ignoredCount)
        assertEquals(2, scanner.progress.value.chatsScanned)
    }

    // --- candidate-list wait + empty chat path -------------------------------

    @Test
    fun `an empty candidate list still surfaces the timeout line`() = runTest {
        val client = FakeTelegramClient(chats = emptyList())
        // currentTime is the virtual clock, so the 2-minute wait costs no real time.
        val scanner = LibraryScanner(client, FakeTrackDao(), FakeChatDao()) {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            testScheduler.currentTime
        }

        scanner.fullScan()

        val progress = scanner.progress.value
        assertEquals("no candidate chats after 120000ms", progress.lastError)
        assertFalse(progress.isRunning)
        assertEquals(0, progress.totalChats)
    }

    @Test
    fun `a chat with no audio pages completes without an error`() = runTest {
        val client = FakeTelegramClient(chats = listOf(chatInfo(1L)))
        val tracks = FakeTrackDao()
        val scanner = LibraryScanner(client, tracks, FakeChatDao())

        scanner.fullScan()

        val progress = scanner.progress.value
        assertFalse(progress.isRunning)
        assertEquals(1, progress.totalChats)
        assertEquals(1, progress.chatsScanned)
        assertNull(progress.lastError)
        assertEquals(0, tracks.rows.size)
    }

    // --- س۳: resume boundary -------------------------------------------------

    @Test
    fun `the first scan walks the whole chat like before`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to descendingMessages(1L, 250)),
        )
        val tracks = FakeTrackDao()
        val chats = FakeChatDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()

        // stopBefore = 0: full traversal, pages of 100 + 100 + 50.
        assertEquals(3, client.pageRequests.size)
        assertEquals(250, tracks.rows.size)
        assertEquals(250, scanner.progress.value.tracksIndexed)
        assertEquals(250L, chats.rows[1L]!!.lastScannedMessageId)
        assertEquals(250, chats.rows[1L]!!.trackCount)
    }

    @Test
    fun `the second scan stops at the boundary and never re-indexes below it`() = runTest {
        val original = descendingMessages(1L, 250) // ids 250..1, newest first
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to original),
        )
        val tracks = FakeTrackDao()
        val chats = FakeChatDao()
        val scanner = LibraryScanner(client, tracks, chats)
        scanner.fullScan()
        assertEquals(3, client.pageRequests.size)
        assertEquals(250L, chats.rows[1L]!!.lastScannedMessageId)

        // Two genuinely new messages arrive on top.
        client.setMessages(1L, listOf(audio(1L, 300L), audio(1L, 299L)) + original)
        val requestsBefore = client.pageRequests.size
        val updatesBefore = tracks.updateCount
        val insertsBefore = tracks.insertAllCount

        scanner.fullScan()

        // Fewer pages: the boundary halts the walk after the first one.
        assertEquals(requestsBefore + 1, client.pageRequests.size)
        // Nothing at or below the boundary was even merged (no update pass).
        assertEquals(updatesBefore, tracks.updateCount)
        // Only the two new messages went in — one batch insert for that page.
        assertEquals(insertsBefore + 1, tracks.insertAllCount)
        assertEquals(252, tracks.rows.size)
        // Progress is per-run: the second run indexed only the two new ones.
        assertEquals(2, scanner.progress.value.tracksIndexed)
        // The upsert between scans kept the boundary and the count.
        assertEquals(250L, chats.lastUpserted!!.lastScannedMessageId)
        assertEquals(250, chats.lastUpserted!!.trackCount)
        // The boundary then moved to the newest message actually seen.
        assertEquals(300L, chats.rows[1L]!!.lastScannedMessageId)
        assertEquals(252, chats.rows[1L]!!.trackCount)
    }

    private companion object {
        /** Simulated per-RPC latency; virtual time in runTest keeps it free. */
        const val RPC_DELAY_MS = 50L
    }
}
