package com.cesar.bocana.predictive.v3.data

import android.util.Log
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.predictive.v3.model.GroupMemberRule
import com.cesar.bocana.predictive.v3.model.PredictiveGroupConfig
import com.cesar.bocana.predictive.v3.model.PredictiveServiceRelation
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.Normalizer

class PredictiveV3ConfigRepository(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val TAG = "PredictivoV3Config"
        const val COLLECTION = "predictive_groups"
        const val DEV_PROJECT_ID = "testserver-89"
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
            if (type == "SERVICE") {
                services += PredictiveServiceRelation(
                    id = doc.id,
                    name = doc.getString("name") ?: doc.id,
                    anchorProductId = doc.getString("anchorProductId") ?: "",
                    linkedGroupId = doc.getString("linkedGroupId") ?: "",
                    preferredGroupProductId = doc.getString("preferredGroupProductId"),
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
                        .orEmpty(),
                    memberRules = rules,
                    enabled = doc.getBoolean("enabled") ?: true
                )
            }
        }
        return ConfigBundle(groups.filter { it.enabled }, services.filter { it.enabled })
    }

    /**
     * Bootstrap SOLO DEV. No adivina productos: exige coincidencia exacta tras normalizar acentos.
     * Si falta un integrante, NO crea ese grupo parcialmente.
     */
    suspend fun ensureInitialDevConfigIfMissing(
        projectId: String?,
        products: List<Product>
    ) {
        if (projectId != DEV_PROJECT_ID) return

        val existing = firestore.collection(COLLECTION).limit(1).get().await()
        if (!existing.isEmpty) return

        val byName = products.associateBy { normalize(it.name) }
        fun idOf(name: String): String? = byName[normalize(name)]?.id

        val ho = idOf("HO")
        val hm = idOf("HM")
        val ro = idOf("RO")
        val rm = idOf("RM")
        val vj = idOf("VJ")
        val lengua = idOf("LENGUA")
        val curvina = idOf("CURVINA")
        val robalo = idOf("ROBALO")

        val batch = firestore.batch()
        var writes = 0

        if (listOf(ho, hm, ro, rm, vj).all { !it.isNullOrBlank() }) {
            val ids = listOf(ho!!, hm!!, ro!!, rm!!, vj!!)
            val ref = firestore.collection(COLLECTION).document("PARGOS")
            batch.set(ref, mapOf(
                "configType" to "GROUP",
                "name" to "Pargos / Huachinangos",
                "memberProductIds" to ids,
                "memberRules" to listOf(
                    mapOf("productId" to ho, "priorityWeight" to 1.35, "enabled" to true),
                    mapOf("productId" to hm, "priorityWeight" to 1.10, "enabled" to true),
                    mapOf("productId" to ro, "priorityWeight" to 0.85, "enabled" to true),
                    mapOf("productId" to rm, "priorityWeight" to 0.85, "enabled" to true),
                    mapOf("productId" to vj, "priorityWeight" to 0.80, "enabled" to true)
                ),
                "enabled" to true
            ))
            writes++
        } else {
            Log.w(TAG, "No se creó PARGOS: faltan nombres exactos HO/HM/RO/RM/VJ")
        }

        if (!lengua.isNullOrBlank() && !curvina.isNullOrBlank()) {
            val ref = firestore.collection(COLLECTION).document("FILETE")
            batch.set(ref, mapOf(
                "configType" to "GROUP",
                "name" to "Filetes",
                "memberProductIds" to listOf(lengua, curvina),
                "memberRules" to listOf(
                    mapOf("productId" to lengua, "priorityWeight" to 1.0, "enabled" to true),
                    mapOf("productId" to curvina, "priorityWeight" to 1.0, "enabled" to true)
                ),
                "enabled" to true
            ))
            writes++
        } else {
            Log.w(TAG, "No se creó FILETE: faltan nombres exactos LENGUA/CURVINA")
        }

        if (!robalo.isNullOrBlank() && !ho.isNullOrBlank() && listOf(ho, hm, ro, rm, vj).all { !it.isNullOrBlank() }) {
            val ref = firestore.collection(COLLECTION).document("SERVICIO_ROBALO_PARGOS")
            batch.set(ref, mapOf(
                "configType" to "SERVICE",
                "name" to "Servicio Róbalo / Pargos",
                "anchorProductId" to robalo,
                "linkedGroupId" to "PARGOS",
                "preferredGroupProductId" to ho,
                "enabled" to true
            ))
            writes++
        } else {
            Log.w(TAG, "No se creó SERVICIO_ROBALO_PARGOS: falta ROBALO o PARGOS")
        }

        if (writes > 0) {
            batch.commit().await()
            Log.d(TAG, "Bootstrap DEV creado con $writes configuraciones.")
        }
    }

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .uppercase()
    }
}
