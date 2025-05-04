package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

enum class MovementType {
    COMPRA, SALIDA_CONSUMO, SALIDA_DEVOLUCION,
    TRASPASO_M_C04, TRASPASO_C04_M, AJUSTE_STOCK_C04, AJUSTE_MANUAL
}

object Location {
    const val MATRIZ = "MATRIZ"
    const val CONGELADOR_04 = "CONGELADOR_04"
    const val PROVEEDOR = "PROVEEDOR"
    const val EXTERNO = "EXTERNO"
    const val AJUSTE = "AJUSTE"
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
    val quantity: Double = 0.0,
    val locationFrom: String? = null,
    val locationTo: String? = null,
    val reason: String? = null,
    val stockAfterMatriz: Double? = null,
    val stockAfterCongelador04: Double? = null,
    val stockAfterTotal: Double? = null
) {
    constructor() : this(
        id = "", timestamp = null, userId = "", userName = "", productId = "", productName = "",
        type = MovementType.AJUSTE_MANUAL, quantity = 0.0, locationFrom = null, locationTo = null,
        reason = null, stockAfterMatriz = null, stockAfterCongelador04 = null, stockAfterTotal = null
    )
}