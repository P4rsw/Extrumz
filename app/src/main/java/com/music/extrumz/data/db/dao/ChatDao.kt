package com.extrumz.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.extrumz.app.data.db.entity.ChatSource
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats WHERE isTracked = 1 ORDER BY title COLLATE NOCASE")
    fun observeTracked(): Flow<List<ChatSource>>

    @Query("SELECT * FROM chats WHERE chatId = :chatId")
    suspend fun getById(chatId: Long): ChatSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatSource)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chats: List<ChatSource>)

    @Query(
        """
        UPDATE chats SET
            lastScannedMessageId = CASE
                WHEN :messageId > 0 THEN MAX(lastScannedMessageId, :messageId)
                WHEN :messageId = -1 AND lastScannedMessageId <= 0 THEN -1
                ELSE lastScannedMessageId
            END,
            trackCount = CASE
                WHEN :messageId > 0 THEN :trackCount
                WHEN :messageId = -1 AND lastScannedMessageId <= 0 THEN 0
                ELSE trackCount
            END
        WHERE chatId = :chatId
        """
    )
    suspend fun updateScanProgress(chatId: Long, messageId: Long, trackCount: Int)

    @Query("SELECT * FROM chats WHERE isTracked = 1")
    suspend fun getTrackedOnce(): List<ChatSource>

    /**
     * Records what a discovery pass just saw, WITHOUT letting the pass decide
     * when a chat was first found.
     *
     * Room's REPLACE upsert would clobber `discoveredAt` with the entity
     * default, so an already-known chat would look newly discovered on every
     * scan and the row would lose the one field the "never dropped" rule
     * reads. This keeps the original stamp, refreshes `lastSeenAt`, and lets
     * the pass contribute only what it actually knows: title, avatar, type.
     *
     * A chat the user explicitly untracked stays untracked — discovery
     * reports existence, not consent.
     */
    @Transaction
    suspend fun ingestDiscovered(chats: List<ChatSource>, now: Long): Int {
        if (chats.isEmpty()) return 0
        var written = 0
        val unchangedIds = ArrayList<Long>(chats.size)
        for (chat in chats) {
            val existing = getById(chat.chatId)
            if (existing == null) {
                upsert(chat.copy(discoveredAt = now, lastSeenAt = now))
                written++
                continue
            }
            // An unchanged row must NOT be rewritten: `lastSeenAt` alone would
            // turn every scan into one write per chat, and the upsert is what
            // clobbered a downloaded HQ avatar with the list's inline
            // thumbnail on every rescan. Discovery reports identity — title,
            // handle, kind — and the avatar belongs to F8's refresh path,
            // which only ever fills an empty slot or replaces it deliberately.
            val avatar = if (existing.photoUrl.isNullOrBlank()) {
                chat.photoUrl ?: existing.photoUrl
            } else {
                existing.photoUrl
            }
            val changed = existing.title != chat.title ||
                existing.username != chat.username ||
                existing.type != chat.type ||
                avatar != existing.photoUrl
            if (changed) {
                upsert(
                    existing.copy(
                        title = chat.title,
                        username = chat.username,
                        type = chat.type,
                        photoUrl = avatar,
                        lastSeenAt = now,
                    ),
                )
                written++
            } else {
                unchangedIds += chat.chatId
            }
        }
        // Only the rows NOT already written need the stamp — the ones upserted
        // above carry `now` already, and re-stating them would be a second
        // write to the same row inside one transaction. Chunked because
        // SQLite's parameter limit is 999 and this account lists over a
        // thousand chats: one giant IN() throws
        // "too many SQL variables" on older Android.
        unchangedIds.chunked(SQLITE_PARAM_CHUNK).forEach { chunk ->
            touchSeen(chunk, now)
        }
        return written
    }

    @Query("UPDATE chats SET lastSeenAt = :now WHERE chatId IN (:ids)")
    suspend fun touchSeen(ids: List<Long>, now: Long)

    /**
     * F8 diagnosis: EVERY row, tracked or not. The untracked half is invisible
     * to getTrackedOnce, yet it is exactly where a wrongly-untracked channel
     * with history sits — counting orphans needs it.
     */
    @Query("SELECT * FROM chats")
    suspend fun getAllOnce(): List<ChatSource>

    @Query(
        "UPDATE chats SET trackCount = (SELECT COUNT(*) FROM track_sources WHERE chatId = :chatId) " +
            "WHERE chatId = :chatId"
    )
    suspend fun refreshTrackCount(chatId: Long)

    @Query("UPDATE chats SET isTracked = :tracked WHERE chatId = :chatId")
    suspend fun setTracked(chatId: Long, tracked: Boolean)

    /**
     * F8: fill a chat avatar that has none — rows scanned before photoUrl
     * existed, or photos whose inline bytes only land later. Never overwrites
     * an avatar already on the row.
     */
    @Query(
        "UPDATE chats SET photoUrl = :photoUrl " +
            "WHERE chatId = :chatId AND (photoUrl IS NULL OR photoUrl = '')"
    )
    suspend fun setPhotoUrlIfEmpty(chatId: Long, photoUrl: String)

    /**
     * F8 quality upgrade: the full-size photo replaces the inline minithumbnail
     * the shelf card is built from; inequality-guarded so an unchanged URL
     * emits no Flow event.
     */
    @Query(
        "UPDATE chats SET photoUrl = :photoUrl " +
            "WHERE chatId = :chatId AND IFNULL(photoUrl, '') != :photoUrl"
    )
    suspend fun setPhotoUrl(chatId: Long, photoUrl: String)

    // Only chats that actually hold music: every dialog gets inserted at 0
    // before (or without) a scan finding anything, and a list of zeroes is
    // noise in both the Library shelf and the picker (user report, 10-03).
    @Query("SELECT * FROM chats WHERE trackCount > 0 ORDER BY trackCount DESC")
    fun observeAllByTrackCount(): Flow<List<ChatSource>>

    /**
     * Every row, music or not — for the picker's zero-filter toggle (user
     * report, 10-10). The rows with zero songs are KEPT here and hidden by
     * the view instead: dropping them from the DB is what broke scans
     * (the "Chat not found" → untrack cascade of 10-07/10-10), so a view
     * preference must never delete data again.
     */
    @Query("SELECT * FROM chats ORDER BY trackCount DESC")
    fun observeAllChats(): Flow<List<ChatSource>>
}

/** SQLite's `SQLITE_MAX_VARIABLE_NUMBER` floor (999) with margin. */
private const val SQLITE_PARAM_CHUNK = 500
