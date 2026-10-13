package com.extrumz.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.extrumz.app.data.db.entity.PendingOperation
import kotlinx.coroutines.flow.Flow

/** Persistence for the offline operation queue. */
@Dao
interface PendingOperationDao {

    @Insert
    suspend fun insert(operation: PendingOperation)

    /** Oldest-first batch to process while online. */
    @Query("SELECT * FROM pending_operations ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<PendingOperation>

    @Query("DELETE FROM pending_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM pending_operations")
    fun observeCount(): Flow<Int>
}
