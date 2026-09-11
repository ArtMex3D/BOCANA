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

@Database(
    entities = [
        StockMovement::class,
        Product::class,
        StockLot::class,
        Supplier::class,
        PendingPackagingTask::class,
        DevolucionPendiente::class
    ],
    version = 9,
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

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * MigraciÃ³n dedicada EXCLUSIVAMENTE al Consumo Predictivo V2.
         * No borra tablas ni cambia otra lÃ³gica del inventario.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `demandaSemanalPrevista` REAL NOT NULL DEFAULT 0.0"
                )
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `demandaSemanalAlta` REAL NOT NULL DEFAULT 0.0"
                )
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `demandaSemanalBaja` REAL NOT NULL DEFAULT 0.0"
                )
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `forecastPeriodKey` TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `forecastModelVersion` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `forecastUsaEstacionalidad` INTEGER NOT NULL DEFAULT 0"
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
                    .addMigrations(MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}