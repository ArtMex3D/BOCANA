package com.cesar.bocana.predictive.v3

import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.predictive.v3.data.PredictiveV3Time
import com.cesar.bocana.util.StockQuantityPolicy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Diagnóstico puro del planificador.
 *
 * NO modifica sugerencias, inventario, Room ni Firestore.
 * Su objetivo es que una sugerencia extraña deje de ser una "caja negra".
 */
object TransferPlannerDiagnostics {

    const val LOG_TAG = "BocanaTraspasoTrace"

    data class Result(
        val text: String,
        val warnings: List<String>
    )

    data class HistoryTrace(
        val source: String,
        val checkpointCount: Int,
        val requestedProductCount: Int,
        val requestedWeekCount: Int,
        val loadMillis: Long = 0L,
        val missingLocalPredictions: Int = 0
    )

    fun inspect(
        snapshot: PredictiveTransferPlannerV3.Snapshot,
        plan: TransferPlanV3,
        history: HistoryTrace? = null
    ): Result {
        val productsById = snapshot.products.associateBy { it.id }
        val groupsById = snapshot.groups.associateBy { it.id }

        val packagedByProduct = snapshot.openLots
            .asSequence()
            .filter {
                it.location == Location.MATRIZ &&
                    it.isPackaged &&
                    !it.isDepleted &&
                    it.estadoTraspaso == null &&
                    StockQuantityPolicy.isUsable(it.currentQuantity)
            }
            .groupBy { it.productId }
            .mapValues { (_, lots) -> lots.sortedBy { effectiveDate(it)?.time ?: Long.MAX_VALUE } }

        val warnings = mutableListOf<String>()
        val out = StringBuilder()

        val regime = PredictiveV3Time.regime(snapshot.now)
        val seasonalWeeks = PredictiveV3Time.seasonalWeeks(snapshot.now)

        out.appendLine("=== BOCANA · TRAZA DE TRASPASOS ===")
        out.appendLine("Fecha cálculo: ${dateTime(snapshot.now)}")
        out.appendLine(
            "Temporada: ${regime.name} | referencia: " +
                seasonalWeeks.joinToString { it.key }
        )
        out.appendLine("Grupos cargados: ${snapshot.groups.size} | relaciones de apoyo: ${snapshot.services.size}")
        history?.let {
            out.appendLine(
                "Historial: fuente=${it.source} | registros=${it.checkpointCount} | " +
                    "productosSolicitados=${it.requestedProductCount} | semanasSolicitadas=${it.requestedWeekCount} | " +
                    "carga=${it.loadMillis}ms | faltantesLocal=${it.missingLocalPredictions}"
            )
        }
        out.appendLine()

        snapshot.groups.forEach { group ->
            val memberIds = group.memberProductIds.toSet()

            if (memberIds.isEmpty()) {
                warnings += "Grupo '${group.name}' (${group.id}) no tiene miembros."
            }
            if (group.primaryProductId != null && !memberIds.contains(group.primaryProductId)) {
                warnings += "Grupo '${group.name}': el rector/principal no pertenece a memberProductIds."
            }

            val invalidSecondaries = group.secondaryProductIds.filterNot(memberIds::contains)
            if (invalidSecondaries.isNotEmpty()) {
                warnings += "Grupo '${group.name}': secundarios fuera del grupo: ${invalidSecondaries.joinToString()}."
            }

            val missingProducts = group.memberProductIds.filterNot(productsById::containsKey)
            if (missingProducts.isNotEmpty()) {
                warnings += "Grupo '${group.name}': IDs de producto no disponibles/activos: ${missingProducts.joinToString()}."
            }
        }

        snapshot.services.forEach { relation ->
            val linked = groupsById[relation.linkedGroupId]
            if (linked == null) {
                warnings += "Relación '${relation.name}' apunta a grupo inexistente '${relation.linkedGroupId}'."
            } else {
                val preferred = relation.preferredGroupProductId
                if (preferred != null && !linked.memberProductIds.contains(preferred)) {
                    warnings += "Relación '${relation.name}': apoyo preferido no pertenece al grupo '${linked.name}'."
                }
            }

            val missingAnchors = relation.effectiveAnchorProductIds().filterNot(productsById::containsKey)
            if (missingAnchors.isNotEmpty()) {
                warnings += "Relación '${relation.name}': producto(s) directo(s) no disponible(s): ${missingAnchors.joinToString()}."
            }
        }

        snapshot.groups.filter { it.enabled }.forEach { group ->
            val gp = plan.groups[group.id]
            val members = group.memberProductIds.mapNotNull(productsById::get)
            val relation = snapshot.services.firstOrNull { it.enabled && it.linkedGroupId == group.id }

            out.appendLine("GRUPO ${group.name} [${group.id}]")
            out.appendLine(
                "  config: miembros=${group.memberProductIds.joinToString()} | " +
                    "principal=${group.primaryProductId ?: "-"} | " +
                    "secundarios=${group.secondaryProductIds.joinToString().ifBlank { "-" }} | " +
                    "repartoMismaFecha=${group.balanceSameReceivedDate}"
            )
            out.appendLine(
                "  objetivo: habitual=${f(gp?.habitualTargetKg)} | crudo=${f(gp?.rawDynamicTargetKg)} | " +
                    "baseProtegida=${f(gp?.baseOperationalTargetKg)} | apoyoCalc=${f(gp?.serviceSupportCandidateKg)} | " +
                    "apoyoAplicado=${f(gp?.serviceSupportExtraKg)} | operativo=${f(gp?.dynamicTargetKg)} | C04=${f(gp?.currentC04Kg)} | " +
                    "pedir=${f(gp?.requestedTransferKg)} | asignado=${f(gp?.allocatedIntentKg)} | " +
                    "pendiente=${f(gp?.remainingKg)}"
            )
            out.appendLine(
                "  predicción: base=${f(gp?.baselineWeeklyKg)} kg/sem | " +
                    "forecast=${f(gp?.forecastWeeklyKg)} | efectivo=${f(gp?.effectiveWeeklyKg)} | " +
                    "estacional=${fNullable(gp?.seasonalReferenceWeeklyKg)} | régimen=${gp?.regimeName ?: "-"}"
            )

            if (relation != null) {
                out.appendLine(
                    "  apoyo: id=${relation.id} | directo=${relation.effectiveAnchorProductIds().joinToString()} | " +
                        "preferido=${relation.preferredGroupProductId ?: "-"} | " +
                        "déficitDirectoResidual=${f(gp?.serviceAnchorResidualKg)} | " +
                        "apoyoCalc=${f(gp?.serviceSupportCandidateKg)} | " +
                        "apoyoAplicado=${f(gp?.serviceSupportExtraKg)} | inverso=${f(gp?.reverseSupportKg)}"
                )
            }

            out.appendLine(
                "  políticaReparto=" +
                    if (group.primaryProductId != null) {
                        "RECTOR_GUIA+PEPS_ATRAS_O_IGUAL"
                    } else if (group.balanceSameReceivedDate) {
                        "EQUIVALENTES+MISMA_FECHA"
                    } else {
                        "EQUIVALENTES+COHORTE"
                    }
            )
            if (!gp?.allocationTrace.isNullOrEmpty()) {
                gp?.allocationTrace.orEmpty().forEach { step ->
                    out.appendLine("    decisión: $step")
                }
            }

            val memberRows = members.map { product ->
                val lots = packagedByProduct[product.id].orEmpty()
                val packagedKg = lots.sumOf { it.currentQuantity.coerceAtLeast(0.0) }
                val predictiveReserve = matrixReserveForCommitment(product)
                // FASE 2: minStock ya no bloquea Matriz -> C04.
                val usable = min(packagedKg, product.stockMatriz.coerceAtLeast(0.0))
                val oldest = lots.firstOrNull()?.let(::effectiveDate)
                val pp = plan.products[product.id]

                val role = when {
                    product.id == group.primaryProductId -> "PRINCIPAL"
                    group.secondaryProductIds.contains(product.id) -> "SECUNDARIO"
                    else -> "MIEMBRO"
                }

                MemberRow(
                    product = product,
                    role = role,
                    packagedKg = packagedKg,
                    reserveKg = predictiveReserve,
                    usableKg = usable,
                    oldestDate = oldest,
                    suggestedKg = pp?.requestedKg ?: 0.0,
                    reasonCodes = pp?.reasonCodes.orEmpty()
                )
            }

            memberRows.forEach { row ->
                out.appendLine(
                    "  ${row.role} ${row.product.name} [${row.product.id}] | " +
                        "C04=${f(row.product.stockCongelador04)} | Matriz=${f(row.product.stockMatriz)} | " +
                        "minGeneral=${f(row.product.minStock)} | empacado=${f(row.packagedKg)} | " +
                        "reservaPredictiva=${f(row.reserveKg)} | transferible=${f(row.usableKg)} | " +
                        "PEPS=${day(row.oldestDate)} | sugerido=${f(row.suggestedKg)} | " +
                        "codes=${row.reasonCodes.joinToString().ifBlank { "-" }}"
                )

                val lotTrace = packagedByProduct[row.product.id].orEmpty()
                    .take(4)
                    .joinToString(" ; ") { lot ->
                        "${lot.id.takeLast(6)} kg=${f(lot.currentQuantity)} " +
                            "rec=${day(lot.receivedAt)} orig=${day(lot.originalReceivedAt)} eff=${day(effectiveDate(lot))}"
                    }
                if (lotTrace.isNotBlank()) {
                    out.appendLine("    lotes: $lotTrace")
                }
            }

            val eligible = memberRows.filter { it.usableKg > 0.01 && it.oldestDate != null }
            val earliest = eligible.minByOrNull { dayStartMillis(it.oldestDate!!) }
            if (earliest != null && group.primaryProductId == null) {
                val earliestDay = dayStartMillis(earliest.oldestDate!!)
                val earliestRows = eligible.filter { dayStartMillis(it.oldestDate!!) == earliestDay }
                val earliestSuggested = earliestRows.sumOf { it.suggestedKg }
                val laterSuggested = eligible
                    .filter { dayStartMillis(it.oldestDate!!) > earliestDay }
                    .sumOf { it.suggestedKg }

                out.appendLine(
                    "  PEPS cabeza: ${day(earliest.oldestDate)} -> " +
                        earliestRows.joinToString { "${it.product.name} usable=${f(it.usableKg)} sugerido=${f(it.suggestedKg)}" }
                )

                if (laterSuggested > 0.01 &&
                    earliestRows.sumOf { it.usableKg } > earliestSuggested + 0.01
                ) {
                    warnings +=
                        "Posible salto PEPS en '${group.name}': hay mercancía transferible del ${day(earliest.oldestDate)} " +
                        "sin usar, pero el plan asignó ${f(laterSuggested)} kg a fecha(s) posteriores."
                }
            }

            if (group.balanceSameReceivedDate) {
                val days = eligible.groupBy { dayStartMillis(it.oldestDate!!) }
                val shared = days.entries.firstOrNull { it.value.size >= 2 }
                if (shared != null) {
                    out.appendLine(
                        "  mismaFechaDetectada=${day(Date(shared.key))} -> " +
                            shared.value.joinToString { it.product.name }
                    )
                } else {
                    out.appendLine("  mismaFechaDetectada=NO entre los lotes PEPS transferibles actuales")
                }
            }

            out.appendLine()
        }

        val groupedIds = snapshot.groups.flatMap { it.memberProductIds }.toSet()
        snapshot.products.filterNot { groupedIds.contains(it.id) }.forEach { product ->
            val pp = plan.products[product.id] ?: return@forEach
            val lots = packagedByProduct[product.id].orEmpty()
            val packagedKg = lots.sumOf { it.currentQuantity.coerceAtLeast(0.0) }
            val transferable = min(packagedKg, product.stockMatriz.coerceAtLeast(0.0))
            val oldest = lots.firstOrNull()?.let(::effectiveDate)

            out.appendLine(
                "INDIVIDUAL ${product.name} [${product.id}] | " +
                    "C04=${f(product.stockCongelador04)} | Matriz=${f(product.stockMatriz)} | min=${f(product.minStock)} | " +
                    "empacado=${f(packagedKg)} | transferible=${f(transferable)} | PEPS=${day(oldest)} | " +
                    "objetivo=${f(pp.dynamicTargetC04Kg)} | crudo=${f(pp.rawDynamicTargetC04Kg)} | " +
                    "apoyo=${f(pp.serviceSupportExtraKg)} | sugerido=${f(pp.requestedKg)} | " +
                    "base=${f(pp.baselineWeeklyKg)} | forecast=${f(pp.forecastWeeklyKg)} | " +
                    "estacional=${fNullable(pp.seasonalReferenceWeeklyKg)} | régimen=${pp.regimeName}"
            )
        }

        if (warnings.isNotEmpty()) {
            out.appendLine()
            out.appendLine("=== ADVERTENCIAS DIAGNÓSTICAS ===")
            warnings.distinct().forEach { out.appendLine("!! $it") }
        }

        return Result(out.toString().trim(), warnings.distinct())
    }

    private data class MemberRow(
        val product: Product,
        val role: String,
        val packagedKg: Double,
        val reserveKg: Double,
        val usableKg: Double,
        val oldestDate: Date?,
        val suggestedKg: Double,
        val reasonCodes: List<TransferReasonCode>
    )

    private fun matrixReserveForCommitment(product: Product): Double =
        max(0.0, product.minStock - product.stockCongelador04)
            .coerceAtMost(product.stockMatriz.coerceAtLeast(0.0))

    private fun effectiveDate(lot: StockLot): Date? =
        listOfNotNull(lot.receivedAt, lot.originalReceivedAt)
            .minByOrNull { it.time }

    private fun dayStartMillis(date: Date): Long {
        val cal = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun day(date: Date?): String =
        date?.let { SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(it) } ?: "-"

    private fun dateTime(date: Date): String =
        SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.getDefault()).format(date)

    private fun f(value: Double?): String =
        String.format(Locale.getDefault(), "%.1f", value ?: 0.0)

    private fun fNullable(value: Double?): String =
        value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "-"
}
