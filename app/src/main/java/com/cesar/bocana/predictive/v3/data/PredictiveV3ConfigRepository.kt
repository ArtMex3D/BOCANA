package com.cesar.bocana.predictive.v3.data

import com.cesar.bocana.predictive.v3.model.GroupMemberRule
import com.cesar.bocana.predictive.v3.model.PredictiveGroupConfig
import com.cesar.bocana.predictive.v3.model.PredictiveServiceRelation
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Repositorio de SOLO LECTURA usado por el motor predictivo.
 *
 * Las altas/ediciones/bajas de grupos viven en PredictiveGroupAdminRepository.
 * De esta forma el motor V3 nunca escribe configuración por sí mismo.
 *
 * La configuración se carga una sola vez por proceso y queda en memoria para evitar
 * releer Firestore cada vez que el usuario abre un popup o vuelve a Traspasos.
 * Las pantallas administrativas invalidan la caché inmediatamente al guardar.
 */
class PredictiveV3ConfigRepository(
    private val firestore: FirebaseFirestore
) {
    companion object {
        const val COLLECTION = "predictive_groups"
        @Volatile
        private var memoryCache: CacheEntry? = null

        fun invalidateMemoryCache() {
            memoryCache = null
        }

        private data class CacheEntry(
            val bundle: ConfigBundle
        )
    }

    data class ConfigBundle(
        val groups: List<PredictiveGroupConfig>,
        val services: List<PredictiveServiceRelation>,
        val productionAdvanceProductIds: Set<String> = emptySet()
    )

    suspend fun load(forceRefresh: Boolean = false): ConfigBundle {
        val cached = memoryCache
        if (!forceRefresh && cached != null) {
            return cached.bundle
        }

        val snapshot = firestore.collection(COLLECTION).get().await()
        val groups = mutableListOf<PredictiveGroupConfig>()
        val services = mutableListOf<PredictiveServiceRelation>()
        val productionAdvanceProductIds = linkedSetOf<String>()

        snapshot.documents.forEach { doc ->
            val type = doc.getString("configType")?.uppercase() ?: "GROUP"

            if (type == "PRODUCT_MODE") {
                (doc.get("productionAdvanceProductIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.filter { it.isNotBlank() }
                    ?.let(productionAdvanceProductIds::addAll)
            } else if (type == "SERVICE" || type == "BALANCE") {
                val anchorIds = (doc.get("anchorProductIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()

                val legacyAnchor = doc.getString("anchorProductId").orEmpty()
                val normalizedAnchorIds = (anchorIds + listOf(legacyAnchor))
                    .filter { it.isNotBlank() }
                    .distinct()

                services += PredictiveServiceRelation(
                    id = doc.id,
                    name = doc.getString("name") ?: doc.id,
                    anchorProductId = legacyAnchor.ifBlank { normalizedAnchorIds.firstOrNull().orEmpty() },
                    anchorProductIds = normalizedAnchorIds,
                    historicalAnchorProductIds = (doc.get("historicalAnchorProductIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.distinct()
                        .orEmpty(),
                    primaryAnchorProductId = doc.getString("primaryAnchorProductId"),
                    linkedGroupId = doc.getString("linkedGroupId") ?: "",
                    preferredGroupProductId = doc.getString("preferredGroupProductId"),
                    relationMode = doc.getString("relationMode") ?: "INVERSE_SUPPORT",
                    enabled = doc.getBoolean("enabled") ?: true
                )
            } else {
                val rules = (doc.get("memberRules") as? List<*>)
                    ?.mapNotNull { raw ->
                        val map = raw as? Map<*, *> ?: return@mapNotNull null
                        val productId = map["productId"] as? String ?: return@mapNotNull null
                        GroupMemberRule(
                            productId = productId,
                            priorityWeight = (map["priorityWeight"] as? Number)?.toDouble() ?: 1.0,
                            enabled = map["enabled"] as? Boolean ?: true
                        )
                    }
                    .orEmpty()

                groups += PredictiveGroupConfig(
                    id = doc.id,
                    name = doc.getString("name") ?: doc.id,
                    memberProductIds = (doc.get("memberProductIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.distinct()
                        .orEmpty(),
                    historicalProductIds = (doc.get("historicalProductIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.distinct()
                        .orEmpty(),
                    memberRules = rules,
                    primaryProductId = doc.getString("primaryProductId"),
                    secondaryProductIds = (doc.get("secondaryProductIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        ?.distinct()
                        .orEmpty(),
                    c04GroupTargetKg = doc.getDouble("c04GroupTargetKg") ?: 0.0,
                    primaryMinimumC04Kg = doc.getDouble("primaryMinimumC04Kg") ?: 0.0,
                    enabled = doc.getBoolean("enabled") ?: true
                )
            }
        }

        val bundle = ConfigBundle(
            groups = groups.filter { it.enabled },
            services = services.filter { it.enabled },
            productionAdvanceProductIds = productionAdvanceProductIds
        )
        memoryCache = CacheEntry(bundle)
        return bundle
    }
}
