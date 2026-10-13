package com.extrumz.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.extrumz.app.data.db.entity.PlayHistory
import com.extrumz.app.data.db.entity.Playlist
import com.extrumz.app.data.db.entity.PlaylistTrack
import com.extrumz.app.data.db.entity.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    suspend fun observeAllOnce(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: String): Playlist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "SELECT t.* FROM tracks t INNER JOIN playlist_tracks pt " +
            "ON t.fileUniqueId = pt.trackFileUniqueId " +
            "WHERE pt.playlistId = :playlistId ORDER BY pt.position ASC"
    )
    fun observeTracks(playlistId: String): Flow<List<Track>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: PlaylistTrack)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearEntries(playlistId: String)

    @Query(
        "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks " +
            "WHERE playlistId = :playlistId"
    )
    suspend fun nextPosition(playlistId: String): Int

    @Transaction
    suspend fun addTrack(playlistId: String, trackFileUniqueId: String, addedAt: Long) {
        val position = nextPosition(playlistId)
        upsertEntry(
            PlaylistTrack(
                playlistId = playlistId,
                trackFileUniqueId = trackFileUniqueId,
                position = position,
                addedAt = addedAt
            )
        )
    }

    @Query(
        "SELECT * FROM playlist_tracks WHERE playlistId = :playlistId " +
            "ORDER BY position ASC"
    )
    suspend fun getEntries(playlistId: String): List<PlaylistTrack>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackFileUniqueId = :trackId")
    suspend fun removeTrack(playlistId: String, trackId: String)

    @Query(
        "UPDATE playlist_tracks SET position = :position " +
            "WHERE playlistId = :playlistId AND trackFileUniqueId = :trackId"
    )
    suspend fun updatePosition(playlistId: String, trackId: String, position: Int)

    @Transaction
    suspend fun reorder(playlistId: String, orderedTrackIds: List<String>) {
        orderedTrackIds.forEachIndexed { index, trackId ->
            updatePosition(playlistId, trackId, index)
        }
    }

    @Transaction
    suspend fun move(playlistId: String, fromIndex: Int, toIndex: Int) {
        val entries = getEntries(playlistId).sortedBy { it.position }
        if (fromIndex !in entries.indices || toIndex !in entries.indices) return
        val reordered = entries.toMutableList()
        val item = reordered.removeAt(fromIndex)
        reordered.add(toIndex, item)
        // Single transaction; positional update only (no row delete/re-insert).
        reordered.forEachIndexed { index, entry ->
            if (entry.position != index) {
                updatePosition(playlistId, entry.trackFileUniqueId, index)
            }
        }
    }

    @Query("SELECT telegramChannelId FROM playlists WHERE id = :id")
    suspend fun getChannelId(id: String): Long?

    @Query("UPDATE playlists SET telegramInviteLink = :link, updatedAt = :now WHERE id = :id")
    suspend fun updateInviteLink(id: String, link: String?, now: Long)

    @Query("UPDATE playlists SET isCollaborative = :collaborative, updatedAt = :now WHERE id = :id")
    suspend fun setCollaborative(id: String, collaborative: Boolean, now: Long)

    @Query("UPDATE playlists SET description = :description, updatedAt = :now WHERE id = :id")
    suspend fun updateDescription(id: String, description: String?, now: Long)

    @Query("UPDATE playlists SET coverUrl = :coverUrl, updatedAt = :now WHERE id = :id")
    suspend fun updateCover(id: String, coverUrl: String?, now: Long)

    @Query("SELECT * FROM playlists WHERE telegramChannelId = :channelId")
    suspend fun getByChannelId(channelId: Long): Playlist?
}

@Dao
interface PlayHistoryDao {

    @Insert
    suspend fun insert(entry: PlayHistory)

    @Query(
        "SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<PlayHistory>>

    @Query("SELECT COUNT(*) FROM play_history WHERE completed = 1")
    fun observeCompletedCount(): Flow<Int>

    @Query(
        "SELECT trackFileUniqueId, COUNT(*) AS cnt FROM play_history " +
            "GROUP BY trackFileUniqueId ORDER BY cnt DESC LIMIT :limit"
    )
    fun observeTopTracks(limit: Int = 20): Flow<List<TrackPlayCount>>
}

data class TrackPlayCount(val trackFileUniqueId: String, val cnt: Int)
