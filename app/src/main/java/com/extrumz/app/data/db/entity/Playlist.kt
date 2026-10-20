package com.extrumz.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * User playlist; optionally mirrored by a private Telegram channel
 * ([telegramChannelId] set once bound).
 */
@Serializable
@Entity(
    tableName = "playlists",
    indices = [Index(value = ["telegramChannelId"])]
)
data class Playlist(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val coverUrl: String?,
    val telegramChannelId: Long?,
    val telegramInviteLink: String?,
    val isCollaborative: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

/** Ordered membership row of a track inside a playlist ([position] is 0-based). */
@Serializable
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackFileUniqueId"],
    indices = [Index(value = ["playlistId", "position"])]
)
data class PlaylistTrack(
    val playlistId: String,
    val trackFileUniqueId: String,
    val position: Int,
    val addedAt: Long
)

/** One playback event; drives stats (top tracks, completion counts). */
@Entity(tableName = "play_history")
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackFileUniqueId: String,
    val playedAt: Long,
    val progressMs: Int,
    val completed: Boolean
)
