package com.cesar.bocana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cesar.bocana.data.model.StockMovement
import java.util.Date

@Dao
interface StockMovementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movements: List<StockMovement>)

    @Query("SELECT * FROM stock_movements ORDER BY timestamp DESC")
    suspend fun getAllMovements(): List<StockMovement>

    @Query("SELECT MAX(timestamp) FROM stock_movements")
    suspend fun getLatestTimestamp(): Long? // Room guarda Date como Long (milisegundos)

    @Query("DELETE FROM stock_movements")
    suspend fun clearAll()
}