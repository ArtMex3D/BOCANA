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
 */
class PredictiveV3ConfigRepository(
    private val firestore: FirebaseFirestore
) {
    companion object {
        const val COLLECTION = "predictive_groups"
    }

    data class ConfigBundle(
        val groups: List<PredictiveGroupConfig>,
        val services: List<PredictiveServiceRelation>
    )

    suspend fun load(): ConfigBundle {
        val snapshot = firestore.collection(COLLECTION).get().await()
        val groups = mutableListOf<PredictiveGroupConfig>()
        val services = mutableListOf<PredictiveServiceRelation>()

        snapshot.documents.forEach { doc ->
            val type = doc.getString("configType")?.uppercase() ?: "GROUP"

            if (type == "SERVICE" || type == "BALANCE") {
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
                    enabled = doc.getBoolean("enabled") ?: true
                )
            }
        }

        return ConfigBundle(
            groups = groups.filter { it.enabled },
            services = services.filter { it.enabled }
        )
    }
}
