package com.cesar.bocana.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cesar.bocana.data.model.DevolucionPendiente
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.data.model.Supplier
import com.cesar.bocana.data.model.TransferPdfConfig
import com.cesar.bocana.predictive.v3.data.PredictiveV3Snapshot
import com.cesar.bocana.predictive.v3.data.PredictiveV3SnapshotDao

@Database(
    entities = [
        StockMovement::class,
        Product::class,
        StockLot::class,
        Supplier::class,
        PendingPackagingTask::class,
        DevolucionPendiente::class,
        PredictiveV3Snapshot::class,
        TransferPdfConfig::class
    ],
    version = 11,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun stockMovementDao(): StockMovementDao
    abstract fun productDao(): ProductDao
    abstract fun stockLotDao(): StockLotDao
    abstract fun supplierDao(): SupplierDao
    abstract fun packagingDao(): PackagingDao
    abstract fun devolucionDao(): DevolucionDao
    abstract fun predictiveV3SnapshotDao(): PredictiveV3SnapshotDao
    abstract fun transferPdfConfigDao(): TransferPdfConfigDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Comprueba si una columna existe en una tabla de la base instalada.
         * Es necesario porque hubo instalaciones v8 con dos variantes reales
         * del esquema de products: unas ya tenían consumoSemanalPromedio y otras no.
         */
        private fun columnExists(
            database: SupportSQLiteDatabase,
            tableName: String,
            columnName: String
        ): Boolean {
            database.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false

                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        return true
                    }
                }
            }
            return false
        }

        /**
         * Migración 8 -> 9 del Consumo Predictivo V2.
         *
         * Se reconstruye SOLO la tabla products para garantizar que el esquema final
         * sea exactamente el que Room espera, sin borrar los productos existentes.
         * Esto cubre tanto bases v8 que ya tenían consumoSemanalPromedio como bases
         * v8 antiguas que todavía no tenían esa columna.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val oldHasConsumoSemanalPromedio = columnExists(
                    database = database,
                    tableName = "products",
                    columnName = "consumoSemanalPromedio"
                )

                database.execSQL("DROP TABLE IF EXISTS `products_new`")

                database.execSQL(
                    """
                    CREATE TABLE `products_new` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `unit` TEXT NOT NULL,
                        `minStock` REAL NOT NULL,
                        `stockIdealC04` REAL NOT NULL,
                        `requiresPackaging` INTEGER NOT NULL,
                        `ordenTraspaso` INTEGER NOT NULL,
                        `modoManualPDF` INTEGER NOT NULL,
                        `espacioExtraPDF` REAL NOT NULL,
                        `labelConfig` TEXT,
                        `categoria` TEXT NOT NULL,
                        `productoRectorId` TEXT,
                        `stockMatriz` REAL NOT NULL,
                        `stockCongelador04` REAL NOT NULL,
                        `totalStock` REAL NOT NULL,
                        `consumoSemanalPromedio` REAL NOT NULL,
                        `demandaSemanalPrevista` REAL NOT NULL DEFAULT 0.0,
                        `demandaSemanalAlta` REAL NOT NULL DEFAULT 0.0,
                        `demandaSemanalBaja` REAL NOT NULL DEFAULT 0.0,
                        `forecastPeriodKey` TEXT NOT NULL DEFAULT '',
                        `forecastModelVersion` INTEGER NOT NULL DEFAULT 0,
                        `forecastUsaEstacionalidad` INTEGER NOT NULL DEFAULT 0,
                        `isActive` INTEGER NOT NULL,
                        `createdAt` INTEGER,
                        `updatedAt` INTEGER,
                        `lastUpdatedByName` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                val consumoExpression = if (oldHasConsumoSemanalPromedio) {
                    "`consumoSemanalPromedio`"
                } else {
                    "0.0"
                }

                database.execSQL(
                    """
                    INSERT INTO `products_new` (
                        `id`,
                        `name`,
                        `unit`,
                        `minStock`,
                        `stockIdealC04`,
                        `requiresPackaging`,
                        `ordenTraspaso`,
                        `modoManualPDF`,
                        `espacioExtraPDF`,
                        `labelConfig`,
                        `categoria`,
                        `productoRectorId`,
                        `stockMatriz`,
                        `stockCongelador04`,
                        `totalStock`,
                        `consumoSemanalPromedio`,
                        `demandaSemanalPrevista`,
                        `demandaSemanalAlta`,
                        `demandaSemanalBaja`,
                        `forecastPeriodKey`,
                        `forecastModelVersion`,
                        `forecastUsaEstacionalidad`,
                        `isActive`,
                        `createdAt`,
                        `updatedAt`,
                        `lastUpdatedByName`
                    )
                    SELECT
                        `id`,
                        `name`,
                        `unit`,
                        `minStock`,
                        `stockIdealC04`,
                        `requiresPackaging`,
                        `ordenTraspaso`,
                        `modoManualPDF`,
                        `espacioExtraPDF`,
                        `labelConfig`,
                        `categoria`,
                        `productoRectorId`,
                        `stockMatriz`,
                        `stockCongelador04`,
                        `totalStock`,
                        $consumoExpression,
                        0.0,
                        0.0,
                        0.0,
                        '',
                        0,
                        0,
                        `isActive`,
                        `createdAt`,
                        `updatedAt`,
                        `lastUpdatedByName`
                    FROM `products`
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE `products`")
                database.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_products_name` ON `products` (`name`)"
                )
            }
        }

        /** Migración 9 -> 10: añade sólo la caché persistente V3. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `predictive_v3_snapshots` (
                        `productId` TEXT NOT NULL,
                        `coverageDays` INTEGER,
                        `probableMinDays` INTEGER,
                        `probableMaxDays` INTEGER,
                        `forecastWeeklyKg` REAL NOT NULL,
                        `baselineWeeklyKg` REAL NOT NULL,
                        `highScenarioWeeklyKg` REAL NOT NULL,
                        `lowScenarioWeeklyKg` REAL NOT NULL,
                        `primaryTotalStockKg` REAL NOT NULL,
                        `c04CurrentKg` REAL NOT NULL,
                        `dynamicC04TargetKg` REAL NOT NULL,
                        `suggestedTransferKg` REAL NOT NULL,
                        `regime` TEXT NOT NULL,
                        `groupId` TEXT,
                        `groupName` TEXT,
                        `calculatedAtMillis` INTEGER NOT NULL,
                        `dayKey` INTEGER NOT NULL,
                        `productUpdatedAtMillis` INTEGER NOT NULL,
                        `stockMatrizBits` INTEGER NOT NULL,
                        `stockC04Bits` INTEGER NOT NULL,
                        `totalStockBits` INTEGER NOT NULL,
                        `latestMovementMillis` INTEGER NOT NULL,
                        `packagingFingerprint` INTEGER NOT NULL,
                        `returnsFingerprint` INTEGER NOT NULL,
                        `configFingerprint` INTEGER NOT NULL,
                        `engineRevision` INTEGER NOT NULL,
                        PRIMARY KEY(`productId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** Migración 10 -> 11: caché local de la configuración global del PDF. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfer_pdf_config` (
                        `id` TEXT NOT NULL,
                        `titleText` TEXT NOT NULL,
                        `headerBackgroundHex` TEXT NOT NULL,
                        `headerTextHex` TEXT NOT NULL,
                        `zebraHex` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bocana_database"
                )
                    .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
