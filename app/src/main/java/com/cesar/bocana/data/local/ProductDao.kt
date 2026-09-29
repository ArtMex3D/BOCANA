package com.cesar.bocana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cesar.bocana.data.model.Product
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<Product>)

    @Query("DELETE FROM products")
    suspend fun clearAll()

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY name ASC")
    fun getAllActiveProductsStream(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY ordenTraspaso ASC, name ASC")
    fun getActiveProductsByTransferOrderStream(): Flow<List<Product>>

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY ordenTraspaso ASC, name ASC")
    suspend fun getAllActiveProductsOnce(): List<Product>

    @Query("SELECT * FROM products WHERE id = :productId LIMIT 1")
    suspend fun getProductByIdOnce(productId: String): Product?

    @Query("UPDATE products SET ordenTraspaso = :orderIndex WHERE id = :productId")
    suspend fun updateTransferOrder(productId: String, orderIndex: Int)

    @Query("UPDATE products SET modoManualPDF = :enabled WHERE id = :productId")
    suspend fun updatePdfManualMode(productId: String, enabled: Boolean)

    @Query("UPDATE products SET stockIdealC04 = :stockIdealKg WHERE id = :productId")
    suspend fun updateStockIdealC04(productId: String, stockIdealKg: Double)

    @Query("UPDATE products SET espacioExtraPDF = :extraSpace WHERE id = :productId")
    suspend fun updatePdfExtraSpace(productId: String, extraSpace: Double)

    @Query("SELECT * FROM products WHERE isActive = 0 ORDER BY name ASC")
    fun getAllArchivedProductsStream(): Flow<List<Product>>

    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProductsStream(): Flow<List<Product>>

    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteById(productId: String)
}
