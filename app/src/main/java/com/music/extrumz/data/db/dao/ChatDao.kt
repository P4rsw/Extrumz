package com.extrumz.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
        "UPDATE chats SET lastScannedMessageId = :messageId, trackCount = :trackCount " +
            "WHERE chatId = :chatId"
    )
    suspend fun updateScanProgress(chatId: Long, messageId: Long, trackCount: Int)

    @Query("SELECT * FROM chats WHERE isTracked = 1")
    suspend fun getTrackedOnce(): List<ChatSource>

    /**
     * F8 diagnosis: EVERY row, tracked or not. The untracked half is invisible
     * to getTrackedOnce, yet it is exactly where a wrongly-untracked channel
     * with history sits — counting orphans needs it.
     */
    @Query("SELECT * FROM chats")
    suspend fun getAllOnce(): List<ChatSource>

    @Query(
        "UPDATE chats SET trackCount = (SELECT COUNT(*) FROM tracks WHERE sourceChatId = :chatId) " +
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
