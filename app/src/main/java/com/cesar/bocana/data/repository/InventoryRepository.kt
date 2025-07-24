package com.cesar.bocana.data

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.data.model.Supplier
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.toObjects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

class InventoryRepository(
    private val db: AppDatabase,
    private val firestore: FirebaseFirestore
) {
    private val productDao = db.productDao()
    private val stockLotDao = db.stockLotDao()
    private val stockMovementDao = db.stockMovementDao()
    private val supplierDao = db.supplierDao()

    suspend fun syncNewData() {
        withContext(Dispatchers.IO) {
            try {
                val lastSyncTimestamp = stockMovementDao.getLatestTimestamp() ?: 0L
                val lastSyncDate = Date(lastSyncTimestamp)
                Log.d("InventoryRepository", "Última sincronización: $lastSyncDate")

                val newMovements = firestore.collection("stockMovements")
                    .whereGreaterThan("timestamp", lastSyncDate)
                    .get().await().toObjects<StockMovement>()

                if (newMovements.isNotEmpty()) {
                    Log.d("InventoryRepository", "Descargando ${newMovements.size} movimientos nuevos.")
                    db.withTransaction {
                        stockMovementDao.insertAll(newMovements)

                        val products = firestore.collection("products").get().await().toObjects<Product>()
                        productDao.insertAll(products)

                        val stockLots = firestore.collection("inventoryLots").get().await().toObjects<StockLot>()
                        stockLotDao.insertAll(stockLots)
                    }
                } else {
                    Log.d("InventoryRepository", "No hay movimientos nuevos para sincronizar.")
                }
            } catch (e: Exception) {
                Log.e("InventoryRepository", "Error en syncNewData", e)
                throw e
            }
        }
    }

    suspend fun getLotById(lotId: String): StockLot? = stockLotDao.getLotById(lotId)

    // Nueva función para buscar por un rango de tiempo exacto (en milisegundos)
    suspend fun getLotIdsByTimestampRange(startTime: Long, endTime: Long): List<String> {
        return stockLotDao.getLotIdsByDateRange(startTime, endTime)
    }

    fun getFilteredMovementsPaged(
        productId: String?,
        startDate: Date?,
        endDate: Date?,
        movementTypes: List<String>?,
        userName: String?,
        supplierId: String?,
        freeText: String?,
        lotIds: List<String>?
    ): Flow<PagingData<StockMovement>> {

        val queryBuilder = StringBuilder("SELECT * FROM stock_movements WHERE 1=1 ")
        val args = mutableListOf<Any?>()

        startDate?.let {
            queryBuilder.append("AND timestamp >= ? ")
            args.add(it.time)
        }
        endDate?.let {
            queryBuilder.append("AND timestamp <= ? ")
            args.add(it.time)
        }
        if (!movementTypes.isNullOrEmpty()) {
            queryBuilder.append("AND type IN (${movementTypes.joinToString { "?" }}) ")
            args.addAll(movementTypes)
        }
        if (!freeText.isNullOrBlank()) {
            val text = "%$freeText%"
            queryBuilder.append("AND (reason LIKE ? OR productName LIKE ? OR userName LIKE ? OR affectedLotIds LIKE ?) ")
            args.addAll(listOf(text, text, text, text))
        }

        if (!lotIds.isNullOrEmpty()) {
            queryBuilder.append("AND (")
            lotIds.forEachIndexed { index, id ->
                if (index > 0) queryBuilder.append(" OR ")
                queryBuilder.append("affectedLotIds LIKE ?")
                args.add("%\"$id\"%")
            }
            queryBuilder.append(") ")
        }

        queryBuilder.append("ORDER BY timestamp DESC")

        val query = SimpleSQLiteQuery(queryBuilder.toString(), args.toTypedArray())

        return Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = false),
            pagingSourceFactory = { stockMovementDao.getFilteredMovementsPaged(query) }
        ).flow
    }
}