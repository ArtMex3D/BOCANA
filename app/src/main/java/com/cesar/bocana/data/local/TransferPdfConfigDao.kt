package com.cesar.bocana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cesar.bocana.data.model.TransferPdfConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferPdfConfigDao {

    @Query("SELECT * FROM transfer_pdf_config WHERE id = 'transfer_pdf' LIMIT 1")
    fun observe(): Flow<TransferPdfConfig?>

    @Query("SELECT * FROM transfer_pdf_config WHERE id = 'transfer_pdf' LIMIT 1")
    suspend fun getOnce(): TransferPdfConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: TransferPdfConfig)
}
