package com.cesar.bocana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cesar.bocana.data.model.Supplier

@Dao
interface SupplierDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(suppliers: List<Supplier>)

    @Query("DELETE FROM suppliers")
    suspend fun clearAll()

    @Query("SELECT * FROM suppliers")
    suspend fun getAllSuppliers(): List<Supplier>

    @Query("SELECT * FROM suppliers WHERE name LIKE '%' || :query || '%'")
    suspend fun findSuppliersByName(query: String): List<Supplier>
}