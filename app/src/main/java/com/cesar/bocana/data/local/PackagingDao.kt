package com.cesar.bocana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cesar.bocana.data.model.PendingPackagingTask
import kotlinx.coroutines.flow.Flow

@Dao
interface PackagingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<PendingPackagingTask>)

    @Query("SELECT * FROM pending_packaging ORDER BY receivedAt ASC")
    fun getAllPackagingTasksStream(): Flow<List<PendingPackagingTask>>

    @Query("DELETE FROM pending_packaging")
    suspend fun clearAll()
}