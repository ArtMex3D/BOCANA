package com.cesar.bocana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cesar.bocana.data.model.DevolucionPendiente
import kotlinx.coroutines.flow.Flow

@Dao
interface DevolucionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devoluciones: List<DevolucionPendiente>)

    @Query("SELECT * FROM pending_devoluciones ORDER BY status DESC, registeredAt DESC")
    fun getAllDevolucionesStream(): Flow<List<DevolucionPendiente>>

    @Query("DELETE FROM pending_devoluciones")
    suspend fun clearAll()
}