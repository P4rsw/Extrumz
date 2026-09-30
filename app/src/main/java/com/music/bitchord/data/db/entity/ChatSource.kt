package com.music.bitchord.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Kind of Telegram chat a [ChatSource] refers to. */
enum class ChatType {
    PRIVATE, GROUP, CHANNEL, SUPERGROUP, UNKNOWN
}

/**
 * Scanned Telegram chat with incremental scan cursor
 * ([lastScannedMessageId]) and indexed track count.
 */
@Entity(
    tableName = "chats",
    indices = [Index(value = ["isTracked"]), Index(value = ["title"])]
)
data class ChatSource(
    @PrimaryKey val chatId: Long,
    val title: String,
    val username: String?,
    val photoUrl: String?,
    val type: ChatType,
    val isTracked: Boolean = true,
    val lastScannedMessageId: Long = 0L,
    val trackCount: Int = 0
)
