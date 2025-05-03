package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Representa un comentario realizado desde la interfaz web sobre un producto.
 */
data class WebComment(
    @DocumentId
    val id: String = "",

    val productId: String = "", // ID del producto comentado
    val productName: String = "", // Nombre (conveniencia)

    val commentText: String = "", // El texto del comentario

    @ServerTimestamp
    val commentedAt: Date? = null, // Cuando se hizo el comentario

    val userId: String = "", // Quién comentó (el UID del usuario Visor/Admin logueado en la web)
    val userName: String = "", // Nombre del usuario (conveniencia)

    val isAcknowledged: Boolean = false // Opcional: Para marcar si ya se vio en la app
) {
    // Constructor vacío necesario para Firestore
    constructor() : this(
        id = "", productId = "", productName = "", commentText = "",
        commentedAt = null, userId = "", userName = "", isAcknowledged = false
    )
}