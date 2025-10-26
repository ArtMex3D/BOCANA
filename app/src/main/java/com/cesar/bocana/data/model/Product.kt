// main/java/com/cesar/bocana/data/model/Product.kt
package com.cesar.bocana.data.model

import android.os.Parcelable
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

// Valores permitidos para la prioridad
object PrioridadDesabasto {
    const val ALTA = "ALTA"
    const val MEDIA = "MEDIA"
    const val BAJA = "BAJA"
}

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
    val unit: String = "Kg", // Unidad de inventario principal SIEMPRE será Kg

    // --- CAMPOS ESENCIALES (MODIFICADOS/AÑADIDOS) ---
    val minStock: Double = 0.0,      // Stock mínimo general en Kg
    val stockIdealC04: Double = 0.0, // Stock MÍNIMO OBJETIVO en C-04, siempre en Kg (Se mantiene nombre)
    val stockMaximoC04: Double = 0.0, // NUEVO: Stock MÁXIMO deseado en C-04
    val prioridadDesabasto: String = PrioridadDesabasto.MEDIA, // NUEVO: Prioridad para ordenar sugerencias

    // --- CAMPOS DE CONFIGURACIÓN GENERAL (SIN CAMBIOS) ---
    @JvmField
    val requiresPackaging: Boolean = false,
    val ordenTraspaso: Int = 999,
    @JvmField
    val modoManualPDF: Boolean = false,
    val espacioExtraPDF: Double = 0.0,
    val labelConfig: @RawValue Map<String, Any>? = null,

    // --- CAMPOS DE CATEGORIZACIÓN (SIN CAMBIOS) ---
    val categoria: String = "FIJO",
    val productoRectorId: String? = null,

    // --- CAMPOS DE ESTADO (SIN CAMBIOS) ---
    val stockMatriz: Double = 0.0,
    val stockCongelador04: Double = 0.0,
    val totalStock: Double = 0.0,
    @JvmField
    val isActive: Boolean = true,
    @ServerTimestamp
    val createdAt: Date? = null,
    @ServerTimestamp
    val updatedAt: Date? = null,
    val lastUpdatedByName: String? = null

) : Parcelable {
    // Constructor vacío para Firestore (ACTUALIZADO)
    constructor() : this(
        id = "", name = "", unit = "Kg", minStock = 0.0,
        stockIdealC04 = 0.0,
        stockMaximoC04 = 0.0, // Añadido
        prioridadDesabasto = PrioridadDesabasto.MEDIA, // Añadido
        requiresPackaging = false,
        ordenTraspaso = 999, modoManualPDF = false, espacioExtraPDF = 0.0,
        labelConfig = null,
        categoria = "FIJO", productoRectorId = null,
        stockMatriz = 0.0, stockCongelador04 = 0.0, totalStock = 0.0,
        isActive = true, createdAt = null, updatedAt = null, lastUpdatedByName = null
    )
}