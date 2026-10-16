package com.extrumz.app.data.db.entity

import androidx.room.ColumnInfo
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
    indices = [
        Index(value = ["isTracked"]),
        Index(value = ["title"]),
        // Declared because the 4→5 migration creates it: Room validates
        // indexes as strictly as columns, and an index the entity does not
        // name fails the whole migration with "Migration didn't properly
        // handle: chats". Also the natural order for anything sorting the
        // library by when a chat was first found.
        Index(value = ["discoveredAt"]),
    ]
)
data class ChatSource(
    @PrimaryKey val chatId: Long,
    val title: String,
    val username: String?,
    val photoUrl: String?,
    val type: ChatType,
    val isTracked: Boolean = true,
    val lastScannedMessageId: Long = 0L,
    val trackCount: Int = 0,
    /**
     * When this chat was FIRST seen by discovery. Immutable for the life of
     * the row — the whole "once found, never dropped" rule hangs off it: a
     * chat that made it into the library once keeps that fact forever, even
     * if a later scan's chat lists come back without it.
     */
    @ColumnInfo(defaultValue = "0")
    val discoveredAt: Long = 0L,
    /**
     * Last time a discovery pass saw this chat, so a row that vanished from
     * TDLib's lists is measurable ("last seen 3 days ago") instead of a row
     * whose age is unknowable.
     */
    @ColumnInfo(defaultValue = "0")
    val lastSeenAt: Long = 0L
)
