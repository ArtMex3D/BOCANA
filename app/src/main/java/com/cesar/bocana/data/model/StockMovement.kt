package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

enum class MovementType {
    COMPRA,
    SALIDA_CONSUMO,
    SALIDA_DEVOLUCION,
    TRASPASO_M_C04,
    TRASPASO_C04_M,
    AJUSTE_STOCK_C04, // Este es para el ajuste del botón "Editar C04" que reduce stock
    SALIDA_CONSUMO_C04, // **NUEVO TIPO AÑADIDO** para un consumo directo desde C04 si es diferente a AJUSTE_STOCK_C04
    AJUSTE_MANUAL
}

object Location {
    const val MATRIZ = "MATRIZ"
    const val CONGELADOR_04 = "CONGELADOR_04"
    const val PROVEEDOR = "PROVEEDOR"
    const val EXTERNO = "EXTERNO" // Para salidas generales por consumo o devolución
    const val AJUSTE = "AJUSTE" // Para movimientos de ajuste que no tienen un origen/destino físico claro
}

data class StockMovement(
    @DocumentId
    val id: String = "",
    @ServerTimestamp
    val timestamp: Date? = null,
    val userId: String = "",
    val userName: String = "",
    val productId: String = "",
    val productName: String = "",
    val type: MovementType = MovementType.AJUSTE_MANUAL,
    val quantity: Double = 0.0, // Siempre positivo para COMPRA, SALIDA, TRASPASO. Para AJUSTE, puede ser +/-.
    val locationFrom: String? = null, // Ej: MATRIZ, CONGELADOR_04, PROVEEDOR, AJUSTE
    val locationTo: String? = null,   // Ej: MATRIZ, CONGELADOR_04, EXTERNO, AJUSTE
    val reason: String? = null, // Ej: "Lotes afectados: abc:10, def:5 | Motivo: Merma"
    val stockAfterMatriz: Double? = null,
    val stockAfterCongelador04: Double? = null,
    val stockAfterTotal: Double? = null
) {
    // Constructor vacío para Firestore
    constructor() : this(
        id = "", timestamp = null, userId = "", userName = "", productId = "", productName = "",
        type = MovementType.AJUSTE_MANUAL, quantity = 0.0, locationFrom = null, locationTo = null,
        reason = null, stockAfterMatriz = null, stockAfterCongelador04 = null, stockAfterTotal = null
    )
}
