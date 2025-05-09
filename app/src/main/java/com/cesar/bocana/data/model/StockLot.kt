package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.JvmField // Importar si usas @JvmField

data class StockLot(
    @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "", // Conveniencia
    val unit: String = "", // Conveniencia (Peso Neto)

    val location: String = Location.MATRIZ, // MATRIZ o CONGELADOR_04

    // Información de Origen
    val supplierId: String? = null,
    val supplierName: String? = null, // Conveniencia
    @ServerTimestamp val receivedAt: Date? = null, // Fecha recepción original o traspaso
    val movementIdIn: String = "", // ID del StockMovement que creó/movió este lote

    // Cantidades Netas
    val initialQuantity: Double = 0.0, // Cantidad neta original al recibir/crear lote
    var currentQuantity: Double = 0.0, // Cantidad neta restante en este lote ¡Esta cambia!

    // Información Opcional del Lote
    val lotNumber: String? = null, // Número de lote del proveedor
    val expirationDate: Date? = null, // Fecha de caducidad

    // Estado
    @JvmField // Ayuda a Firestore con booleanos 'is...'
    var isDepleted: Boolean = false, // Se marca true cuando currentQuantity <= 0.0
    @JvmField // Puede ser útil para saber si pasó por empaque (si aplica)
    var isPackaged: Boolean = false // Se marcará true al completar tarea de empaque
) {
    // Constructor vacío para Firestore
    constructor() : this(
        id = "", productId = "", productName = "", unit = "", location = Location.MATRIZ,
        supplierId = null, supplierName = null, receivedAt = null, movementIdIn = "",
        initialQuantity = 0.0, currentQuantity = 0.0,
        lotNumber = null, expirationDate = null,
        isDepleted = false, isPackaged = false
    )
}