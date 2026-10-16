package com.extrumz.app

import androidx.room.RoomRawQuery
import com.extrumz.app.data.db.dao.AlbumCount
import com.extrumz.app.data.db.dao.ArtistCount
import com.extrumz.app.data.db.dao.ArtistPlayCount
import com.extrumz.app.data.db.dao.ChatDao
import com.extrumz.app.data.db.dao.ChatTrackCount
import com.extrumz.app.data.db.dao.FingerprintRow
import com.extrumz.app.data.db.dao.MediaAlbumCount
import com.extrumz.app.data.db.dao.TrackDao
import com.extrumz.app.data.db.entity.ChatSource
import com.extrumz.app.data.db.entity.ChatType
import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.db.entity.TrackSource
import com.extrumz.app.data.telegram.ChatInfo
import com.extrumz.app.data.telegram.TelegramClientException
import com.extrumz.app.data.telegram.LibraryScanner
import com.extrumz.app.data.telegram.MusicTelegramClient
import com.extrumz.app.data.telegram.RemoteAudio
import com.extrumz.app.data.telegram.RemoteAudioPage
import com.extrumz.app.data.telegram.TelegramAuthState
import com.extrumz.app.data.telegram.TelegramConnectionState
import com.extrumz.app.data.telegram.TelegramCredentials
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
        override suspend fun touchSeen(ids: List<Long>, now: Long) {
            ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(lastSeenAt = now) } }
        }

        override suspend fun upsert(chat: ChatSource) {
            upsertCount++
            lastUpserted = chat
            rows[chat.chatId] = chat
        }

        override suspend fun upsertAll(chats: List<ChatSource>) = chats.forEach { upsert(it) }
        override suspend fun updateScanProgress(chatId: Long, messageId: Long, trackCount: Int) {
            rows[chatId]?.let { current ->
                val newId = when {
                    messageId > 0 -> maxOf(current.lastScannedMessageId, messageId)
                    messageId == -1L && current.lastScannedMessageId <= 0 -> -1L
                    else -> current.lastScannedMessageId
                }
                val newCount = when {
                    messageId > 0 -> trackCount
                    messageId == -1L && current.lastScannedMessageId <= 0 -> 0
                    else -> current.trackCount
                }
                rows[chatId] = current.copy(lastScannedMessageId = newId, trackCount = newCount)
            }
        }

        override suspend fun getTrackedOnce(): List<ChatSource> = rows.values.filter { it.isTracked }
        override suspend fun getAllOnce(): List<ChatSource> = rows.values.toList()
        override suspend fun refreshTrackCount(chatId: Long) {}
        override suspend fun setTracked(chatId: Long, tracked: Boolean) {}
        // F8: cover fill, with the same guard as Room — fill when empty,
        // upgrade when the stronger cover lands.
        override suspend fun setPhotoUrlIfEmpty(chatId: Long, photoUrl: String) {
            rows[chatId]?.let { chat ->
                if (chat.photoUrl.isNullOrBlank()) rows[chatId] = chat.copy(photoUrl = photoUrl)
            }
        }
        override suspend fun setPhotoUrl(chatId: Long, photoUrl: String) {
            rows[chatId]?.let { rows[chatId] = it.copy(photoUrl = photoUrl) }
        }
        override fun observeAllByTrackCount(): Flow<List<ChatSource>> =
            flowOf(rows.values.filter { it.trackCount > 0 }.sortedByDescending { it.trackCount })
        // 4d71d1a added this to ChatDao (server-side restore of missing chats)
        // and missed this fake — the whole unit-test source set stopped compiling.
        override fun observeAllChats(): Flow<List<ChatSource>> = flowOf(rows.values.toList())
    }

    private class FakeTrackDao : TrackDao {
        val rows = linkedMapOf<String, Track>()
        val sources = linkedSetOf<TrackSource>()
        var getByIdsCount = 0
        var getByIdCount = 0
        var insertAllCount = 0
        var insertCount = 0
        var updateCount = 0
        var ignoredCount = 0

        override suspend fun insertSources(sources: List<TrackSource>) {
            this.sources += sources
        }

        // Mirrors the SQL gate: rows whose (performer, title, duration) is
        // shared with a lower file id. Clean libraries answer 0 here, which
        // is what lets the convergence pass cost nothing on a normal scan.
        override suspend fun countCollidedDuplicates(): Int =
            rows.values
                .groupBy { Triple(it.performer, it.title, it.duration) }
                .values.count { group -> group.size > 1 }

        // The light fingerprint projection: same rows, no heavy columns.
        override suspend fun loadFingerprintRows(): List<FingerprintRow> =
            rows.values.map {
                FingerprintRow(
                    fileUniqueId = it.fileUniqueId,
                    performer = it.performer,
                    title = it.title,
                    duration = it.duration,
                    isFavorite = it.isFavorite,
                    playCount = it.playCount,
                    lastPlayed = it.lastPlayed,
                    mediaAlbumId = it.mediaAlbumId,
                )
            }

        override suspend fun getForMerge(ids: List<String>): List<Track> =
            ids.mapNotNull { rows[it] }

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

        var updateAllCount = 0
        override suspend fun updateAll(tracks: List<Track>) {
            updateAllCount++
            tracks.forEach { rows[it.fileUniqueId] = it }
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
        override suspend fun observeAllOnce(): List<Track> = rows.values.toList()
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

        // F7: fingerprint index + collapse.
        val repoints = mutableListOf<Pair<String, String>>()
        override suspend fun loadAllTracks(): List<Track> = rows.values.toList()
        override suspend fun deleteByFileUniqueIds(ids: List<String>) {
            ids.forEach { rows.remove(it) }
        }
        override suspend fun repointToKeeper(from: String, to: String) {
            repoints += from to to
        }
        override suspend fun rePointPlaylistTracks(from: String, to: String) = TODO()
        override suspend fun rePointPlaylistTracksLeftovers(from: String) = TODO()
        override suspend fun rePointPlayHistory(from: String, to: String) = TODO()
        override suspend fun rePointPlayHistoryLeftovers(from: String) = TODO()

        // F8: cover fill, same guard as Room — fill when empty, upgrade when
        // the stronger cover lands.
        override suspend fun setCoverIfEmpty(chatId: Long, messageId: Long, coverUrl: String) {
            getByChatAndMessage(chatId, messageId)?.let { track ->
                if (track.coverUrl.isNullOrBlank()) {
                    rows[track.fileUniqueId] = track.copy(coverUrl = coverUrl)
                }
            }
        }
        override suspend fun setCover(chatId: Long, messageId: Long, coverUrl: String) {
            getByChatAndMessage(chatId, messageId)?.let { track ->
                rows[track.fileUniqueId] = track.copy(coverUrl = coverUrl)
            }
        }
    }

    private class FakeTelegramClient(
        chats: List<ChatInfo>,
        private val messagesByChat: MutableMap<Long, List<RemoteAudio>> = mutableMapOf(),
        private val failingChats: Set<Long> = emptySet(),
        private val goneChats: Set<Long> = emptySet(),
        private val hangingChats: Set<Long> = emptySet(),
        private val listFailsWith: String? = null,
    ) : MusicTelegramClient {
        private val chats = chats

        override suspend fun countCandidateChatIds(): Int = chats.size
        override val authState: StateFlow<TelegramAuthState> =
            MutableStateFlow(TelegramAuthState.Ready)
        override val connectionState: StateFlow<TelegramConnectionState> =
            MutableStateFlow(TelegramConnectionState.READY)
        private val newAudioFeed = MutableSharedFlow<RemoteAudio>(extraBufferCapacity = 16)
        override val newAudioMessages: Flow<RemoteAudio> = newAudioFeed

        /** Test hook: models the client's incoming-audio emission. */
        suspend fun publishNewAudio(audio: RemoteAudio) {
            newAudioFeed.emit(audio)
        }


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
            if (chatId in goneChats) throw TelegramClientException("Chat not found", 400)
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

        var listTrackedCandidateChatsCount = 0
            private set

        override suspend fun listTrackedCandidateChats(): List<ChatInfo> {
            listTrackedCandidateChatsCount++
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
        override suspend fun getStreamPath(
            fileId: String,
            offset: Long,
            expectedUniqueId: String?,
        ): String = TODO()
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

    /**
     * Newest-first message list for one chat: ids count..1, newest first.
     * Chats other than 1 get an id offset: F7 collapses cross-chat reposts
     * by (performer,title,duration), and these fixtures index distinct songs
     * per chat. Chat 1 keeps raw ids — round-2 asserts exact
     * lastScannedMessageId values (250/300).
     */
    private fun descendingMessages(chatId: Long, count: Int): List<RemoteAudio> {
        val offset = (chatId - 1) * 1_000_000L
        return (count downTo 1).map { audio(chatId, it.toLong() + offset) }
    }

    // --- س۱: chat-level parallelism -----------------------------------------

    @Test
    fun `chats scan in parallel bounded by six and start in list order`() = runTest {
        val ids = (1L..12L).toList()
        val client = FakeTelegramClient(
            chats = ids.map { chatInfo(it) },
            messagesByChat = ids.associateWith { listOf(audio(it, 100L + it)) }.toMutableMap(),
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
        // 24, not 12: ingestDiscovered writes each newly found row (12), then
        // scanOneChat writes it again carrying its own fields. That second
        // write is load-bearing — updateScanProgress is an UPDATE and does
        // nothing against a missing row — so it stays; what changed is that it
        // now NAMES discoveredAt/lastSeenAt/isTracked instead of letting
        // REPLACE reset them to the entity defaults.
        assertEquals(24, chats.upsertCount)
        assertEquals(12, tracks.rows.size)
        assertEquals(12, progress.tracksIndexed)
    }

    // --- س۱: error policy ----------------------------------------------------

    @Test
    fun `one failing chat is contained and the rest still scan`() = runTest {
        val ids = listOf(1L, 2L, 3L)
        val client = FakeTelegramClient(
            chats = ids.map { chatInfo(it) },
            messagesByChat = ids.associateWith { listOf(audio(it, 10L + it)) }.toMutableMap(),
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
        // 6 = 3 discovered + 3 scanned; see the note on upsertCount above.
        assertEquals(6, chats.upsertCount)
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
            // Two batch lookups per page, not one: the first reads the page's
            // own rows, the second confirms which keepers still exist before
            // their source rows are written. Both are ONE query for the whole
            // page — what this test guards against is the per-song path
            // (getById / insert below), and neither count moved off the floor.
            assertEquals(4, tracks.getByIdsCount)
            assertEquals(2, tracks.insertAllCount)
            // The scanner itself never drops to the per-song path.
            assertEquals(0, tracks.getByIdCount)
            assertEquals(0, tracks.insertCount)
            assertEquals(150, tracks.rows.size)
            assertEquals(150, scanner.progress.value.tracksIndexed)
        }

    /**
     * The production bug this whole redesign exists for: the same song in two
     * chats used to leave ONE track row and NO trace of the loser, so the
     * second chat's filter lost the song forever while the main list kept it.
     * A repost must now leave a source row pointing at the keeper.
     */
    @Test
    fun `a repost folds into one track row but keeps BOTH chats as sources`() = runTest {
        // The fixture builds title/performer/duration from messageId alone, so
        // the SAME messageId in two chats is a same-song repost; uniqueId is
        // what differs, exactly like a re-upload. Different chats give the two
        // source rows distinct (chatId, messageId) keys.
        val inChatOne = audio(1L, 10L, uniqueId = "file-a")
        val inChatTwo = audio(2L, 10L, uniqueId = "file-b")
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L), chatInfo(2L)),
            messagesByChat = mutableMapOf(
                1L to listOf(inChatOne),
                2L to listOf(inChatTwo),
            ),
        )
        val tracks = FakeTrackDao()
        val scanner = LibraryScanner(client, tracks, FakeChatDao())

        scanner.fullScan()

        // One song, one row — F7 still does its job.
        assertEquals(1, tracks.rows.size)
        // ...but both homes survive, which is what the channel filter reads.
        val homes = tracks.sources.map { it.chatId }.toSet()
        assertEquals(setOf(1L, 2L), homes)
        // The loser points at the keeper rather than at a row that never was.
        val keeper = tracks.rows.values.first()
        assertTrue(tracks.sources.all { it.trackFileUniqueId == keeper.fileUniqueId })
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

    // --- round2: Gemini review findings -------------------------------------

    @Test
    fun `a second fullScan is a no-op while one runs and cancel frees the gate`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to listOf(audio(1L, 100L))),
            hangingChats = setOf(1L),
        )
        val scanner = LibraryScanner(client, FakeTrackDao(), FakeChatDao())
        val job = launch { scanner.fullScan() }
        // The first scan passes the gate and parks inside the hanging chat.
        testScheduler.advanceUntilIdle()
        assertTrue(scanner.progress.value.isRunning)

        // With the gate closed this returns immediately; with the old
        // read-then-write guard it would queue a SECOND scan behind the first
        // and park here until the timeout (null).
        val second = withTimeoutOrNull(1_000) { scanner.fullScan() }
        assertNotNull(second)

        job.cancelAndJoin()
        // The finally must clear isRunning on cancellation — otherwise a
        // cancelled scan leaves the banner and the start button stuck.
        assertFalse(scanner.progress.value.isRunning)
    }

    @Test
    fun `a file already in the DB is written back with one batched update`() = runTest {
        val chats = FakeChatDao()
        val tracks = FakeTrackDao()
        // A first channel indexes the file the normal way.
        val first = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to listOf(audio(1L, 100L, uniqueId = "shared-file"))),
        )
        LibraryScanner(first, tracks, chats).fullScan()
        assertEquals(1, tracks.rows.size)

        // A second channel carries the SAME file — the merge-update path.
        val second = FakeTelegramClient(
            chats = listOf(chatInfo(2L)),
            messagesByChat = mutableMapOf(2L to listOf(audio(2L, 100L, uniqueId = "shared-file"))),
        )
        LibraryScanner(second, tracks, chats).fullScan()

        // One page of already-known files → exactly one batched update call and
        // zero per-row statements (the N+1 the review flagged).
        assertEquals(1, tracks.updateAllCount)
        assertEquals(0, tracks.updateCount)
    }

    // --- round3: quiet re-pass after the scan (mid-sync misses) -------------

    @Test
    fun `quiet re-pass scans a candidate whose data arrived after the first pass`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L), chatInfo(2L)),
            messagesByChat = mutableMapOf(1L to descendingMessages(1L, 3)),
        )
        val chats = FakeChatDao()
        val tracks = FakeTrackDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()

        // First pass: chat 1 indexed; chat 2 answered empty (sync race) and
        // therefore kept lastScannedMessageId = 0 — the launch bug.
        assertEquals(3, tracks.rows.size)
        assertEquals(0L, chats.rows[2L]!!.lastScannedMessageId)

        // TDLib finishes syncing chat 2 inside the quiet window.
        client.setMessages(2L, descendingMessages(2L, 5))
        scanner.quietRepass()

        assertEquals(8, tracks.rows.size)
        assertTrue(chats.rows[2L]!!.lastScannedMessageId > 0L)
        // Already-scanned chat 1 is NOT touched again by the re-pass.
        assertEquals(1, client.pageRequests.count { it.first == 1L })
        // The pass never surfaces on the UI.
        assertFalse(scanner.progress.value.isRunning)
        assertNull(scanner.progress.value.lastError)
    }

    @Test
    fun `quiet re-pass does no work when every candidate is already scanned`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to descendingMessages(1L, 3)),
        )
        val scanner = LibraryScanner(client, FakeTrackDao(), FakeChatDao())
        scanner.fullScan()
        val requestsAfterScan = client.pageRequests.size
        val candidateCallsAfterScan = client.listTrackedCandidateChatsCount

        scanner.quietRepass()

        assertEquals(requestsAfterScan, client.pageRequests.size)
        assertEquals(candidateCallsAfterScan, client.listTrackedCandidateChatsCount)
        assertFalse(scanner.progress.value.isRunning)
    }

    @Test
    fun `quiet re-pass yields to a scan that owns the gate`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            hangingChats = setOf(1L),
        )
        val scanner = LibraryScanner(client, FakeTrackDao(), FakeChatDao())
        val job = launch { scanner.fullScan() }
        testScheduler.advanceUntilIdle()
        assertTrue(scanner.progress.value.isRunning)

        // The gate is held: the re-pass must come back without scanning anything.
        scanner.quietRepass()

        assertTrue(client.pageRequests.isEmpty())
        job.cancelAndJoin()
        assertFalse(scanner.progress.value.isRunning)
    }

    // --- round 4: tracked chats the main list never reports (archived/left) --

    @Test
    fun `fullScan scans a tracked chat the main list does not contain`() = runTest {
        // Chat 7 lives on the shelf (the app's own table) but never in
        // GetChats(ChatListMain) — the archived-dialog hole the device showed
        // on 2026-10-06: no SearchChatMessages request was ever sent for it.
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(
                1L to descendingMessages(1L, 3),
                7L to descendingMessages(7L, 4),
            ),
        )
        val chats = FakeChatDao()
        chats.rows[7L] = ChatSource(
            chatId = 7L,
            title = "Archived channel",
            username = null,
            photoUrl = null,
            type = ChatType.PRIVATE,
        )
        val tracks = FakeTrackDao()

        LibraryScanner(client, tracks, chats).fullScan()

        assertTrue(chats.rows[7L]!!.lastScannedMessageId > 0L)
        assertEquals(7, tracks.rows.size) // 3 + 4
        assertFalse(chats.rows[7L]!!.isTracked == false)
    }

    @Test
    fun `quiet re-pass retries a shelf-only chat after the sync window`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to descendingMessages(1L, 3)),
        )
        val chats = FakeChatDao()
        // An archived music chat: on the shelf with history, outside the main
        // list, and its messages arrive only after the sync window.
        chats.rows[9L] = ChatSource(
            chatId = 9L,
            title = "Archived music",
            username = null,
            photoUrl = null,
            type = ChatType.CHANNEL,
            lastScannedMessageId = 0L,
            trackCount = 522,
        )
        val tracks = FakeTrackDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()

        // First pass found nothing (empty page) — the launch-time victim.
        assertEquals(0L, chats.rows[9L]!!.lastScannedMessageId)
        client.setMessages(9L, descendingMessages(9L, 5))

        scanner.quietRepass()

        assertTrue(chats.rows[9L]!!.lastScannedMessageId > 0L)
        assertEquals(8, tracks.rows.size) // 3 + 5
        assertFalse(scanner.progress.value.isRunning)
        assertNull(scanner.progress.value.lastError)
    }

    // --- round 5: a chat that is gone from Telegram entirely -----------------

    @Test
    fun `a gone chat is untracked silently instead of failing the banner`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to descendingMessages(1L, 3)),
            goneChats = setOf(7L),
        )
        val chats = FakeChatDao()
        chats.rows[7L] = ChatSource(
            chatId = 7L,
            title = "Deleted channel",
            username = null,
            photoUrl = null,
            type = ChatType.CHANNEL,
            trackCount = 10,
        )
        val tracks = FakeTrackDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()

        // The row stays (history intact) but leaves the scan set, and the
        // launch is NOT turned into a red banner.
        assertFalse(chats.rows[7L]!!.isTracked)
        assertNull(scanner.progress.value.lastError)
        assertFalse(scanner.progress.value.isRunning)
        assertEquals(3, tracks.rows.size)

        // A later pass must not spend a single request on the dead chat.
        val before = client.pageRequests.size
        scanner.quietRepass()
        assertEquals(before, client.pageRequests.size)
    }

    // --- round 6: "Chat not found" on a chat TDLib itself listed -------------

    @Test
    fun `a chat that TDLib lists keeps its tracking when the search misses`() = runTest {
        // The shape of 2026-10-07: the chat is IN the candidate list TDLib just
        // reported (it was never gone — its dialog was simply in ChatListArchive,
        // or the local cache had not caught up), yet SearchChatMessages answers
        // "Chat not found". Reading that as "left Telegram" untracked 18 live
        // channels, so the row must survive and the failure must surface
        // instead of vanishing.
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(7L)),
            goneChats = setOf(7L),
        )
        val chats = FakeChatDao()
        chats.rows[7L] = ChatSource(
            chatId = 7L,
            title = "Archived music",
            username = null,
            photoUrl = null,
            type = ChatType.CHANNEL,
            trackCount = 10,
        )
        val tracks = FakeTrackDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()

        assertTrue("a listed chat must never be untracked", chats.rows[7L]!!.isTracked)
        assertTrue(
            "and the miss is reported, not swallowed",
            scanner.progress.value.lastError.orEmpty().contains("Chat not found"),
        )
        assertFalse(scanner.progress.value.isRunning)
    }

    // --- م۴: live indexing ---------------------------------------------------

    @Test
    fun `live indexing picks up a new audio in tracked chats and skips untracked ones`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to mutableListOf(audio(1L, 100L))),
        )
        val tracks = FakeTrackDao()
        val chats = FakeChatDao()
        val scanner = LibraryScanner(client, tracks, chats)
        scanner.fullScan() // seeds chat 1's tracked row, exactly like production
        val before = tracks.rows.size
        assertTrue(before > 0)

        scanner.startLiveIndexing(backgroundScope)
        testScheduler.runCurrent() // the collector must be subscribed before emit
        client.publishNewAudio(audio(1L, 200L))
        client.publishNewAudio(audio(9L, 300L)) // chat 9 was never tracked
        // runCurrent, NOT advanceUntilIdle: the SharedFlow resume is an
        // immediate task and the scheduler pump that only advances virtual
        // time (advanceUntilIdle) leaves immediate tasks unpumped here.
        testScheduler.runCurrent()

        // Only the tracked chat's fresh audio landed; the stray chat is out.
        assertEquals(before + 1, tracks.rows.size)
        assertNotNull(
            tracks.rows.values.firstOrNull {
                it.sourceChatId == 1L && it.sourceMessageId == 200L
            },
        )
        assertNull(tracks.rows.values.firstOrNull { it.sourceChatId == 9L })
        assertNull(scanner.progress.value.lastError)

        // Repeat starts are no-ops: one collector, no double indexing.
        scanner.startLiveIndexing(backgroundScope)
        testScheduler.runCurrent()
        assertEquals(before + 1, tracks.rows.size)
    }

    // --- F7: one row per song -------------------------------------------------

    @Test
    fun `F7 collapses even when the scan finds no new messages`() = runTest {
        // Device case: every chat is scanned to the latest message, so no
        // batchIndex call ever happens — the cleanup must not depend on it.
        val tracks = FakeTrackDao()
        tracks.rows["k2"] = Track(
            fileUniqueId = "k2", fileId = "f2",
            title = "Twin", performer = "Same", duration = 333,
        )
        tracks.rows["k1"] = Track(
            fileUniqueId = "k1", fileId = "f1",
            title = "Twin", performer = "Same", duration = 333, isFavorite = true,
        )
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(), // nothing new anywhere
        )
        val scanner = LibraryScanner(client, tracks, FakeChatDao())

        scanner.fullScan()

        assertNotNull(tracks.rows["k1"])
        assertNull(tracks.rows["k2"])
        assertEquals(listOf("k2" to "k1"), tracks.repoints)
        assertTrue(tracks.rows.getValue("k1").isFavorite)
    }

    @Test
    fun `F7 collapses pre-existing duplicate rows and re-points their references`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L)),
            messagesByChat = mutableMapOf(1L to listOf(audio(1L, 100L))),
        )
        val tracks = FakeTrackDao()
        // The same song indexed twice under two Telegram file ids.
        tracks.rows["k2"] = Track(
            fileUniqueId = "k2", fileId = "f2",
            title = "Same Song", performer = "Artist", duration = 100,
            playCount = 2, lastPlayed = 5L,
        )
        tracks.rows["k1"] = Track(
            fileUniqueId = "k1", fileId = "f1",
            title = "Same Song", performer = "Artist", duration = 100,
            isFavorite = true, playCount = 3, lastPlayed = 9L,
        )
        val chats = FakeChatDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan() // the first index load performs the collapse

        assertNotNull(tracks.rows["k1"])
        assertNull(tracks.rows["k2"])
        assertEquals(listOf("k2" to "k1"), tracks.repoints) // references followed the keeper
        val keeper = tracks.rows.getValue("k1")
        assertTrue(keeper.isFavorite) // favorites OR'd across the group
        assertEquals(5, keeper.playCount) // plays summed
        assertEquals(9L, keeper.lastPlayed) // newest play kept
        assertNotNull(tracks.rows["u-1-100"]) // the scanned song still indexed
        assertNull(scanner.progress.value.lastError)

        // Idempotent: a second pass finds groups of one and writes nothing.
        val repointsAfterFirst = tracks.repoints.toList()
        scanner.fullScan()
        assertEquals(repointsAfterFirst, tracks.repoints)
        assertEquals(2, tracks.rows.size)
    }

    @Test
    fun `F7 drops a repost in a later chat instead of inserting a second row`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L), chatInfo(2L)),
            messagesByChat = mutableMapOf(
                1L to listOf(audio(1L, 500L)),
                2L to listOf(audio(2L, 500L)), // same song, other file id
            ),
        )
        val tracks = FakeTrackDao()
        val chats = FakeChatDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()

        val songRows = tracks.rows.values.filter { it.title == "title 500" }
        assertEquals(1, songRows.size) // one row serves both chats
        assertNull(scanner.progress.value.lastError)
    }

    @Test
    fun `F7 skips a live repost arriving after the scan`() = runTest {
        val tracks = FakeTrackDao()
        tracks.rows["keeper"] = Track(
            fileUniqueId = "keeper", fileId = "f1",
            title = "title 500", performer = "performer", duration = 1,
        )
        val client = FakeTelegramClient(chats = listOf(chatInfo(1L)), messagesByChat = mutableMapOf())
        val chats = FakeChatDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.startLiveIndexing(backgroundScope)
        testScheduler.runCurrent()
        client.publishNewAudio(audio(1L, 500L, uniqueId = "u-live-500"))
        testScheduler.runCurrent()

        assertEquals(1, tracks.rows.size) // keeper only — the live repost was dropped
        assertNull(tracks.rows["u-live-500"])
    }

    @Test
    fun `a rescan preserves the downloaded HQ avatar instead of clobbering it`() = runTest {
        // The regression the device showed on 10-03: GetChats only carries the
        // inline minithumbnail, and scanOneChat's REPLACE wrote it over the
        // covers/hq_* avatar Tier 2 had downloaded — every channel went back
        // to pixelated the moment the user tapped re-scan (measured: 6 HQ
        // avatars → 0). The full-size URL is strictly better than anything
        // GetChats can hand back, so it must survive the upsert.
        val hq = "file:///data/user/0/com.extrumz.app/files/covers/hq_abc.jpg"
        val chats = FakeChatDao()
        chats.rows[1L] = ChatSource(
            chatId = 1L,
            title = "chat 1",
            username = null,
            photoUrl = hq,
            type = ChatType.CHANNEL,
        )
        val client = FakeTelegramClient(
            chats = listOf(ChatInfo(
                chatId = 1L, title = "chat 1", username = null,
                isChannel = true, isGroup = false,
                photoUrl = "file:///data/user/0/com.extrumz.app/files/covers/9911.jpg",
            )),
            messagesByChat = mutableMapOf(1L to descendingMessages(1L, 2)),
        )

        LibraryScanner(client, FakeTrackDao(), chats).fullScan()

        assertEquals("the downloaded avatar survives the rescan", hq, chats.rows[1L]!!.photoUrl)
    }

    @Test
    fun `a rescan still fills a chat whose avatar row has none`() = runTest {
        // The preserve rule is not a freeze: a row with nothing must take
        // whatever the scan brings, or channels scanned before photoUrl
        // existed would stay coverless forever.
        val inline = "file:///data/user/0/com.extrumz.app/files/covers/9911.jpg"
        val chats = FakeChatDao()
        chats.rows[1L] = ChatSource(
            chatId = 1L,
            title = "chat 1",
            username = null,
            photoUrl = null,
            type = ChatType.CHANNEL,
        )
        val client = FakeTelegramClient(
            chats = listOf(ChatInfo(
                chatId = 1L, title = "chat 1", username = null,
                isChannel = true, isGroup = false,
                photoUrl = inline,
            )),
            messagesByChat = mutableMapOf(1L to descendingMessages(1L, 2)),
        )

        LibraryScanner(client, FakeTrackDao(), chats).fullScan()

        assertEquals(inline, chats.rows[1L]!!.photoUrl)
    }

    @Test
    fun `quiet re-pass marks empty chat settled and subsequent fullScan preserves sentinel`() = runTest {
        val client = FakeTelegramClient(
            chats = listOf(chatInfo(1L), chatInfo(2L)),
            messagesByChat = mutableMapOf(1L to descendingMessages(1L, 3)),
        )
        val chats = FakeChatDao()
        val tracks = FakeTrackDao()
        val scanner = LibraryScanner(client, tracks, chats)

        scanner.fullScan()
        // 1. First pass leaves empty chat at 0L (sync-race safety window)
        assertEquals(0L, chats.rows[2L]!!.lastScannedMessageId)

        // 2. Quiet re-pass runs with settleEmpty = true and settles it to -1L
        scanner.quietRepass()
        assertEquals(LibraryScanner.SCAN_COMPLETE_NO_TRACKS, chats.rows[2L]!!.lastScannedMessageId)

        // 3. Subsequent fullScan: quiet settled chat costs exactly 1 page check (0 pages past boundary) and preserves -1L
        val chat2RequestsBeforeFullScan = client.pageRequests.count { it.first == 2L }
        scanner.fullScan()
        assertEquals(LibraryScanner.SCAN_COMPLETE_NO_TRACKS, chats.rows[2L]!!.lastScannedMessageId)
        assertEquals(chat2RequestsBeforeFullScan + 1, client.pageRequests.count { it.first == 2L })

        // 4. Quiet re-pass skips chat 2 because lastScannedMessageId != 0L:
        // Exits in 0ms without even calling listTrackedCandidateChats!
        val chat2RequestsBeforeRepass = client.pageRequests.count { it.first == 2L }
        val candidateCallsBeforeRepass = client.listTrackedCandidateChatsCount
        scanner.quietRepass()
        assertEquals(chat2RequestsBeforeRepass, client.pageRequests.count { it.first == 2L })
        assertEquals(candidateCallsBeforeRepass, client.listTrackedCandidateChatsCount)

        // 5. Settled chat receives new audio: picked up immediately, promotes boundary above -1L
        client.setMessages(2L, listOf(audio(2L, 42L)))
        val chat2RequestsBeforePickup = client.pageRequests.count { it.first == 2L }
        scanner.fullScan()
        assertEquals(42L, chats.rows[2L]!!.lastScannedMessageId)
        assertEquals(1, chats.rows[2L]!!.trackCount)
        assertEquals(1, tracks.countForChat(2L))
        assertEquals(chat2RequestsBeforePickup + 1, client.pageRequests.count { it.first == 2L })
    }

    private companion object {
        /** Simulated per-RPC latency; virtual time in runTest keeps it free. */
        const val RPC_DELAY_MS = 50L
    }

    /**
     * The sentinel is only real if it survives a rescan, and only reachable if
     * `settleEmpty` is actually passed. This is the test that fails when either
     * rots: dropping the parameter leaves chat 2 at 0, and regressing
     * `stopBefore != 0L` back to `> 0L` wipes -1 on the very next fullScan.
     */
    @Test
    fun `updateScanProgress preserves sentinel and never lets 0 overwrite -1 or positive IDs`() = runTest {
        val chats = FakeChatDao()
        chats.upsert(
            ChatSource(
                chatId = 1L,
                title = "Test",
                username = null,
                photoUrl = null,
                type = com.extrumz.app.data.db.entity.ChatType.PRIVATE,
                isTracked = true,
                lastScannedMessageId = 0L,
                trackCount = 0,
            )
        )

        // 1. Settling to -1 succeeds from 0
        chats.updateScanProgress(1L, LibraryScanner.SCAN_COMPLETE_NO_TRACKS, 0)
        assertEquals(LibraryScanner.SCAN_COMPLETE_NO_TRACKS, chats.rows[1L]!!.lastScannedMessageId)

        // 2. Stale write of 0 cannot overwrite -1
        chats.updateScanProgress(1L, 0L, 0)
        assertEquals(LibraryScanner.SCAN_COMPLETE_NO_TRACKS, chats.rows[1L]!!.lastScannedMessageId)

        // 3. Positive track boundary upgrades -1
        chats.updateScanProgress(1L, 500L, 10)
        assertEquals(500L, chats.rows[1L]!!.lastScannedMessageId)
        assertEquals(10, chats.rows[1L]!!.trackCount)

        // 4. Stale write of 0 or -1 cannot overwrite positive boundary
        chats.updateScanProgress(1L, 0L, 0)
        assertEquals(500L, chats.rows[1L]!!.lastScannedMessageId)
        chats.updateScanProgress(1L, -1L, 0)
        assertEquals(500L, chats.rows[1L]!!.lastScannedMessageId)

        // 5. Monotonic increase moves boundary forward, never backward
        chats.updateScanProgress(1L, 600L, 12)
        assertEquals(600L, chats.rows[1L]!!.lastScannedMessageId)
        chats.updateScanProgress(1L, 400L, 12)
        assertEquals(600L, chats.rows[1L]!!.lastScannedMessageId)
    }
}
