package com.extrumz.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.extrumz.app.data.db.entity.Track
import com.extrumz.app.data.db.entity.TrackSource
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC")
    suspend fun observeAllOnce(): List<Track>

    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE fileUniqueId = :id")
    suspend fun getById(id: String): Track?

    @Query("SELECT * FROM tracks WHERE fileUniqueId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Track>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<Track>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(track: Track)

    @Update
    suspend fun update(track: Track)

    /** One transaction for a page of merge-updates (Room updates by primary key). */
    @Update
    suspend fun updateAll(tracks: List<Track>)

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE fileUniqueId = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query(
        "UPDATE tracks SET playCount = playCount + 1, lastPlayed = :playedAt " +
            "WHERE fileUniqueId = :id"
    )
    suspend fun recordPlay(id: String, playedAt: Long)

    @Query(
        "SELECT * FROM tracks WHERE sourceChatId = :chatId AND sourceMessageId = :messageId"
    )
    suspend fun getByChatAndMessage(chatId: Long, messageId: Long): Track?

    /**
     * F8 cover fill: record a row's inline thumbnail — but only while the row
     * has none, so a rescan, an id3 parse or an earlier fill is never
     * clobbered by a weaker source.
     */
    @Query(
        "UPDATE tracks SET coverUrl = :coverUrl " +
            "WHERE sourceChatId = :chatId AND sourceMessageId = :messageId " +
            "AND (coverUrl IS NULL OR coverUrl = '')"
    )
    suspend fun setCoverIfEmpty(chatId: Long, messageId: Long, coverUrl: String)

    /**
     * F8 quality upgrade: the full-size cover replaces the ~90px inline one,
     * in the one column every surface reads — so rows, cards and the player
     * all redraw sharp from a single write. Inequality-guarded: an unchanged
     * URL emits no Flow event.
     */
    @Query(
        "UPDATE tracks SET coverUrl = :coverUrl " +
            "WHERE sourceChatId = :chatId AND sourceMessageId = :messageId " +
            "AND IFNULL(coverUrl, '') != :coverUrl"
    )
    suspend fun setCover(chatId: Long, messageId: Long, coverUrl: String)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    /**
     * Rows indexed for one chat — the cumulative figure a chat's trackCount
     * shows. Counted through `track_sources` for the same reason as
     * [observeByChat]: after F7 folds a song into a keeper living elsewhere,
     * `tracks.sourceChatId` no longer mentions this chat at all.
     */
    @Query(
        "SELECT COUNT(*) FROM track_sources s " +
            "INNER JOIN tracks t ON t.fileUniqueId = s.trackFileUniqueId " +
            "WHERE s.chatId = :chatId"
    )
    suspend fun countForChat(chatId: Long): Int

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY lastPlayed DESC")
    fun observeFavorites(): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks WHERE performer LIKE '%' || :query || '%' " +
            "OR title LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%' " +
            "OR tags LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE"
    )
    fun search(query: String): Flow<List<Track>>

    /**
     * FTS5 MATCH search. tracks_fts is created outside Room schema (callback),
     * so the SQL must be validated via RawQuery rather than a static @Query.
     */
    @androidx.room.RawQuery(observedEntities = [Track::class])
    fun searchFtsRaw(query: androidx.room.RoomRawQuery): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks WHERE performer = :performer " +
            "ORDER BY album, trackNumber, title COLLATE NOCASE"
    )
    fun observeByArtist(performer: String): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks WHERE album = :album " +
            "ORDER BY trackNumber, title COLLATE NOCASE"
    )
    fun observeByAlbum(album: String): Flow<List<Track>>

    @Query(
        "SELECT performer, COUNT(*) AS cnt FROM tracks " +
            "WHERE performer IS NOT NULL AND performer != '' " +
            "GROUP BY performer ORDER BY cnt DESC"
    )
    fun observeArtists(): Flow<List<ArtistCount>>

    @Query(
        "SELECT album, COUNT(*) AS cnt FROM tracks " +
            "WHERE album IS NOT NULL AND album != '' " +
            "GROUP BY album ORDER BY album COLLATE NOCASE"
    )
    fun observeAlbums(): Flow<List<AlbumCount>>

    @Query(
        "SELECT mediaAlbumId, COUNT(*) AS cnt FROM tracks " +
            "WHERE mediaAlbumId IS NOT NULL GROUP BY mediaAlbumId HAVING cnt > 1"
    )
    fun observeMediaAlbums(): Flow<List<MediaAlbumCount>>

    @Query(
        "SELECT * FROM tracks WHERE mediaAlbumId = :albumId " +
            "ORDER BY dateAdded DESC"
    )
    fun observeByMediaAlbum(albumId: Long): Flow<List<Track>>

    @Query(
        "SELECT performer, playCount FROM tracks " +
            "WHERE performer IS NOT NULL AND performer != '' AND playCount > 0 " +
            "ORDER BY playCount DESC LIMIT :limit"
    )
    fun observeTopArtists(limit: Int = 20): Flow<List<ArtistPlayCount>>

    @Query(
        "SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC, lastPlayed DESC LIMIT :limit"
    )
    fun observeMostPlayed(limit: Int = 50): Flow<List<Track>>

    /**
     * The source list itself. Counted from `track_sources` so a channel keeps
     * its true size after a dedupe folded its copy of a song into a keeper
     * living somewhere else — counting `tracks.sourceChatId` would shrink a
     * channel every time one of its songs was reposted elsewhere first.
     */
    @Query(
        "SELECT chatId AS sourceChatId, COUNT(*) AS cnt FROM track_sources " +
            "GROUP BY chatId ORDER BY cnt DESC LIMIT :limit"
    )
    fun observeTopSources(limit: Int = 20): Flow<List<ChatTrackCount>>

    @Query(
        "SELECT t.* FROM tracks t " +
            "INNER JOIN track_sources s ON s.trackFileUniqueId = t.fileUniqueId " +
            "WHERE s.chatId = :chatId AND t.playCount > 0 " +
            "ORDER BY t.playCount DESC LIMIT :limit"
    )
    fun observeTopFromChat(chatId: Long, limit: Int = 20): Flow<List<Track>>

    /**
     * One channel's songs, newest first.
     *
     * Reads `track_sources` rather than `tracks.sourceChatId`: a song is one
     * row in `tracks` (F7) but can belong to many chats, and the loser of a
     * dedupe used to take its chat association with it when it was deleted.
     * The ORDER BY is the source's messageId because message ids count up
     * within each chat — the keeper's own sourceMessageId belongs to a
     * different feed and would scramble this channel's order.
     */
    @Query(
        "SELECT t.* FROM tracks t " +
            "INNER JOIN track_sources s ON s.trackFileUniqueId = t.fileUniqueId " +
            "WHERE s.chatId = :chatId ORDER BY s.messageId DESC"
    )
    fun observeByChat(chatId: Long): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks ORDER BY lastPlayed DESC NULLS LAST LIMIT :limit"
    )
    fun observeHistory(limit: Int = 50): Flow<List<Track>>

    /**
     * Which rows are in one chat, as a reactive set.
     *
     * [observeByChat] answers the same question with the full rows, but the
     * songs list already holds every `Track` in memory and only needs to know
     * membership — re-querying the whole channel would duplicate a list the
     * caller has. And search filters `Song`s, which carry no join of their own.
     *
     * Reads `track_sources` for the reason [observeByChat] gives: one row can
     * belong to many chats, and `tracks.sourceChatId` remembers only one.
     */
    @Query(
        "SELECT trackFileUniqueId FROM track_sources " +
            "WHERE chatId = :chatId " +
            "GROUP BY trackFileUniqueId ORDER BY MAX(messageId) DESC"
    )
    fun observeIdsInChat(chatId: Long): Flow<List<String>>

    @Transaction
    suspend fun upsertAll(tracks: List<Track>) {
        insertAll(tracks)
    }

    /** Every known home of one song. IGNORE: six chats scan in parallel. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSources(sources: List<TrackSource>)

    /**
     * F7 dedupe: whole-table load for the once-per-process fingerprint index.
     *
     * Deliberately NOT `SELECT *`. This runs once per process and every
     * scanner keeps the result in memory for the whole session; the full row
     * carries lyrics, caption and tags, which are megabytes on a library this
     * size and none of them reach the fingerprint. Keep this projection
     * minimal — adding a column here taxes every scan for a value no caller
     * reads.
     */
    @Query(
        "SELECT fileUniqueId, performer, title, duration, isFavorite, " +
            "playCount, lastPlayed, mediaAlbumId FROM tracks"
    )
    suspend fun loadFingerprintRows(): List<FingerprintRow>

    /**
     * The full rows for just the ids that need merging, fetched only when the
     * light scan above actually found a duplicate group. Keeps the common
     * case (no duplicates) off the heavy columns entirely.
     */
    @Query("SELECT * FROM tracks WHERE fileUniqueId IN (:ids)")
    suspend fun getForMerge(ids: List<String>): List<Track>

    /**
 * How many rows would collide with another row's (performer, title, duration).
 *
 * Runs entirely inside SQLite and answers on an indexed grouping — a clean
 * library returns 0 in milliseconds without moving a single row into Kotlin.
 * This is the gate for the post-scan convergence pass: the inline dedupe in
 * batchIndex folds everything it SEES, but rows written before the index
 * existed, or a keeper picked before better metadata arrived, can only be
 * found by looking at what is actually stored.
 */
@Query(
    """
    SELECT COUNT(*) FROM tracks t
    WHERE EXISTS (
        SELECT 1 FROM tracks o
        WHERE o.performer = t.performer
          AND o.title = t.title
          AND o.duration = t.duration
          AND o.fileUniqueId < t.fileUniqueId
    )
    """
)
suspend fun countCollidedDuplicates(): Int

/** F7 dedupe: whole-table load for the once-per-process fingerprint index. */
    @Query("SELECT * FROM tracks")
    suspend fun loadAllTracks(): List<Track>

    @Query("DELETE FROM tracks WHERE fileUniqueId IN (:ids)")
    suspend fun deleteByFileUniqueIds(ids: List<String>)

    /**
     * F7 dedupe: move EVERY playlist/history row of a collapsed duplicate onto
     * its keeper. OR IGNORE first — a playlist that holds both songs keeps the
     * keeper's row — then sweep whatever the conflict left, so no loser file
     * id survives in a user-owned table. Both tables mirror into FTS5 by
     * trigger and follow the UPDATE/DELETE on their own.
     */
    @Transaction
    suspend fun repointToKeeper(from: String, to: String) {
        rePointPlaylistTracks(from, to)
        rePointPlaylistTracksLeftovers(from)
        rePointPlayHistory(from, to)
        rePointPlayHistoryLeftovers(from)
        // The sources too, and this one is not bookkeeping. TrackSource is
        // declared with onDelete = CASCADE, so deleting the loser takes every
        // chat it was found in with it — the merge was quietly erasing the
        // associations it exists to preserve, and a song then stayed reachable
        // from one channel and vanished from the rest. The comment above
        // batchIndex's sourceFor has claimed the chat survives the merge for a
        // while; it did not.
        rePointTrackSources(from, to)
        rePointTrackSourcesLeftovers(from)
    }

    @Query("UPDATE OR IGNORE track_sources SET trackFileUniqueId = :to WHERE trackFileUniqueId = :from")
    suspend fun rePointTrackSources(from: String, to: String)

    @Query("DELETE FROM track_sources WHERE trackFileUniqueId = :from")
    suspend fun rePointTrackSourcesLeftovers(from: String)

    @Query("UPDATE OR IGNORE playlist_tracks SET trackFileUniqueId = :to WHERE trackFileUniqueId = :from")
    suspend fun rePointPlaylistTracks(from: String, to: String)

    @Query("DELETE FROM playlist_tracks WHERE trackFileUniqueId = :from")
    suspend fun rePointPlaylistTracksLeftovers(from: String)

    @Query("UPDATE OR IGNORE play_history SET trackFileUniqueId = :to WHERE trackFileUniqueId = :from")
    suspend fun rePointPlayHistory(from: String, to: String)

    @Query("DELETE FROM play_history WHERE trackFileUniqueId = :from")
    suspend fun rePointPlayHistoryLeftovers(from: String)
}

data class ArtistCount(val performer: String, val cnt: Int)
data class AlbumCount(val album: String, val cnt: Int)
data class MediaAlbumCount(val mediaAlbumId: Long, val cnt: Int)
data class ArtistPlayCount(val performer: String, val playCount: Int)
data class ChatTrackCount(val sourceChatId: Long, val cnt: Int)

/**
 * The columns F7's fingerprint actually reads, for the once-per-process index.
 *
 * Deliberately NOT `Track`: the index is held in memory for the whole session
 * and a full row carries lyrics, caption and tags — megabytes on a library
 * this size, none of which reaches the key.
 */
data class FingerprintRow(
    val fileUniqueId: String,
    val performer: String,
    val title: String,
    val duration: Int,
    val isFavorite: Boolean,
    val playCount: Int,
    val lastPlayed: Long?,
    val mediaAlbumId: Long?,
)
