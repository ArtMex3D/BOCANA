package com.cesar.bocana.maintenance

import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.util.StockQuantityPolicy
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Mantenimiento ADMINISTRATIVO explícito para limpiar lotes residuales antiguos.
 *
 * IMPORTANTE:
 * - Está fuera del paquete del motor predictivo por diseño;
 * - el motor V3 nunca llama esta clase;
 * - sólo debe ejecutarse por acción humana desde una herramienta de mantenimiento;
 * - menos de 0.10 kg se considera agotado y se lleva a 0.00;
 * - después de reparar, reconcilia Matriz/C04/Total con la suma real de lotes.
 */
class InventoryResidualRepair(
    private val firestore: FirebaseFirestore
) {
    data class Preview(
        val candidateCount: Int,
        val totalResidualKg: Double,
        val documentIds: List<String>,
        val affectedProductIds: List<String> = emptyList(),
        val reconciledProductCount: Int = 0,
        val invalidOrNegativeCount: Int = 0
    )

    suspend fun preview(thresholdKg: Double = StockQuantityPolicy.MIN_USABLE_KG): Preview {
        val candidates = loadCandidates(thresholdKg)
        return Preview(
            candidateCount = candidates.size,
            totalResidualKg = candidates.sumOf { if (it.quantity.isFinite()) it.quantity.coerceAtLeast(0.0) else 0.0 },
            documentIds = candidates.map { it.id },
            affectedProductIds = candidates.map { it.productId }.distinct(),
            invalidOrNegativeCount = candidates.count { !it.quantity.isFinite() || it.quantity < 0.0 }
        )
    }

    /**
     * Limpieza real y explícita.
     * Pone cada residuo en 0.00, marca el lote agotado y recalcula el stock agregado del producto.
     */
    suspend fun repairExplicitly(
        thresholdKg: Double = StockQuantityPolicy.MIN_USABLE_KG
    ): Preview {
        val candidates = loadCandidates(thresholdKg)
        if (candidates.isEmpty()) return Preview(0, 0.0, emptyList())

        candidates.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { item ->
                val lotRef = firestore.collection("inventoryLots").document(item.id)
                batch.update(lotRef, mapOf(
                    "currentQuantity" to 0.0,
                    "isDepleted" to true
                ))
            }
            batch.commit().await()
        }

        val affectedProducts = candidates.map { it.productId }.distinct()
        reconcileProducts(affectedProducts)

        return Preview(
            candidateCount = candidates.size,
            totalResidualKg = candidates.sumOf { if (it.quantity.isFinite()) it.quantity.coerceAtLeast(0.0) else 0.0 },
            documentIds = candidates.map { it.id },
            affectedProductIds = affectedProducts,
            reconciledProductCount = affectedProducts.size,
            invalidOrNegativeCount = candidates.count { !it.quantity.isFinite() || it.quantity < 0.0 }
        )
    }

    private suspend fun reconcileProducts(productIds: List<String>) {
        // Secuencial a propósito: mantenimiento prioriza consistencia y estabilidad
        // sobre velocidad; evita lanzar decenas de lecturas/escrituras simultáneas.
        for (productId in productIds) {
            val snapshot = firestore.collection("inventoryLots")
                .whereEqualTo("productId", productId)
                .get().await()

            val lots = snapshot.documents.mapNotNull { doc ->
                doc.toObject(StockLot::class.java)?.copy(id = doc.id)
            }

            val matriz = lots.asSequence()
                .filter { !it.isDepleted && it.location == Location.MATRIZ }
                .sumOf { StockQuantityPolicy.normalizeLotQuantity(it.currentQuantity) }

            val c04 = lots.asSequence()
                .filter { !it.isDepleted && it.location == Location.CONGELADOR_04 }
                .sumOf { StockQuantityPolicy.normalizeLotQuantity(it.currentQuantity) }

            firestore.collection("products").document(productId).update(
                mapOf(
                    "stockMatriz" to matriz,
                    "stockCongelador04" to c04,
                    "totalStock" to (matriz + c04),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    private data class Candidate(
        val id: String,
        val productId: String,
        val quantity: Double
    )

    private suspend fun loadCandidates(thresholdKg: Double): List<Candidate> {
        require(thresholdKg >= 0.0) { "thresholdKg debe ser >= 0" }
        val snapshot = firestore.collection("inventoryLots")
            .whereEqualTo("isDepleted", false)
            .get().await()

        return snapshot.documents.mapNotNull { doc ->
            val qty = doc.getDouble("currentQuantity") ?: return@mapNotNull null
            val mustClose = !qty.isFinite() || qty < 0.0 || (qty + StockQuantityPolicy.FLOAT_EPSILON < thresholdKg)
            if (!mustClose) return@mapNotNull null
            val productId = doc.getString("productId") ?: return@mapNotNull null
            Candidate(doc.id, productId, qty)
        }
    }
}
