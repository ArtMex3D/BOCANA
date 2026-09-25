package com.cesar.bocana.ui.printing

import android.content.Context
import android.util.Log
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.ui.printing.pdf.PdfGenerator
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.UUID

object ActiveLabelStore {
    private const val TAG = "ActiveLabelStore"
    private const val PREFS = "active_labels_v2"
    private const val KEY = "records"
    private const val COLLECTION = "generated_labels"
    private const val LIFE_MS = 24L * 60L * 60L * 1000L

    fun list(context: Context): List<ActiveLabelRecord> {
        cleanupLocal(context)
        return readAll(context).map(::normalizeRecord).sortedByDescending { it.createdAtMillis }
    }

    fun get(context: Context, id: String): ActiveLabelRecord? {
        cleanupLocal(context)
        return readAll(context).firstOrNull { it.id == id }?.let(::normalizeRecord)
    }

    /**
     * Sincroniza Etiquetas activas con Firestore.
     * - Reintenta etiquetas nuevas pendientes de subir.
     * - Elimina registros remotos vencidos.
     * - Descarga las recetas activas de otros teléfonos.
     * - Reconstruye el PDF una sola vez y lo conserva en filesDir/active_labels.
     *
     * Si no hay conexión, devuelve las etiquetas locales sin bloquear la pantalla.
     */
    suspend fun syncActiveLabels(context: Context): List<ActiveLabelRecord> {
        cleanupLocal(context)

        // Reintenta primero cualquier etiqueta creada sin conexión.
        readAll(context)
            .filter { it.remoteManaged && it.syncPending && it.expiresAtMillis > System.currentTimeMillis() }
            .forEach { syncRecordToFirestore(context, it.id) }

        return runCatching {
            cleanupExpiredRemote()

            val now = System.currentTimeMillis()
            val snapshot = Firebase.firestore.collection(COLLECTION)
                .whereGreaterThan("expiresAt", Date(now))
                .get()
                .await()

            val remoteRecords = snapshot.documents.mapNotNull { doc ->
                val flow = doc.getString("flowType")
                    ?.let { runCatching { LabelFlowType.valueOf(it) }.getOrNull() }
                    ?: return@mapNotNull null

                val expires = doc.getTimestamp("expiresAt")?.toDate()?.time
                    ?: doc.getLong("expiresAtMillis")
                    ?: return@mapNotNull null

                if (expires <= now) return@mapNotNull null

                ActiveLabelRecord(
                    id = doc.id,
                    createdAtMillis = doc.getLong("createdAtMillis") ?: now,
                    expiresAtMillis = expires,
                    title = doc.getString("title").orEmpty(),
                    typeLabel = doc.getString("typeLabel").orEmpty(),
                    summary = doc.getString("summary").orEmpty(),
                    pdfPath = "",
                    flowType = flow,
                    templateDescription = doc.getString("templateDescription").orEmpty(),
                    payloadJson = doc.getString("payloadJson").orEmpty(),
                    remoteManaged = true,
                    syncPending = false
                )
            }

            val remoteIds = remoteRecords.mapTo(hashSetOf()) { it.id }
            var local = readAll(context)

            // Si un registro sincronizado fue eliminado desde otro teléfono, desaparece aquí también.
            val removedRemotely = local.filter {
                it.remoteManaged && !it.syncPending && it.id !in remoteIds
            }
            removedRemotely.forEach { runCatching { File(it.pdfPath).delete() } }
            if (removedRemotely.isNotEmpty()) {
                local = local.filterNot { record -> removedRemotely.any { it.id == record.id } }
                writeAll(context, local)
            }

            remoteRecords.forEach { remote ->
                val current = readAll(context).firstOrNull { it.id == remote.id }
                val sameRecipe = current != null &&
                    current.payloadJson == remote.payloadJson &&
                    current.templateDescription == remote.templateDescription &&
                    current.flowType == remote.flowType &&
                    current.expiresAtMillis == remote.expiresAtMillis &&
                    File(current.pdfPath).exists()

                if (sameRecipe) {
                    replaceRecord(
                        context,
                        current!!.copy(
                            title = remote.title,
                            typeLabel = remote.typeLabel,
                            summary = remote.summary,
                            remoteManaged = true,
                            syncPending = false
                        )
                    )
                } else {
                    rebuildRemotePdf(context, remote)
                }
            }

            list(context)
        }.onFailure {
            Log.w(TAG, "No se pudo sincronizar Etiquetas activas; se conserva la copia local.", it)
        }.getOrElse {
            list(context)
        }
    }

    /** Sube o actualiza una sola receta. Si falla, queda marcada para reintento posterior. */
    suspend fun syncRecordToFirestore(context: Context, id: String): Boolean {
        val record = readAll(context).firstOrNull { it.id == id } ?: return false
        if (!record.remoteManaged || record.expiresAtMillis <= System.currentTimeMillis()) return false

        return runCatching {
            val user = Firebase.auth.currentUser
            val data = hashMapOf<String, Any?>(
                "title" to record.title,
                "typeLabel" to record.typeLabel,
                "summary" to record.summary,
                "flowType" to record.flowType.name,
                "templateDescription" to record.templateDescription,
                "payloadJson" to record.payloadJson,
                "createdAtMillis" to record.createdAtMillis,
                "expiresAtMillis" to record.expiresAtMillis,
                // Timestamp real: permite activar TTL nativo de Firestore sobre este campo.
                "expiresAt" to Date(record.expiresAtMillis),
                "updatedAt" to FieldValue.serverTimestamp(),
                "updatedBy" to (user?.displayName ?: user?.email ?: "")
            )

            Firebase.firestore.collection(COLLECTION)
                .document(record.id)
                .set(data, SetOptions.merge())
                .await()

            replaceRecord(context, record.copy(syncPending = false, remoteManaged = true))
            true
        }.onFailure {
            Log.w(TAG, "Etiqueta ${record.id} quedó pendiente de sincronizar.", it)
        }.getOrDefault(false)
    }

    suspend fun deleteEverywhere(context: Context, id: String): Boolean {
        val record = readAll(context).firstOrNull { it.id == id }
        if (record == null) return true

        // Etiquetas antiguas exclusivamente locales no necesitan operación remota.
        if (!record.remoteManaged) {
            delete(context, id)
            return true
        }

        return runCatching {
            Firebase.firestore.collection(COLLECTION).document(id).delete().await()
            delete(context, id)
            true
        }.onFailure {
            Log.w(TAG, "No se pudo eliminar la etiqueta compartida $id.", it)
        }.getOrDefault(false)
    }

    private fun normalizeRecord(record: ActiveLabelRecord): ActiveLabelRecord {
        val label = when (record.typeLabel) {
            "Avanzada fija" -> "Cajas · idénticas"
            "Avanzada variable" -> "Cajas · variables"
            else -> record.typeLabel
        }
        return if (label == record.typeLabel) record else record.copy(typeLabel = label)
    }

    fun saveSingle(
        context: Context,
        sourcePdf: File,
        flowType: LabelFlowType,
        template: LabelTemplate,
        data: LabelData,
        replaceId: String? = null
    ): ActiveLabelRecord {
        val title = when (flowType) {
            LabelFlowType.SIMPLE -> data.supplierName?.takeIf { it.isNotBlank() } ?: "Etiqueta simple"
            LabelFlowType.COSTAL, LabelFlowType.FIXED_DETAILED -> data.productName?.takeIf { it.isNotBlank() } ?: "Etiqueta"
            LabelFlowType.VARIABLE_DETAILED -> data.productName?.takeIf { it.isNotBlank() } ?: "Etiquetas"
        }
        val typeLabel = when (flowType) {
            LabelFlowType.SIMPLE -> "Simple"
            LabelFlowType.COSTAL -> "Costales"
            LabelFlowType.FIXED_DETAILED -> "Cajas · idénticas"
            LabelFlowType.VARIABLE_DETAILED -> "Cajas · variables"
        }
        val summary = listOfNotNull(
            data.supplierName?.takeIf { it.isNotBlank() },
            data.productName?.takeIf { it.isNotBlank() && flowType == LabelFlowType.SIMPLE }
        ).firstOrNull().orEmpty()
        return persistNewLocal(
            context = context,
            sourcePdf = sourcePdf,
            id = replaceId ?: UUID.randomUUID().toString(),
            title = title,
            typeLabel = typeLabel,
            summary = summary,
            flowType = flowType,
            templateDescription = template.description,
            payloadJson = encodeLabelData(data)
        )
    }

    fun saveMulti(
        context: Context,
        sourcePdf: File,
        template: LabelTemplate,
        configs: List<IndividualLabelConfig?>,
        replaceId: String? = null
    ): ActiveLabelRecord {
        val names = configs.filterNotNull().map { it.product.name }.filter { it.isNotBlank() }.distinct()
        val title = names.firstOrNull() ?: "Etiquetas variables"
        val summary = when {
            names.size <= 1 -> "${configs.count { it != null }} configuradas"
            names.size == 2 -> names.joinToString(" · ")
            else -> "${names.take(2).joinToString(" · ")} · +${names.size - 2}"
        }
        return persistNewLocal(
            context = context,
            sourcePdf = sourcePdf,
            id = replaceId ?: UUID.randomUUID().toString(),
            title = title,
            typeLabel = "Cajas · variables",
            summary = summary,
            flowType = LabelFlowType.VARIABLE_DETAILED,
            templateDescription = template.description,
            payloadJson = encodeConfigs(configs)
        )
    }

    /** Borrado local puro; deleteEverywhere es el usado por la UI compartida. */
    fun delete(context: Context, id: String) {
        val current = readAll(context)
        current.firstOrNull { it.id == id }?.let { runCatching { File(it.pdfPath).delete() } }
        writeAll(context, current.filterNot { it.id == id })
    }

    fun decodeLabelData(record: ActiveLabelRecord): LabelData? = runCatching {
        val o = JSONObject(record.payloadJson)
        LabelData(
            labelType = LabelType.valueOf(o.getString("labelType")),
            productId = o.optString("productId").ifBlank { null },
            productName = o.optString("productName").ifBlank { null },
            supplierName = o.optString("supplierName").ifBlank { null },
            date = Date(o.getLong("date")),
            weight = o.optString("weight").ifBlank { null },
            unit = o.optString("unit").ifBlank { null },
            detail = o.optString("detail").ifBlank { null }
        )
    }.getOrNull()

    fun decodeConfigs(record: ActiveLabelRecord): MutableList<IndividualLabelConfig?> = runCatching {
        val array = JSONArray(record.payloadJson)
        MutableList<IndividualLabelConfig?>(array.length()) { index ->
            if (array.isNull(index)) null else {
                val o = array.getJSONObject(index)
                val product = Product(
                    id = o.optString("productId"),
                    name = o.optString("productName"),
                    unit = o.optString("productUnit", "Kg")
                )
                IndividualLabelConfig(
                    product = product,
                    supplierName = o.optString("supplierName"),
                    date = Date(o.getLong("date")),
                    weight = o.optString("weight").ifBlank { null },
                    unit = o.optString("unit"),
                    detail = o.optString("detail").ifBlank { null }
                )
            }
        }
    }.getOrElse { mutableListOf() }

    private fun persistNewLocal(
        context: Context,
        sourcePdf: File,
        id: String,
        title: String,
        typeLabel: String,
        summary: String,
        flowType: LabelFlowType,
        templateDescription: String,
        payloadJson: String
    ): ActiveLabelRecord {
        cleanupLocal(context)
        val now = System.currentTimeMillis()
        val old = readAll(context).firstOrNull { it.id == id }
        val record = ActiveLabelRecord(
            id = id,
            createdAtMillis = old?.createdAtMillis ?: now,
            expiresAtMillis = now + LIFE_MS,
            title = title,
            typeLabel = typeLabel,
            summary = summary,
            pdfPath = localPdfFile(context, id).absolutePath,
            flowType = flowType,
            templateDescription = templateDescription,
            payloadJson = payloadJson,
            remoteManaged = true,
            syncPending = true
        )
        copyPdfAndReplaceRecord(context, sourcePdf, record)
        return record
    }

    private suspend fun rebuildRemotePdf(context: Context, remote: ActiveLabelRecord) {
        val template = LabelTemplates.findByDescription(remote.templateDescription)
        if (template == null) {
            Log.w(TAG, "No se encontró plantilla '${remote.templateDescription}' para ${remote.id}.")
            return
        }

        val generated = when (remote.flowType) {
            LabelFlowType.VARIABLE_DETAILED -> {
                val configs = decodeConfigs(remote)
                PdfGenerator.createMultiLabelPdf(context, configs, template)
            }
            LabelFlowType.SIMPLE,
            LabelFlowType.COSTAL,
            LabelFlowType.FIXED_DETAILED -> {
                val data = decodeLabelData(remote) ?: return
                PdfGenerator.createSingleLabelPdf(context, data, template)
            }
        }

        copyPdfAndReplaceRecord(
            context,
            generated,
            remote.copy(
                pdfPath = localPdfFile(context, remote.id).absolutePath,
                remoteManaged = true,
                syncPending = false
            )
        )
        runCatching { generated.delete() }
    }

    private fun copyPdfAndReplaceRecord(context: Context, sourcePdf: File, record: ActiveLabelRecord) {
        val destination = localPdfFile(context, record.id)
        if (sourcePdf.absolutePath != destination.absolutePath) {
            sourcePdf.copyTo(destination, overwrite = true)
        }
        replaceRecord(context, record.copy(pdfPath = destination.absolutePath))
    }

    private fun localPdfFile(context: Context, id: String): File {
        val dir = File(context.filesDir, "active_labels").apply { mkdirs() }
        return File(dir, "$id.pdf")
    }

    private fun cleanupLocal(context: Context) {
        val now = System.currentTimeMillis()
        val all = readAll(context)
        val expired = all.filter { it.expiresAtMillis <= now }
        expired.forEach { runCatching { File(it.pdfPath).delete() } }

        // Un PDF faltante de una etiqueta remota NO elimina la receta: se reconstruye al sincronizar.
        val missingLocalOnly = all.filter {
            !it.remoteManaged && it.expiresAtMillis > now && !File(it.pdfPath).exists()
        }
        if (expired.isNotEmpty() || missingLocalOnly.isNotEmpty()) {
            val removeIds = (expired + missingLocalOnly).mapTo(hashSetOf()) { it.id }
            writeAll(context, all.filterNot { it.id in removeIds })
        }
    }

    private suspend fun cleanupExpiredRemote() {
        val expired = Firebase.firestore.collection(COLLECTION)
            .whereLessThanOrEqualTo("expiresAt", Date())
            .get()
            .await()

        expired.documents.chunked(400).forEach { chunk ->
            if (chunk.isEmpty()) return@forEach
            val batch = Firebase.firestore.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }

    private fun replaceRecord(context: Context, record: ActiveLabelRecord) {
        val next = readAll(context).filterNot { it.id == record.id } + record
        writeAll(context, next)
    }

    private fun readAll(context: Context): List<ActiveLabelRecord> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    ActiveLabelRecord(
                        id = o.getString("id"),
                        createdAtMillis = o.getLong("createdAt"),
                        expiresAtMillis = o.getLong("expiresAt"),
                        title = o.getString("title"),
                        typeLabel = o.getString("typeLabel"),
                        summary = o.optString("summary"),
                        pdfPath = o.getString("pdfPath"),
                        flowType = LabelFlowType.valueOf(o.getString("flowType")),
                        templateDescription = o.getString("templateDescription"),
                        payloadJson = o.getString("payloadJson"),
                        remoteManaged = o.optBoolean("remoteManaged", false),
                        syncPending = o.optBoolean("syncPending", false)
                    )
                )
            }
        }
    }.getOrElse { emptyList() }

    private fun writeAll(context: Context, records: List<ActiveLabelRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("createdAt", r.createdAtMillis)
                put("expiresAt", r.expiresAtMillis)
                put("title", r.title)
                put("typeLabel", r.typeLabel)
                put("summary", r.summary)
                put("pdfPath", r.pdfPath)
                put("flowType", r.flowType.name)
                put("templateDescription", r.templateDescription)
                put("payloadJson", r.payloadJson)
                put("remoteManaged", r.remoteManaged)
                put("syncPending", r.syncPending)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun encodeLabelData(data: LabelData): String = JSONObject().apply {
        put("labelType", data.labelType.name)
        put("productId", data.productId ?: "")
        put("productName", data.productName ?: "")
        put("supplierName", data.supplierName ?: "")
        put("date", data.date.time)
        put("weight", data.weight ?: "")
        put("unit", data.unit ?: "")
        put("detail", data.detail ?: "")
    }.toString()

    private fun encodeConfigs(configs: List<IndividualLabelConfig?>): String {
        val arr = JSONArray()
        configs.forEach { c ->
            if (c == null) arr.put(JSONObject.NULL) else arr.put(JSONObject().apply {
                put("productId", c.product.id)
                put("productName", c.product.name)
                put("productUnit", c.product.unit)
                put("supplierName", c.supplierName)
                put("date", c.date.time)
                put("weight", c.weight ?: "")
                put("unit", c.unit)
                put("detail", c.detail ?: "")
            })
        }
        return arr.toString()
    }
}
