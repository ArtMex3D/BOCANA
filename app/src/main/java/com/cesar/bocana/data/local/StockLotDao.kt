package com.cesar.bocana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cesar.bocana.data.model.StockLot
import kotlinx.coroutines.flow.Flow

@Dao
interface StockLotDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lots: List<StockLot>)

    @Query("DELETE FROM stock_lots")
    suspend fun clearAll()

    @Query("SELECT * FROM stock_lots WHERE id = :lotId LIMIT 1")
    suspend fun getLotById(lotId: String): StockLot?

    @Query("SELECT * FROM stock_lots WHERE productId = :productId AND location = :location AND isDepleted = 0 ORDER BY receivedAt ASC")
    fun getActiveLotsStream(productId: String, location: String): Flow<List<StockLot>>

    @Query("SELECT id FROM stock_lots WHERE receivedAt >= :startOfDay AND receivedAt <= :endOfDay")
    suspend fun getLotIdsByDateRange(startOfDay: Long, endOfDay: Long): List<String>
}
