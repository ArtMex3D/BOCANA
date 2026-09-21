package com.cesar.bocana.ui.printing

import android.content.Context
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.data.model.Product
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.UUID

object ActiveLabelStore {
    private const val PREFS = "active_labels_v2"
    private const val KEY = "records"
    private const val LIFE_MS = 24L * 60L * 60L * 1000L

    fun list(context: Context): List<ActiveLabelRecord> {
        cleanup(context)
        return readAll(context).map(::normalizeRecord).sortedByDescending { it.createdAtMillis }
    }

    fun get(context: Context, id: String): ActiveLabelRecord? {
        cleanup(context)
        return readAll(context).firstOrNull { it.id == id }?.let(::normalizeRecord)
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
        return persist(
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
        return persist(
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

    private fun persist(
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
        cleanup(context)
        val dir = File(context.filesDir, "active_labels").apply { mkdirs() }
        val destination = File(dir, "$id.pdf")
        sourcePdf.copyTo(destination, overwrite = true)
        val now = System.currentTimeMillis()
        val old = readAll(context).firstOrNull { it.id == id }
        val record = ActiveLabelRecord(
            id = id,
            createdAtMillis = old?.createdAtMillis ?: now,
            expiresAtMillis = now + LIFE_MS,
            title = title,
            typeLabel = typeLabel,
            summary = summary,
            pdfPath = destination.absolutePath,
            flowType = flowType,
            templateDescription = templateDescription,
            payloadJson = payloadJson
        )
        val next = readAll(context).filterNot { it.id == id } + record
        writeAll(context, next)
        return record
    }

    private fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        val all = readAll(context)
        val expired = all.filter { it.expiresAtMillis <= now || !File(it.pdfPath).exists() }
        expired.forEach { runCatching { File(it.pdfPath).delete() } }
        if (expired.isNotEmpty()) writeAll(context, all - expired.toSet())
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
                        payloadJson = o.getString("payloadJson")
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
