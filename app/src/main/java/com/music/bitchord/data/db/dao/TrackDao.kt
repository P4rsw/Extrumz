package com.music.bitchord.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.music.bitchord.data.db.entity.Track
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

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    /** Rows indexed for one chat — the cumulative figure a chat's trackCount shows. */
    @Query("SELECT COUNT(*) FROM tracks WHERE sourceChatId = :chatId")
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

    @Query(
        "SELECT sourceChatId, COUNT(*) AS cnt FROM tracks " +
            "GROUP BY sourceChatId ORDER BY cnt DESC LIMIT :limit"
    )
    fun observeTopSources(limit: Int = 20): Flow<List<ChatTrackCount>>

    @Query(
        "SELECT * FROM tracks WHERE sourceChatId = :chatId AND playCount > 0 " +
            "ORDER BY playCount DESC LIMIT :limit"
    )
    fun observeTopFromChat(chatId: Long, limit: Int = 20): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks WHERE sourceChatId = :chatId ORDER BY sourceMessageId DESC"
    )
    fun observeByChat(chatId: Long): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks ORDER BY lastPlayed DESC NULLS LAST LIMIT :limit"
    )
    fun observeHistory(limit: Int = 50): Flow<List<Track>>

    @Transaction
    suspend fun upsertAll(tracks: List<Track>) {
        insertAll(tracks)
    }

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
    }

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
