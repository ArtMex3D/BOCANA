package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.JvmField // Asegúrate de tener este import si usas @JvmField

data class Product(
    @DocumentId
    val id: String = "",
    val name: String = "",
    val unit: String = "",
    val minStock: Double = 0.0,
    val providerDetails: String = "",
    val stockMatriz: Double = 0.0,
    val stockCongelador04: Double = 0.0,
    val totalStock: Double = 0.0,
    @ServerTimestamp
    val createdAt: Date? = null,
    @ServerTimestamp
    val updatedAt: Date? = null,
    val lastUpdatedByName: String? = null,
    @JvmField // Es buena práctica para booleanos is... o requires...
    val isActive: Boolean = true,
    @JvmField // Campo añadido
    val requiresPackaging: Boolean = false // Añadido: Valor por defecto false
) {
    // Constructor vacío actualizado
    constructor() : this(
        id = "", name = "", unit = "", minStock = 0.0, providerDetails = "",
        stockMatriz = 0.0, stockCongelador04 = 0.0, totalStock = 0.0,
        createdAt = null, updatedAt = null, lastUpdatedByName = null, isActive = true,
        requiresPackaging = false // Añadido al constructor vacío
    )
}