package com.extrumz.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Where one song physically lives on Telegram.
 *
 * A song is not one row: the same file gets reposted across channels, and the
 * library keeps ONE track row per song (F7) while this table records every
 * place it was seen. Collapsing duplicates used to DELETE the loser row, which
 * deleted the chat association with it — that is how a song found in a bot chat
 * vanished from that chat's filter while still showing in the main list.
 *
 * (chatId, messageId) is per-chat, not global: message ids are a counter
 * within each chat, so the pair is the only way to say "this song, in THAT
 * channel's feed, at THAT position". It is also what the playback fallback in
 * TelegramSource asks for when the cached fileId has gone stale, and what the
 * channel view orders by.
 *
 * PRIMARY KEY rather than a single auto id: the same chat can only contribute
 * one row per song per message, and the scanner runs six chats in parallel, so
 * ON CONFLICT IGNORE must have something to collide on.
 */
@Entity(
    tableName = "track_sources",
    primaryKeys = ["trackFileUniqueId", "chatId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["fileUniqueId"],
            childColumns = ["trackFileUniqueId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // The channel view: every source row for one chat, newest first.
        Index(value = ["chatId", "messageId"]),
        // Reverse lookup: everything that points at one keeper.
        Index(value = ["trackFileUniqueId"]),
    ],
)
data class TrackSource(
    /** The keeper row in `tracks` this occurrence was folded into. */
    val trackFileUniqueId: String,
    /** Chat the file was actually posted in. */
    val chatId: Long,
    /** Message id WITHIN [chatId] — what orders the channel feed. */
    val messageId: Long,
    /** Telegram file id as posted here; may differ from the keeper's copy. */
    val fileId: String = "",
    /** When this occurrence was first recorded, for stable ordering of ties. */
    val dateAdded: Long = 0L,
)
