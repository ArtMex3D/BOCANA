package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

// Tipos de movimientos posibles
enum class MovementType {
    COMPRA,             // Entrada a Matriz desde proveedor
    SALIDA_CONSUMO,     // Salida de Matriz (no devolución, no traspaso)
    SALIDA_DEVOLUCION,  // Salida de Matriz hacia proveedor (para sección Devoluciones)
    TRASPASO_M_C04,     // Traspaso interno: Matriz -> Congelador 04
    TRASPASO_C04_M,     // Traspaso interno: Congelador 04 -> Matriz
    AJUSTE_STOCK_C04,   // Ajuste por "venta/salida" desde Congelador 04 (el botón lápiz)
    AJUSTE_MANUAL       // Ajuste por corrección de conteo (desde sección Ajustes)
}

// Ubicaciones posibles (simplificado)
object Location {
    const val MATRIZ = "MATRIZ"
    const val CONGELADOR_04 = "CONGELADOR_04"
    const val PROVEEDOR = "PROVEEDOR" // Origen de compra, destino de devolución
    const val EXTERNO = "EXTERNO" // Destino de Salida por Consumo o Ajuste C04
    const val AJUSTE = "AJUSTE" // Origen/Destino para ajustes manuales
}


/**
 * Registra un movimiento individual de stock en el historial.
 */
data class StockMovement(
    @DocumentId
    val id: String = "",

    @ServerTimestamp
    val timestamp: Date? = null, // Fecha/Hora del movimiento

    val userId: String = "", // UID del usuario que realizó el movimiento
    val userName: String = "", // Nombre del usuario (por conveniencia)

    val productId: String = "", // ID del producto afectado
    val productName: String = "", // Nombre del producto (por conveniencia)

    val type: MovementType = MovementType.AJUSTE_MANUAL, // Tipo de movimiento
    val quantity: Long = 0L, // Cantidad movida (siempre positiva)

    // Información de origen/destino para claridad
    val locationFrom: String? = null, // Ej: PROVEEDOR, MATRIZ, CONGELADOR_04
    val locationTo: String? = null,   // Ej: MATRIZ, CONGELADOR_04, EXTERNO, PROVEEDOR

    val reason: String? = null, // Motivo (obligatorio para AJUSTE_MANUAL)

    // Opcional: Stock resultante tras el movimiento (para auditoría fácil)
    val stockAfterMatriz: Long? = null,
    val stockAfterCongelador04: Long? = null,
    val stockAfterTotal: Long? = null

) {
    // Constructor vacío necesario para Firestore
    constructor() : this(
        id = "", timestamp = null, userId = "", userName = "", productId = "", productName = "",
        type = MovementType.AJUSTE_MANUAL, quantity = 0L, locationFrom = null, locationTo = null,
        reason = null, stockAfterMatriz = null, stockAfterCongelador04 = null, stockAfterTotal = null
    )
}