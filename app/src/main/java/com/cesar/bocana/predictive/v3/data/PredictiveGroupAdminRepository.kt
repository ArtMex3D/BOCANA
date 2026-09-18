package com.cesar.bocana.predictive.v3.data

import com.cesar.bocana.data.model.Product
import com.cesar.bocana.predictive.v3.model.GroupMemberRule
import com.cesar.bocana.predictive.v3.model.PredictiveGroupConfig
import com.cesar.bocana.predictive.v3.model.PredictiveServiceRelation
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Repositorio ADMINISTRATIVO para la pantalla "Grupos y prioridades".
 *
 * Este repositorio sí escribe porque el usuario lo ordena explícitamente desde la UI.
 * NO se usa desde PredictiveV3Coordinator ni desde el motor automático.
 */
class PredictiveGroupAdminRepository(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val COLLECTION = PredictiveV3ConfigRepository.COLLECTION
        private const val PRIMARY_WEIGHT = 1.35
        private const val SECONDARY_WEIGHT = 1.15
        private const val NORMAL_WEIGHT = 1.0
    }

    data class AdminBundle(
        val activeProducts: List<Product>,
        val groups: List<PredictiveGroupConfig>,
        val balances: List<PredictiveServiceRelation>
    )

    suspend fun load(): AdminBundle {
        val products = firestore.collection("products")
            .whereEqualTo("isActive", true)
            .get()
            .await()
            .documents
            .mapNotNull { doc -> doc.toObject(Product::class.java)?.copy(id = doc.id) }
            .sortedBy { it.name.lowercase() }

        val allConfig = loadAllConfig()
        return AdminBundle(products, allConfig.groups, allConfig.services)
    }

    /**
     * Se ejecuta únicamente al abrir la pantalla administrativa.
     * Retira de la configuración productos desactivados, pero NO toca movimientos,
     * checkpoints, lotes agotados ni históricos.
     */
    suspend fun cleanupInactiveMembers(activeProductIds: Set<String>): Boolean {
        val snapshot = firestore.collection(COLLECTION).get().await()
        var changed = false

        snapshot.documents.forEach { doc ->
            val type = doc.getString("configType")?.uppercase() ?: "GROUP"

            if (type == "SERVICE" || type == "BALANCE") {
                val legacyAnchor = doc.getString("anchorProductId").orEmpty()
                val stored = (doc.get("anchorProductIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty()

                val originalAnchors = (stored + listOf(legacyAnchor))
                    .filter { it.isNotBlank() }
                    .distinct()

                val anchors = originalAnchors
                    .filter { activeProductIds.contains(it) }
                    .distinct()

                val oldHistoricalAnchors = (doc.get("historicalAnchorProductIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty()
                val historicalAnchors = (oldHistoricalAnchors + originalAnchors.filterNot { activeProductIds.contains(it) })
                    .filter { it.isNotBlank() && !anchors.contains(it) }
                    .distinct()

                val oldPrimary = doc.getString("primaryAnchorProductId")
                val newPrimary = oldPrimary
                    ?.takeIf { activeProductIds.contains(it) && anchors.contains(it) }
                    ?: anchors.firstOrNull()

                val preferred = doc.getString("preferredGroupProductId")
                    ?.takeIf { activeProductIds.contains(it) }

                if (anchors != originalAnchors ||
                    historicalAnchors != oldHistoricalAnchors.distinct() ||
                    newPrimary != oldPrimary ||
                    preferred != doc.getString("preferredGroupProductId")
                ) {
                    doc.reference.update(
                        mapOf(
                            "anchorProductIds" to anchors,
                            "historicalAnchorProductIds" to historicalAnchors,
                            "anchorProductId" to (newPrimary ?: ""),
                            "primaryAnchorProductId" to newPrimary,
                            "preferredGroupProductId" to preferred,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    changed = true
                }
            } else {
                val members = (doc.get("memberProductIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty()

                val cleanMembers = members.filter { activeProductIds.contains(it) }.distinct()

                val oldHistorical = (doc.get("historicalProductIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty()
                val historical = (oldHistorical + members.filterNot { activeProductIds.contains(it) })
                    .filter { it.isNotBlank() && !cleanMembers.contains(it) }
                    .distinct()

                val oldSecondary = (doc.get("secondaryProductIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty()
                val cleanSecondary = oldSecondary
                    .filter { activeProductIds.contains(it) && cleanMembers.contains(it) }
                    .distinct()

                val oldPrimary = doc.getString("primaryProductId")
                val cleanPrimary = oldPrimary
                    ?.takeIf { activeProductIds.contains(it) && cleanMembers.contains(it) }

                val oldRules = (doc.get("memberRules") as? List<*>).orEmpty()
                val cleanRules = oldRules.mapNotNull { raw ->
                    val map = raw as? Map<*, *> ?: return@mapNotNull null
                    val productId = map["productId"] as? String ?: return@mapNotNull null
                    if (!cleanMembers.contains(productId)) return@mapNotNull null
                    mapOf(
                        "productId" to productId,
                        "priorityWeight" to ((map["priorityWeight"] as? Number)?.toDouble() ?: NORMAL_WEIGHT),
                        "enabled" to ((map["enabled"] as? Boolean) ?: true)
                    )
                }

                if (cleanMembers != members.distinct() ||
                    historical != oldHistorical.distinct() ||
                    cleanSecondary != oldSecondary.distinct() ||
                    cleanPrimary != oldPrimary ||
                    cleanRules.size != oldRules.size
                ) {
                    doc.reference.update(
                        mapOf(
                            "memberProductIds" to cleanMembers,
                            "historicalProductIds" to historical,
                            "memberRules" to cleanRules,
                            "primaryProductId" to cleanPrimary,
                            "secondaryProductIds" to cleanSecondary,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    changed = true
                }
            }
        }

        return changed
    }

    suspend fun saveJointGroup(
        existingId: String?,
        name: String,
        memberProductIds: List<String>,
        primaryProductId: String?,
        secondaryProductIds: List<String>
    ): String {
        require(name.isNotBlank()) { "El grupo necesita un nombre." }

        val members = memberProductIds.filter { it.isNotBlank() }.distinct()
        require(members.isNotEmpty()) { "Selecciona al menos un producto." }

        validateNoDuplicateJointMembership(existingId, members)

        val primary = primaryProductId?.takeIf { members.contains(it) }
        val secondaries = secondaryProductIds
            .filter { members.contains(it) && it != primary }
            .distinct()

        val existing = existingId
            ?.let { id -> loadAllConfig().groups.firstOrNull { it.id == id } }
        val historical = (
            existing?.historicalProductIds.orEmpty() +
                existing?.memberProductIds.orEmpty().filterNot { members.contains(it) }
            )
            .filter { it.isNotBlank() && !members.contains(it) }
            .distinct()

        val rules = members.map { productId ->
            val weight = when {
                productId == primary -> PRIMARY_WEIGHT
                secondaries.contains(productId) -> SECONDARY_WEIGHT
                else -> NORMAL_WEIGHT
            }
            mapOf(
                "productId" to productId,
                "priorityWeight" to weight,
                "enabled" to true
            )
        }

        val docRef = existingId
            ?.takeIf { it.isNotBlank() }
            ?.let { firestore.collection(COLLECTION).document(it) }
            ?: firestore.collection(COLLECTION).document()

        val data = hashMapOf<String, Any?>(
            "configType" to "GROUP",
            "name" to name.trim(),
            "memberProductIds" to members,
            "historicalProductIds" to historical,
            "memberRules" to rules,
            "primaryProductId" to primary,
            "secondaryProductIds" to secondaries,
            "enabled" to true,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        if (existingId.isNullOrBlank()) {
            data["createdAt"] = FieldValue.serverTimestamp()
        }

        docRef.set(data, com.google.firebase.firestore.SetOptions.merge()).await()
        return docRef.id
    }

    suspend fun saveBalanceGroup(
        existingId: String?,
        name: String,
        anchorProductIds: List<String>,
        primaryAnchorProductId: String?,
        linkedGroupId: String,
        preferredGroupProductId: String?
    ): String {
        require(name.isNotBlank()) { "El grupo necesita un nombre." }

        val anchors = anchorProductIds.filter { it.isNotBlank() }.distinct()
        require(anchors.isNotEmpty()) { "Selecciona al menos un producto directo." }
        require(linkedGroupId.isNotBlank()) { "Selecciona el grupo relacionado." }

        val primary = primaryAnchorProductId
            ?.takeIf { anchors.contains(it) }
            ?: anchors.first()

        val allConfig = loadAllConfig()
        val existing = existingId
            ?.let { id -> allConfig.services.firstOrNull { it.id == id } }
        val historicalAnchors = (
            existing?.historicalAnchorProductIds.orEmpty() +
                existing?.effectiveAnchorProductIds().orEmpty().filterNot { anchors.contains(it) }
            )
            .filter { it.isNotBlank() && !anchors.contains(it) }
            .distinct()

        val linkedGroup = allConfig.groups.firstOrNull { it.id == linkedGroupId }
            ?: error("El grupo relacionado ya no existe.")

        val preferred = preferredGroupProductId
            ?.takeIf { linkedGroup.memberProductIds.contains(it) }

        val docRef = existingId
            ?.takeIf { it.isNotBlank() }
            ?.let { firestore.collection(COLLECTION).document(it) }
            ?: firestore.collection(COLLECTION).document()

        val data = hashMapOf<String, Any?>(
            // Se conserva SERVICE por compatibilidad interna; para el usuario es un grupo de equilibrio.
            "configType" to "SERVICE",
            "name" to name.trim(),
            "anchorProductId" to primary,
            "anchorProductIds" to anchors,
            "historicalAnchorProductIds" to historicalAnchors,
            "primaryAnchorProductId" to primary,
            "linkedGroupId" to linkedGroupId,
            "preferredGroupProductId" to preferred,
            "relationMode" to "INVERSE_SUPPORT",
            "enabled" to true,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        if (existingId.isNullOrBlank()) {
            data["createdAt"] = FieldValue.serverTimestamp()
        }

        docRef.set(data, com.google.firebase.firestore.SetOptions.merge()).await()
        return docRef.id
    }

    /**
     * Elimina únicamente la CONFIGURACIÓN.
     * No toca Product, lotes, movimientos, checkpoints ni estadísticas.
     *
     * Si se borra un grupo conjunto, se borran también las configuraciones de equilibrio
     * que dependían de ese grupo para evitar referencias rotas.
     */
    suspend fun deleteConfig(configId: String) {
        val snapshot = firestore.collection(COLLECTION).get().await()
        val batch = firestore.batch()

        val target = firestore.collection(COLLECTION).document(configId)
        batch.delete(target)

        snapshot.documents.forEach { doc ->
            if (doc.id != configId && doc.getString("linkedGroupId") == configId) {
                batch.delete(doc.reference)
            }
        }

        batch.commit().await()
    }

    private suspend fun validateNoDuplicateJointMembership(
        currentGroupId: String?,
        requestedProductIds: List<String>
    ) {
        val requested = requestedProductIds.toSet()
        val bundle = loadAllConfig()

        val conflict = bundle.groups
            .filter { it.id != currentGroupId }
            .firstOrNull { group -> group.memberProductIds.any { requested.contains(it) } }

        if (conflict != null) {
            val duplicated = conflict.memberProductIds.first { requested.contains(it) }
            throw IllegalStateException(
                "Un producto sólo puede pertenecer a un grupo de cobertura conjunta. " +
                    "Ya existe en “${conflict.name}” (ID de producto: ${duplicated.takeLast(6)})."
            )
        }
    }

    private suspend fun loadAllConfig(): PredictiveV3ConfigRepository.ConfigBundle {
        val snapshot = firestore.collection(COLLECTION).get().await()
        val groups = mutableListOf<PredictiveGroupConfig>()
        val services = mutableListOf<PredictiveServiceRelation>()

        snapshot.documents.forEach { doc ->
            val type = doc.getString("configType")?.uppercase() ?: "GROUP"

            if (type == "SERVICE" || type == "BALANCE") {
                val legacyAnchor = doc.getString("anchorProductId").orEmpty()
                val anchors = ((doc.get("anchorProductIds") as? List<*>)
                    ?.mapNotNull { it as? String }
                    .orEmpty() + listOf(legacyAnchor))
                    .filter { it.isNotBlank() }
                    .distinct()

                services += PredictiveServiceRelation(
                    id = doc.id,
                    name = doc.getString("name") ?: doc.id,
                    anchorProductId = legacyAnchor.ifBlank { anchors.firstOrNull().orEmpty() },
                    anchorProductIds = anchors,
                    historicalAnchorProductIds = (doc.get("historicalAnchorProductIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        .orEmpty(),
                    primaryAnchorProductId = doc.getString("primaryAnchorProductId"),
                    linkedGroupId = doc.getString("linkedGroupId").orEmpty(),
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
                            priorityWeight = (map["priorityWeight"] as? Number)?.toDouble() ?: NORMAL_WEIGHT,
                            enabled = (map["enabled"] as? Boolean) ?: true
                        )
                    }
                    .orEmpty()

                groups += PredictiveGroupConfig(
                    id = doc.id,
                    name = doc.getString("name") ?: doc.id,
                    memberProductIds = (doc.get("memberProductIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        .orEmpty(),
                    historicalProductIds = (doc.get("historicalProductIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        .orEmpty(),
                    memberRules = rules,
                    primaryProductId = doc.getString("primaryProductId"),
                    secondaryProductIds = (doc.get("secondaryProductIds") as? List<*>)
                        ?.mapNotNull { it as? String }
                        .orEmpty(),
                    enabled = doc.getBoolean("enabled") ?: true
                )
            }
        }

        return PredictiveV3ConfigRepository.ConfigBundle(groups, services)
    }
}
