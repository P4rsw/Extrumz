package com.music.bitchord.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Operation targeting Telegram that failed while offline.
 * Enqueued by services and drained once the connection is READY.
 */
@Serializable
@Entity(tableName = "pending_operations")
data class PendingOperation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** Operation discriminator, e.g. [TYPE_ADD_TO_PLAYLIST]. */
    val type: String,
    /** JSON-encoded payload for the operation. */
    val payload: String,
    val createdAt: Long
) {
    companion object {
        const val TYPE_ADD_TO_PLAYLIST = "ADD_TO_PLAYLIST"
    }
}

/** Payload for [PendingOperation.TYPE_ADD_TO_PLAYLIST]. */
@Serializable
data class AddToPlaylistPayload(
    val playlistChatId: Long,
    val chatId: Long,
    val messageId: Long
)
