package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date


data class Product(
    @DocumentId // Mapea el ID del documento Firestore a este campo
    val id: String = "",

    val name: String = "",
    val unit: String = "", // Unidad de medida (Kg, Bolsas, Cajas, Piezas)
    val minStock: Long = 0L, // Stock mínimo permitido (usamos Long para cantidades)
    val providerDetails: String = "", // Detalles/Proveedor

    // Stock actual en cada almacén
    val stockMatriz: Long = 0L,
    val stockCongelador04: Long = 0L,
    val totalStock: Long = 0L, // Campo calculado (stockMatriz + stockCongelador04)

    @ServerTimestamp // Firestore asignará la fecha del servidor al crear
    val createdAt: Date? = null,
    @ServerTimestamp // Firestore asignará/actualizará la fecha del servidor al modificar
    val updatedAt: Date? = null,
    val lastUpdatedByName: String? = null, // <-- AÑADIDO
       val isActive: Boolean = true // Opcional: Para "archivar" en lugar de borrar
) {
    constructor() : this(
        id = "", name = "", unit = "", minStock = 0L, providerDetails = "",
        stockMatriz = 0L, stockCongelador04 = 0L, totalStock = 0L,
        createdAt = null, updatedAt = null, lastUpdatedByName = null, isActive = true // <-- AÑADIDO EN CONSTRUCTOR
    )
}