
package com.cesar.bocana.data.model

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.cesar.bocana.data.local.Converters
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.JvmField
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import androidx.room.Index

@Parcelize
@Entity(
    tableName = "products",
    indices = [Index(value = ["name"], unique = false)]
)
@TypeConverters(Converters::class)
data class Product(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val name: String = "",
    val unit: String = "Kg",

    val minStock: Double = 0.0,
    val stockIdealC04: Double = 0.0,

    @JvmField
    val requiresPackaging: Boolean = false,
    val ordenTraspaso: Int = 999,
    @JvmField
    val modoManualPDF: Boolean = false,
    val espacioExtraPDF: Double = 0.0,
    val labelConfig: @RawValue Map<String, Any>? = null,

    val categoria: String = "FIJO",
    val productoRectorId: String? = null,
    val stockMatriz: Double = 0.0,
    val stockCongelador04: Double = 0.0,
    val totalStock: Double = 0.0,

    // CONSUMO PREDICTIVO: datos ya calculados para evitar lecturas al abrir lista/popup
    val consumoSemanalPromedio: Double = 0.0,

    @ColumnInfo(defaultValue = "0.0")
    val demandaSemanalPrevista: Double = 0.0,

    @ColumnInfo(defaultValue = "0.0")
    val demandaSemanalAlta: Double = 0.0,

    @ColumnInfo(defaultValue = "0.0")
    val demandaSemanalBaja: Double = 0.0,

    @ColumnInfo(defaultValue = "''")
    val forecastPeriodKey: String = "",

    @ColumnInfo(defaultValue = "0")
    val forecastModelVersion: Int = 0,

    @JvmField
    @ColumnInfo(defaultValue = "0")
    val forecastUsaEstacionalidad: Boolean = false,

    @JvmField
    val isActive: Boolean = true,
    @ServerTimestamp
    val createdAt: Date? = null,
    @ServerTimestamp
    val updatedAt: Date? = null,
    val lastUpdatedByName: String? = null

) : Parcelable {
    constructor() : this(
        id = "", name = "", unit = "Kg", minStock = 0.0,
        stockIdealC04 = 0.0, requiresPackaging = false,
        ordenTraspaso = 999, modoManualPDF = false, espacioExtraPDF = 0.0,
        labelConfig = null, stockMatriz = 0.0, stockCongelador04 = 0.0, totalStock = 0.0,
        consumoSemanalPromedio = 0.0, demandaSemanalPrevista = 0.0,
        demandaSemanalAlta = 0.0, demandaSemanalBaja = 0.0,
        forecastPeriodKey = "", forecastModelVersion = 0, forecastUsaEstacionalidad = false,
        isActive = true, createdAt = null, updatedAt = null, lastUpdatedByName = null
    )
}