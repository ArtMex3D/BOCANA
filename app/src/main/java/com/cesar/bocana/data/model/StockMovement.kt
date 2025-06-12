package com.cesar.bocana.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.cesar.bocana.data.local.Converters
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

// --- TU 'enum MovementType' RESTAURADO ---
enum class MovementType {
    COMPRA,
    SALIDA_CONSUMO,
    SALIDA_DEVOLUCION,
    TRASPASO_M_C04,
    TRASPASO_C04_M,
    AJUSTE_STOCK_C04,
    SALIDA_CONSUMO_C04,
    AJUSTE_MANUAL,
    // Tipos que yo había propuesto, se mantienen por si los usas en algún lugar
    DEVOLUCION_PROVEEDOR,
    DEVOLUCION_CLIENTE,
    AJUSTE_POSITIVO,
    AJUSTE_NEGATIVO,
    BAJA_PRODUCTO
}

// --- TU 'object Location' RESTAURADO ---
object Location {
    const val MATRIZ = "MATRIZ"
    const val CONGELADOR_04 = "CONGELADOR_04"
    const val PROVEEDOR = "PROVEEDOR"
    const val EXTERNO = "EXTERNO"
    const val AJUSTE = "AJUSTE"
}

/**
 * TU data class StockMovement, ahora con las etiquetas que Room necesita
 * para guardarla en la base de datos local.
 * NO se ha cambiado ningún nombre de campo.
 */
@Entity(tableName = "stock_movements")
@TypeConverters(Converters::class) // Le dice a Room cómo manejar Date y MovementType
data class StockMovement(
    @PrimaryKey
    @DocumentId

    val id: String = "",
    @ServerTimestamp
    val timestamp: Date? = null,
    val userId: String = "",
    val userName: String = "",
    val productId: String = "",
    val productName: String = "",

    // Se mantiene tu enum original
    val type: MovementType = MovementType.AJUSTE_MANUAL,

    val quantity: Double = 0.0,
    val locationFrom: String? = null,
    val locationTo: String? = null,
    val reason: String? = null,
    val stockAfterMatriz: Double? = null,
    val stockAfterCongelador04: Double? = null,
    val stockAfterTotal: Double? = null
) {
    // Tu constructor vacío original para Firestore
    constructor() : this(
        id = "", timestamp = null, userId = "", userName = "", productId = "", productName = "",
        type = MovementType.AJUSTE_MANUAL, quantity = 0.0, locationFrom = null, locationTo = null,
        reason = null, stockAfterMatriz = null, stockAfterCongelador04 = null, stockAfterTotal = null
    )
}