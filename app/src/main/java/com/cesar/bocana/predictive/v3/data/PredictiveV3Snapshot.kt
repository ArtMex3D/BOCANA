package com.cesar.bocana.predictive.v3.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Fotografía pequeña y persistente del último resultado V3 válido.
 * No duplica el historial de Firestore: guarda sólo el resultado derivado.
 */
@Entity(tableName = "predictive_v3_snapshots")
data class PredictiveV3Snapshot(
    @PrimaryKey val productId: String,
    val coverageDays: Int?,
    val probableMinDays: Int?,
    val probableMaxDays: Int?,
    val forecastWeeklyKg: Double,
    val baselineWeeklyKg: Double,
    val highScenarioWeeklyKg: Double,
    val lowScenarioWeeklyKg: Double,
    val primaryTotalStockKg: Double,
    val c04CurrentKg: Double,
    val dynamicC04TargetKg: Double,
    val suggestedTransferKg: Double,
    val regime: String,
    val groupId: String?,
    val groupName: String?,
    val calculatedAtMillis: Long,
    val dayKey: Int,
    val productUpdatedAtMillis: Long,
    val stockMatrizBits: Long,
    val stockC04Bits: Long,
    val totalStockBits: Long,
    val latestMovementMillis: Long,
    val packagingFingerprint: Int,
    val returnsFingerprint: Int,
    val configFingerprint: Int,
    val engineRevision: Int
)

@Dao
interface PredictiveV3SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: PredictiveV3Snapshot)

    @Query("SELECT * FROM predictive_v3_snapshots WHERE productId = :productId LIMIT 1")
    suspend fun getByProductId(productId: String): PredictiveV3Snapshot?

    @Query("SELECT * FROM predictive_v3_snapshots")
    suspend fun getAllOnce(): List<PredictiveV3Snapshot>

    @Query("SELECT * FROM predictive_v3_snapshots")
    fun observeAll(): Flow<List<PredictiveV3Snapshot>>

    @Query("DELETE FROM predictive_v3_snapshots WHERE productId = :productId")
    suspend fun deleteByProductId(productId: String)

    @Query("DELETE FROM predictive_v3_snapshots")
    suspend fun clearAll()
}
