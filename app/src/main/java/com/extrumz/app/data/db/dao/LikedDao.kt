package com.extrumz.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.extrumz.app.data.db.entity.LikedVideo
import kotlinx.coroutines.flow.Flow

@Dao
interface LikedDao {

    @Query("SELECT * FROM liked_videos ORDER BY likedAt DESC")
    fun observeAll(): Flow<List<LikedVideo>>

    @Query("SELECT videoId FROM liked_videos")
    suspend fun ids(): List<String>

    /** REPLACE, so re-liking moves the row rather than failing on the key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LikedVideo)

    @Query("DELETE FROM liked_videos WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM liked_videos")
    suspend fun clear()
}
