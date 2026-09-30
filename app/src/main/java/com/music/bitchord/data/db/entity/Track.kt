package com.music.bitchord.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Indexed Telegram audio message.
 *
 * Metadata columns are non-null with empty defaults so UI/merging logic never
 * deals with nulls; unknown values are represented as `""` / `0`.
 */
@Serializable
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["fileUniqueId"], unique = true),
        Index(value = ["fileId"]),
        Index(value = ["performer"]),
        Index(value = ["title"]),
        Index(value = ["album"]),
        Index(value = ["sourceChatId"]),
        Index(value = ["mediaAlbumId"]),
        Index(value = ["dateAdded"]),
        Index(value = ["sourceChatId", "dateAdded"]),
        Index(value = ["performer", "album", "trackNumber"]),
        Index(value = ["isFavorite", "dateAdded"])
    ]
)
data class Track(
    @ColumnInfo(name = "id")
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fileUniqueId: String,
    val fileId: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val performer: String = "",
    @ColumnInfo(collate = ColumnInfo.NOCASE)
    val title: String = "",
    val duration: Int = 0,
    val album: String = "",
    val trackNumber: Int = 0,
    val genre: String = "",
    val year: Int = 0,
    val coverUrl: String = "",
    val lyrics: String = "",
    val dateAdded: Long = 0L,
    val sourceChatId: Long = 0L,
    val sourceMessageId: Long = 0L,
    val mediaAlbumId: Long? = null,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long? = null,
    val tags: String = "",
    val fileSize: Long = 0L,
    val mimeType: String = "audio/mpeg",
    val caption: String = ""
)
