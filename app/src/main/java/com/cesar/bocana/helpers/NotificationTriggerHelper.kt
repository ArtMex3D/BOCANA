package com.cesar.bocana.helpers

import android.util.Log
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.User
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

object NotificationTriggerHelper {

    private const val TAG = "NotificationTrigger"
    private val db = Firebase.firestore
    private const val QUEUE_COLLECTION = "sendNotificationQueue" // Make sure this matches your Trigger Email extension config

    private suspend fun getAdminEmails(): List<String> {
        return try {
            val adminSnapshot = db.collection("users")
                .whereEqualTo("role", "ADMIN")
                .whereEqualTo("isAccountActive", true) // Also check if admin is active
                .get()
                .await()

            val emails = adminSnapshot.documents.mapNotNull { it.getString("email") }
            Log.d(TAG, "Admin emails found: $emails")
            emails
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error getting admin emails", e)
            emptyList()
        }
    }

    suspend fun triggerLowStockNotification(product: Product) {
        // Compare Doubles, check minStock > 0.0
        if (product.minStock <= 0.0) {
            Log.d(TAG, "No low stock notification needed for ${product.name}, minStock not set > 0.0")
            return
        }
        if (product.totalStock > product.minStock) {
            Log.d(TAG, "No low stock notification needed for ${product.name}, stock (${product.totalStock}) > minStock (${product.minStock})")
            return
        }

        Log.d(TAG, "Triggering low stock notification for ${product.name}")
        val adminEmails = getAdminEmails()
        if (adminEmails.isEmpty()) {
            Log.w(TAG, "No active admin emails found to send low stock notification.")
            return
        }

        // Format quantities for the email body
        val format = Locale.getDefault()
        val currentStockStr = String.format(format, "%.2f", product.totalStock)
        val minStockStr = String.format(format, "%.2f", product.minStock)
        val unitStr = product.unit ?: ""

        val subject = "🚨 Alerta de Stock Bajo: ${product.name}"
        val messageText = """
            ¡Alerta de Inventario!

            El producto '${product.name}' ha alcanzado un nivel de stock bajo.

            - Stock Actual: $currentStockStr $unitStr
            - Stock Mínimo: $minStockStr $unitStr

            Por favor, revisa el inventario para tomar las acciones necesarias.
            
            Fecha de Alerta: ${Date()}
        """.trimIndent()

        // Structure for Firebase Trigger Email Extension
        val notificationRequest = hashMapOf(
            "to" to adminEmails,
            "message" to hashMapOf(
                "subject" to subject,
                "text" to messageText
                // "html" key could be added here for formatted emails
            ),
            // Add a timestamp for potential debugging or tracking in Firestore
            "createdAt" to FieldValue.serverTimestamp(),
            "type" to "lowStockAlert", // Optional: categorize notifications
            "productId" to product.id // Optional: link back to product
        )

        try {
            db.collection(QUEUE_COLLECTION).add(notificationRequest).await()
            Log.i(TAG, "Low stock notification request added to queue for ${product.name}.")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error adding low stock notification request to queue for ${product.name}", e)
        }
    }
}