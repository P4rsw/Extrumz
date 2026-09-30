package com.music.bitchord.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.music.bitchord.data.db.entity.ChatSource
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

    @Query(
        "UPDATE chats SET trackCount = (SELECT COUNT(*) FROM tracks WHERE sourceChatId = :chatId) " +
            "WHERE chatId = :chatId"
    )
    suspend fun refreshTrackCount(chatId: Long)

    @Query("UPDATE chats SET isTracked = :tracked WHERE chatId = :chatId")
    suspend fun setTracked(chatId: Long, tracked: Boolean)

    @Query("SELECT * FROM chats ORDER BY trackCount DESC")
    fun observeAllByTrackCount(): Flow<List<ChatSource>>
}
