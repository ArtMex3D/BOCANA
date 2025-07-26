package com.cesar.bocana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cesar.bocana.data.model.Supplier
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(suppliers: List<Supplier>)

    @Query("DELETE FROM suppliers")
    suspend fun clearAll()

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun getAllSuppliersStream(): Flow<List<Supplier>>
}
