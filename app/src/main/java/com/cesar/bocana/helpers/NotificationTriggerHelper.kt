package com.cesar.bocana.helpers

import android.util.Log
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.User // Necesario para leer usuarios
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.Date

object NotificationTriggerHelper {

    private const val TAG = "NotificationTrigger"
    private val db = Firebase.firestore
    private const val QUEUE_COLLECTION = "sendNotificationQueue"

    // Obtiene la lista de correos de los administradores
    private suspend fun getAdminEmails(): List<String> {
        return try {
            val adminSnapshot = db.collection("users")
                .whereEqualTo("role", "ADMIN") // Busca por el rol ADMIN
                .get()
                .await() // Espera el resultado

            val emails = adminSnapshot.documents.mapNotNull { it.getString("email") }
            Log.d(TAG, "Admin emails found: $emails")
            emails
        } catch (e: Exception) {
            Log.e(TAG, "Error getting admin emails", e)
            emptyList() // Devuelve lista vacía en caso de error
        }
    }

    // Crea el documento en Firestore para disparar el email de stock bajo
    suspend fun triggerLowStockNotification(product: Product) {
        if (product.minStock <= 0) return // No notificar si no hay mínimo definido
        if (product.totalStock > product.minStock) return // Doble check: No notificar si el stock ya no está bajo

        Log.d(TAG, "Triggering low stock notification for ${product.name}")
        val adminEmails = getAdminEmails()
        if (adminEmails.isEmpty()) {
            Log.w(TAG, "No admin emails found to send low stock notification.")
            return
        }

        val subject = "🚨 Alerta Stock Bajo: ${product.name}"
        val messageText = """
            ¡Alerta de Stock Bajo!
            Producto: ${product.name} (${product.id})
            Stock Actual: ${product.totalStock} ${product.unit ?: ""}
            Stock Mínimo: ${product.minStock} ${product.unit ?: ""}
            Fecha: ${Date()}
        """.trimIndent() // trimIndent quita espacios iniciales comunes

        val notificationRequest = hashMapOf(
            "to" to adminEmails, // Campo 'to' para la extensión Trigger Email
            "message" to hashMapOf( // Campo 'message' estándar de la extensión
                "subject" to subject,
                "text" to messageText
                // Podrías añadir 'html' aquí si quieres formato más avanzado
            ),
            "triggerTimestamp" to FieldValue.serverTimestamp() // Hora del servidor
        )

        try {
            db.collection(QUEUE_COLLECTION).add(notificationRequest).await()
            Log.i(TAG, "Low stock notification request added to queue for ${product.name}.")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding low stock notification request to queue", e)
        }
    }

    // --- Funciones Placeholder para otros tipos de notificación ---
    // (Implementaremos la lógica de detección y llamada a estas más adelante si es necesario)

    suspend fun triggerOverduePackagingNotification(/* Lista de tareas atrasadas */) {
        // Similar a triggerLowStockNotification:
        // 1. getAdminEmails()
        // 2. Construir subject y message sobre tareas atrasadas
        // 3. Crear el 'notificationRequest' map
        // 4. Añadir a db.collection(QUEUE_COLLECTION)
        Log.d(TAG, "Placeholder: Triggering overdue packaging notification...")
        // Por ahora no hace nada
    }

    suspend fun triggerPendingReturnsNotification(/* Lista de devoluciones */) {
        // Similar a triggerLowStockNotification
        Log.d(TAG, "Placeholder: Triggering pending returns notification...")
        // Por ahora no hace nada
    }

}