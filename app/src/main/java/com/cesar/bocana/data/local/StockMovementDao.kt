package com.cesar.bocana.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.cesar.bocana.data.model.StockMovement

@Dao
interface StockMovementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movements: List<StockMovement>)

    @Query("DELETE FROM stock_movements")
    suspend fun clearAll()

    @Query("SELECT MAX(timestamp) FROM stock_movements")
    suspend fun getLatestTimestamp(): Long?

    @Query("SELECT DISTINCT userName FROM stock_movements WHERE userName IS NOT NULL")
    suspend fun getAllUserNames(): List<String>

    @Query("SELECT * FROM stock_movements ORDER BY timestamp ASC")
    suspend fun getAllMovementsOnce(): List<StockMovement>

    @Query("SELECT * FROM stock_movements WHERE productId = :productId ORDER BY timestamp ASC")
    suspend fun getMovementsForProductOnce(productId: String): List<StockMovement>

    @Query("SELECT * FROM stock_movements WHERE timestamp >= :startMillis AND timestamp < :endMillis ORDER BY timestamp ASC")
    suspend fun getMovementsBetweenOnce(startMillis: Long, endMillis: Long): List<StockMovement>

    // Usaremos RawQuery para ejecutar una consulta construida dinámicamente
    @RawQuery(observedEntities = [StockMovement::class])
    fun getFilteredMovementsPaged(query: SupportSQLiteQuery): PagingSource<Int, StockMovement>
}
