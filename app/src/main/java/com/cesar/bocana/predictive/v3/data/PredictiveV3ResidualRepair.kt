package com.cesar.bocana.predictive.v3.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/**
 * Mantenimiento explícito para residuos flotantes antiguos.
 *
 * IMPORTANTE:
 * - el motor predictivo NO llama esta clase automáticamente;
 * - sólo debe ejecutarse desde una herramienta administrativa/DEBUG con acción humana explícita;
 * - al limpiar un residuo también corrige el acumulado del producto para no dejar diferencias.
 */
class PredictiveV3ResidualRepair(
    private val firestore: FirebaseFirestore
) {
    data class Preview(
        val candidateCount: Int,
        val totalResidualKg: Double,
        val documentIds: List<String>
    )

    suspend fun preview(epsilonKg: Double = 0.01): Preview {
        val candidates = loadCandidates(epsilonKg)
        return Preview(
            candidateCount = candidates.size,
            totalResidualKg = candidates.sumOf { it.quantity },
            documentIds = candidates.map { it.id }
        )
    }

    /**
     * Limpieza real. NO se ejecuta sola.
     * Pone el lote en 0.00, lo marca agotado y descuenta el residuo del stock agregado.
     */
    suspend fun repairExplicitly(epsilonKg: Double = 0.01): Preview {
        val candidates = loadCandidates(epsilonKg)
        if (candidates.isEmpty()) return Preview(0, 0.0, emptyList())

        candidates.chunked(350).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { item ->
                val lotRef = firestore.collection("inventoryLots").document(item.id)
                batch.update(lotRef, mapOf(
                    "currentQuantity" to 0.0,
                    "isDepleted" to true
                ))
            }

            chunk.groupBy { it.productId }.forEach { (productId, productItems) ->
                val matrizKg = productItems.filter { it.location == "MATRIZ" }.sumOf { it.quantity }
                val c04Kg = productItems.filter { it.location == "CONGELADOR_04" }.sumOf { it.quantity }
                val totalKg = matrizKg + c04Kg
                val updates = mutableMapOf<String, Any>(
                    "totalStock" to FieldValue.increment(-totalKg)
                )
                if (matrizKg > 0.0) updates["stockMatriz"] = FieldValue.increment(-matrizKg)
                if (c04Kg > 0.0) updates["stockCongelador04"] = FieldValue.increment(-c04Kg)
                batch.update(firestore.collection("products").document(productId), updates)
            }
            batch.commit().await()
        }

        return Preview(
            candidateCount = candidates.size,
            totalResidualKg = candidates.sumOf { it.quantity },
            documentIds = candidates.map { it.id }
        )
    }

    private data class Candidate(
        val id: String,
        val productId: String,
        val location: String,
        val quantity: Double
    )

    private suspend fun loadCandidates(epsilonKg: Double): List<Candidate> {
        val snapshot = firestore.collection("inventoryLots")
            .whereEqualTo("isDepleted", false)
            .get().await()
        return snapshot.documents.mapNotNull { doc ->
            val qty = doc.getDouble("currentQuantity") ?: return@mapNotNull null
            if (qty < 0.0 || abs(qty) > epsilonKg) return@mapNotNull null
            val productId = doc.getString("productId") ?: return@mapNotNull null
            val location = doc.getString("location") ?: return@mapNotNull null
            Candidate(doc.id, productId, location, qty)
        }
    }
}
