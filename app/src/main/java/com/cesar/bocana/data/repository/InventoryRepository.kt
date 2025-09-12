package com.cesar.bocana.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.DevolucionPendiente
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.data.model.Supplier
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.toObjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

class InventoryRepository(
    private val db: AppDatabase,
    private val firestore: FirebaseFirestore
) {
    private val productDao = db.productDao()
    private val stockLotDao = db.stockLotDao()
    private val stockMovementDao = db.stockMovementDao()
    private val supplierDao = db.supplierDao()
    private val packagingDao = db.packagingDao()
    private val devolucionDao = db.devolucionDao()

    private var productsListener: ListenerRegistration? = null
    private var suppliersListener: ListenerRegistration? = null
    private var stockLotsListener: ListenerRegistration? = null
    private var packagingListener: ListenerRegistration? = null
    private var devolucionesListener: ListenerRegistration? = null

    fun getActiveProductsStream(): Flow<List<Product>> = productDao.getAllActiveProductsStream()
    fun getArchivedProductsStream(): Flow<List<Product>> = productDao.getAllArchivedProductsStream()
    fun getAllProductsStream(): Flow<List<Product>> = productDao.getAllProductsStream()
    fun getSuppliersStream(): Flow<List<Supplier>> = supplierDao.getAllSuppliersStream()
    fun getPackagingTasksStream(): Flow<List<PendingPackagingTask>> = packagingDao.getAllPackagingTasksStream()
    fun getDevolucionesStream(): Flow<List<DevolucionPendiente>> = devolucionDao.getAllDevolucionesStream()

    fun startFirestoreListeners() {
        Log.d("InventoryRepository", "Iniciando listeners de Firestore...")

        stopFirestoreListeners()

        productsListener = firestore.collection("products")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) {
                    Log.e("InventoryRepository", "Error en listener de productos", error)
                    return@addSnapshotListener
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val firestoreProducts = snapshots.toObjects(Product::class.java)
                    productDao.insertAll(firestoreProducts)
                }
            }

        suppliersListener = firestore.collection("suppliers")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) {
                    Log.e("InventoryRepository", "Error en listener de proveedores", error)
                    return@addSnapshotListener
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val firestoreSuppliers = snapshots.toObjects(Supplier::class.java)
                    supplierDao.insertAll(firestoreSuppliers)
                }
            }

        stockLotsListener = firestore.collection("inventoryLots")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) {
                    Log.e("InventoryRepository", "Error en listener de lotes", error)
                    return@addSnapshotListener
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val firestoreLots = snapshots.toObjects(StockLot::class.java)
                    stockLotDao.insertAll(firestoreLots)
                }
            }

        packagingListener = firestore.collection("pendingPackaging")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) {
                    Log.e("InventoryRepository", "Error en listener de empaque", error)
                    return@addSnapshotListener
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val firestoreTasks = snapshots.toObjects(PendingPackagingTask::class.java)
                    packagingDao.clearAll()
                    packagingDao.insertAll(firestoreTasks)
                }
            }

        devolucionesListener = firestore.collection("pendingDevoluciones")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) {
                    Log.e("InventoryRepository", "Error en listener de devoluciones", error)
                    return@addSnapshotListener
                }
                CoroutineScope(Dispatchers.IO).launch {
                    val firestoreDevoluciones = snapshots.toObjects(DevolucionPendiente::class.java)
                    devolucionDao.clearAll()
                    devolucionDao.insertAll(firestoreDevoluciones)
                }
            }
    }

    fun stopFirestoreListeners() {
        Log.d("InventoryRepository", "Deteniendo listeners de Firestore.")
        productsListener?.remove()
        suppliersListener?.remove()
        stockLotsListener?.remove()
        packagingListener?.remove()
        devolucionesListener?.remove()
    }

    suspend fun syncNewMovements() {
        withContext(Dispatchers.IO) {
            try {
                val lastSyncTimestamp = stockMovementDao.getLatestTimestamp() ?: 0L
                val lastSyncDate = Date(lastSyncTimestamp)

                val newMovements = firestore.collection("stockMovements")
                    .whereGreaterThan("timestamp", lastSyncDate)
                    .get().await().toObjects(StockMovement::class.java)

                if (newMovements.isNotEmpty()) {
                    Log.d("InventoryRepository", "Descargando ${newMovements.size} movimientos nuevos.")
                    db.withTransaction {
                        stockMovementDao.insertAll(newMovements)
                    }
                } else {
                    Log.d("InventoryRepository", "No hay movimientos nuevos para sincronizar.")
                }
            } catch (e: Exception) {
                Log.e("InventoryRepository", "Error en syncNewMovements", e)
                throw e
            }
        }
    }

    /**
     * Borra toda la base de datos local y la vuelve a llenar desde Firestore.
     * Soluciona el error de compilación.
     */
    suspend fun forceFullResync() {
        withContext(Dispatchers.IO) {
            try {
                Log.d("InventoryRepository", "Iniciando Sincronización Forzada...")
                // 1. Borrar todos los datos locales en una única transacción
                db.withTransaction {
                    productDao.clearAll()
                    supplierDao.clearAll()
                    stockLotDao.clearAll()
                    stockMovementDao.clearAll()
                    packagingDao.clearAll()
                    devolucionDao.clearAll()
                }
                Log.d("InventoryRepository", "Base de datos local borrada.")

                // 2. Descargar todos los datos de Firestore de nuevo
                val products = firestore.collection("products").get().await().toObjects(Product::class.java)
                productDao.insertAll(products)
                Log.d("InventoryRepository", "${products.size} productos descargados.")

                val suppliers = firestore.collection("suppliers").get().await().toObjects(Supplier::class.java)
                supplierDao.insertAll(suppliers)
                Log.d("InventoryRepository", "${suppliers.size} proveedores descargados.")

                val stockLots = firestore.collection("inventoryLots").get().await().toObjects(StockLot::class.java)
                stockLotDao.insertAll(stockLots)
                Log.d("InventoryRepository", "${stockLots.size} lotes descargados.")

                val movements = firestore.collection("stockMovements").get().await().toObjects(StockMovement::class.java)
                stockMovementDao.insertAll(movements)
                Log.d("InventoryRepository", "${movements.size} movimientos descargados.")

                val packagingTasks = firestore.collection("pendingPackaging").get().await().toObjects(PendingPackagingTask::class.java)
                packagingDao.insertAll(packagingTasks)
                Log.d("InventoryRepository", "${packagingTasks.size} tareas de empaque descargadas.")

                val devoluciones = firestore.collection("pendingDevoluciones").get().await().toObjects(DevolucionPendiente::class.java)
                devolucionDao.insertAll(devoluciones)
                Log.d("InventoryRepository", "${devoluciones.size} devoluciones descargadas.")

                Log.d("InventoryRepository", "Sincronización Forzada completada.")
            } catch (e: Exception) {
                Log.e("InventoryRepository", "Error durante la Sincronización Forzada", e)
                throw e
            }
        }
    }

    suspend fun getLotById(lotId: String): StockLot? = withContext(Dispatchers.IO) {
        stockLotDao.getLotById(lotId)
    }

    suspend fun getLotIdsByTimestampRange(startTime: Long, endTime: Long): List<String> = withContext(Dispatchers.IO) {
        stockLotDao.getLotIdsByDateRange(startTime, endTime)
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

