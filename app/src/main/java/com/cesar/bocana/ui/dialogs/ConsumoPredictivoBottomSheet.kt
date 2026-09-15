package com.cesar.bocana.ui.dialogs

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.predictive.v3.PredictiveV3Coordinator
import com.cesar.bocana.util.PredictiveConsumptionEngine
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.card.MaterialCardView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class ConsumoPredictivoBottomSheet(private val product: Product) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ConsumoPredictivoBottomSheet"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_consumo_predictivo, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvProductName = view.findViewById<TextView>(R.id.tvPopupProductName)
        val tvProductId = view.findViewById<TextView>(R.id.tvPopupProductId)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClosePopup)

        val progressGauge = view.findViewById<ProgressBar>(R.id.progressGauge)
        val tvGaugeNumber = view.findViewById<TextView>(R.id.tvGaugeNumber)
        val tvGaugeText = view.findViewById<TextView>(R.id.tvGaugeText)
        val tvGaugeExtra = view.findViewById<TextView>(R.id.tvGaugeExtra)

        val cardStatusBadge = view.findViewById<MaterialCardView>(R.id.cardStatusBadge)
        val tvStatusBadge = view.findViewById<TextView>(R.id.tvStatusBadge)
        val tvMainPrediction = view.findViewById<TextView>(R.id.tvMainPrediction)
        val tvProbableRange = view.findViewById<TextView>(R.id.tvProbableRange)

        val tvForecastWeekly = view.findViewById<TextView>(R.id.tvForecastWeekly)
        val tvForecast30Days = view.findViewById<TextView>(R.id.tvForecast30Days)
        val tvAverageWeekly = view.findViewById<TextView>(R.id.tvAverageWeekly)
        val tvAverageMonthly = view.findViewById<TextView>(R.id.tvAverageMonthly)

        val cardHighDemandScenario = view.findViewById<MaterialCardView>(R.id.cardHighDemandScenario)
        val cardLowDemandScenario = view.findViewById<MaterialCardView>(R.id.cardLowDemandScenario)
        val tvHighDemandScenario = view.findViewById<TextView>(R.id.tvHighDemandScenario)
        val tvLowDemandScenario = view.findViewById<TextView>(R.id.tvLowDemandScenario)
        val tvSeasonInsight = view.findViewById<TextView>(R.id.tvSeasonInsight)

        tvProductName.text = product.name
        tvProductId.text = "ID: ${product.id.takeLast(6).uppercase(Locale.ROOT)}"
        btnClose.setOnClickListener { dismiss() }

        // SHADOW MODE V3: sólo aparece y consulta datos en el proyecto DEV.
        // No sustituye el V2 visible ni modifica inventario/traspasos.
        installV3DevShadow(view)

        val demandaSemanal = product.demandaSemanalPrevista
        val consumoPromedioSemanal = product.consumoSemanalPromedio

        tvForecastWeekly.text = formatQuantity(demandaSemanal, product.unit)
        tvForecast30Days.text = formatQuantity(demandaSemanal * 30.0 / 7.0, product.unit)

        if (consumoPromedioSemanal > 0.0) {
            tvAverageWeekly.text = formatQuantity(consumoPromedioSemanal, product.unit)
            tvAverageMonthly.text = formatQuantity(consumoPromedioSemanal * 4.0, product.unit)
        } else {
            tvAverageWeekly.text = "Sin dato reciente"
            tvAverageMonthly.text = "Sin dato reciente"
        }

        val diasPrincipales = PredictiveConsumptionEngine.coverageDays(
            stock = product.totalStock,
            weeklyDemand = demandaSemanal
        )

        if (diasPrincipales == null) {
            showNoDataState(
                progressGauge = progressGauge,
                tvGaugeNumber = tvGaugeNumber,
                tvGaugeText = tvGaugeText,
                tvGaugeExtra = tvGaugeExtra,
                cardStatusBadge = cardStatusBadge,
                tvStatusBadge = tvStatusBadge,
                tvMainPrediction = tvMainPrediction,
                tvProbableRange = tvProbableRange,
                cardHighDemandScenario = cardHighDemandScenario,
                cardLowDemandScenario = cardLowDemandScenario,
                tvSeasonInsight = tvSeasonInsight
            )
            return
        }

        val status = PredictiveConsumptionEngine.coverageStatus(diasPrincipales)
        val statusColor = Color.parseColor(status.colorHex)
        val badgeColor = Color.parseColor(status.badgeBackgroundHex)
        val gauge = PredictiveConsumptionEngine.gaugeDisplay(diasPrincipales)

        tvGaugeNumber.text = gauge.mainValue
        tvGaugeText.text = gauge.unitLabel
        tvGaugeExtra.text = gauge.extraLabel
        tvGaugeExtra.visibility = if (gauge.extraLabel.isBlank()) View.GONE else View.VISIBLE

        tvGaugeNumber.setTextColor(statusColor)
        tvGaugeText.setTextColor(statusColor)
        tvGaugeExtra.setTextColor(statusColor)
        progressGauge.progressDrawable.setTint(statusColor)
        progressGauge.progress = PredictiveConsumptionEngine.gaugeProgress(diasPrincipales)

        cardStatusBadge.setCardBackgroundColor(badgeColor)
        tvStatusBadge.setTextColor(statusColor)
        tvStatusBadge.text = when (status) {
            PredictiveConsumptionEngine.CoverageStatus.CRITICAL -> "⚠ ${status.label}"
            PredictiveConsumptionEngine.CoverageStatus.BUY_SOON -> "● ${status.label}"
            PredictiveConsumptionEngine.CoverageStatus.ATTENTION -> "● ${status.label}"
            PredictiveConsumptionEngine.CoverageStatus.HEALTHY -> "✓ ${status.label}"
            PredictiveConsumptionEngine.CoverageStatus.VERY_HEALTHY -> "✓ ${status.label}"
        }

        tvMainPrediction.text = if (diasPrincipales == 0) {
            "El stock está agotado"
        } else {
            "Se agotará en ${PredictiveConsumptionEngine.formatDuration(diasPrincipales)}"
        }

        val demandaAlta = product.demandaSemanalAlta.takeIf { it > 0.0 } ?: demandaSemanal
        val demandaBaja = product.demandaSemanalBaja.takeIf { it > 0.0 } ?: demandaSemanal

        val diasAltaDemanda = PredictiveConsumptionEngine.coverageDaysFloor(
            stock = product.totalStock,
            weeklyDemand = demandaAlta
        ) ?: diasPrincipales

        val diasBajaDemanda = PredictiveConsumptionEngine.coverageDaysCeil(
            stock = product.totalStock,
            weeklyDemand = demandaBaja
        ) ?: diasPrincipales

        val rangoMin = minOf(diasAltaDemanda, diasPrincipales, diasBajaDemanda)
        val rangoMax = maxOf(diasAltaDemanda, diasPrincipales, diasBajaDemanda)

        tvProbableRange.text =
            "${PredictiveConsumptionEngine.formatDuration(rangoMin)} a " +
                PredictiveConsumptionEngine.formatDuration(rangoMax)

        tvHighDemandScenario.text = if (diasAltaDemanda == 0) {
            "Con alta demanda, el stock puede agotarse hoy."
        } else {
            "Con alta demanda, podría agotarse en ${PredictiveConsumptionEngine.formatDuration(diasAltaDemanda)}."
        }

        tvLowDemandScenario.text = if (diasBajaDemanda == 0) {
            "Aunque baje el movimiento, el stock ya está agotado."
        } else {
            "Si baja el movimiento, podría alcanzar hasta ${PredictiveConsumptionEngine.formatDuration(diasBajaDemanda)}."
        }

        val currentReferences = PredictiveConsumptionEngine.buildReferences()
        tvSeasonInsight.text = when {
            product.forecastUsaEstacionalidad && currentReferences.isLentSeason ->
                "La previsión da más peso al patrón equivalente de Cuaresma y Semana Santa del año pasado."

            product.forecastUsaEstacionalidad ->
                "La previsión compara esta etapa del año con la etapa equivalente respecto a Semana Santa del año pasado."

            else ->
                "La previsión usa las semanas recientes disponibles; todavía no hay referencia estacional suficiente."
        }
    }

    private fun showNoDataState(
        progressGauge: ProgressBar,
        tvGaugeNumber: TextView,
        tvGaugeText: TextView,
        tvGaugeExtra: TextView,
        cardStatusBadge: MaterialCardView,
        tvStatusBadge: TextView,
        tvMainPrediction: TextView,
        tvProbableRange: TextView,
        cardHighDemandScenario: MaterialCardView,
        cardLowDemandScenario: MaterialCardView,
        tvSeasonInsight: TextView
    ) {
        val gray = Color.parseColor("#9E9E9E")

        tvGaugeNumber.text = "-"
        tvGaugeText.text = "SIN DATOS"
        tvGaugeExtra.visibility = View.GONE
        tvGaugeNumber.setTextColor(gray)
        tvGaugeText.setTextColor(gray)
        progressGauge.progressDrawable.setTint(gray)
        progressGauge.progress = 0

        cardStatusBadge.setCardBackgroundColor(Color.parseColor("#F5F5F5"))
        tvStatusBadge.setTextColor(gray)
        tvStatusBadge.text = "Sin consumo suficiente"
        tvMainPrediction.text = "Aún no se puede estimar cuándo se agotará"
        tvProbableRange.text = "Sin rango disponible"

        cardHighDemandScenario.visibility = View.GONE
        cardLowDemandScenario.visibility = View.GONE
        tvSeasonInsight.text =
            "Cuando existan semanas completas de consumo, Bocana calculará automáticamente la previsión."
    }

    private fun installV3DevShadow(rootView: View) {
        val projectId = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
        if (projectId != "testserver-89") return

        val container = when (rootView) {
            is LinearLayout -> rootView
            is ViewGroup -> (0 until rootView.childCount)
                .map { rootView.getChildAt(it) }
                .filterIsInstance<LinearLayout>()
                .firstOrNull()
            else -> null
        } ?: return

        val card = MaterialCardView(requireContext()).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#A78BFA")
            setCardBackgroundColor(Color.parseColor("#F5F3FF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
                bottomMargin = dp(18)
            }
        }

        val text = TextView(requireContext()).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(Color.parseColor("#312E81"))
            textSize = 13f
            text = "🧪 MOTOR V3 · DEV · SOLO ANÁLISIS\nConectando con datos reales..."
        }
        card.addView(text)
        container.addView(card)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val analysis = PredictiveV3Coordinator(FirebaseFirestore.getInstance())
                    .analyzeProduct(product.id)

                val operational = analysis.individualOperational
                val lines = mutableListOf<String>()
                lines += "🧪 MOTOR V3 · DEV · SOLO ANÁLISIS"
                lines += "Temporada: ${seasonLabel(analysis.regime)}"
                lines += "Cubierto por: ${formatWindowDays(analysis.targetWindowDays)}"
                lines += "Demanda estimada: ${format1(operational.effectiveWeeklyKg)} kg/semana"
                lines += "Cobertura total: ${analysis.effectiveCoverageDays?.let { format0(it) + " días" } ?: "sin dato"}"
                lines += "C04 actual: ${format1(analysis.selectedProduct.stockCongelador04)} kg"
                lines += "Objetivo C04: ${format1(operational.dynamicC04TargetKg)} kg"
                lines += "Traspaso sugerido: ${format1(operational.suggestedTransferKg)} kg"

                analysis.purchaseSignal?.let { purchase ->
                    lines += "Compra promedio: ${format1(purchase.averagePurchaseKg)} kg · ${purchase.purchaseCount} compras"
                    purchase.averageDaysBetweenPurchases?.let { days ->
                        lines += "Frecuencia de compra: cada ~${format1(days)} días"
                    }
                }

                analysis.transferSignal?.let { transfer ->
                    val a = transfer.averageKgWindowA?.let { "Lun/Mar ~${format1(it)} kg" }
                    val b = transfer.averageKgWindowB?.let { "Jue/Vie ~${format1(it)} kg" }
                    val parts = listOfNotNull(a, b)
                    if (parts.isNotEmpty()) lines += "Traspaso habitual a C04: ${parts.joinToString(" · ")}"
                }

                val group = analysis.groupAnalysis
                lines += if (group != null) {
                    "Pertenece a grupo: Sí · ${groupDisplayName(group.config.id, group.config.name)}"
                } else {
                    "Pertenece a grupo: No"
                }

                lines += ""
                lines += "— Referencias históricas —"
                val history = analysis.historicalReference
                lines += "Consumo hace un año:"
                lines += "  Semana: ${history?.weekOneYearAgoKg?.let { format1(it) + " kg" } ?: "sin dato"}"
                lines += "  Mes: ${history?.monthOneYearAgoKg?.let { format1(it) + " kg" } ?: "sin dato"}"

                group?.let { groupAnalysis ->
                    lines += ""
                    lines += "GRUPO ${groupDisplayName(groupAnalysis.config.id, groupAnalysis.config.name).uppercase(Locale.ROOT)}"
                    lines += "Demanda del grupo: ${format1(groupAnalysis.operational.effectiveWeeklyKg)} kg/semana"
                    lines += "Objetivo C04 grupo: ${format1(groupAnalysis.operational.dynamicC04TargetKg)} kg"
                    lines += "Traspaso sugerido grupo: ${format1(groupAnalysis.allocation.allocatedKg)} kg"
                    if (groupAnalysis.allocation.members.isNotEmpty()) {
                        lines += "Reparto sugerido:"
                        groupAnalysis.allocation.members.forEach { member ->
                            val name = groupAnalysis.memberNames[member.productId] ?: member.productId.takeLast(6)
                            lines += "  • $name: ${format1(member.suggestedKg)} kg"
                        }
                    }
                    if (groupAnalysis.allocation.unallocatedKg > 0.01) {
                        lines += "  ⚠ Pendiente por falta de disponibilidad/reserva: ${format1(groupAnalysis.allocation.unallocatedKg)} kg"
                    }
                }

                analysis.serviceAnalysis?.let { service ->
                    lines += ""
                    lines += "RELACIÓN ${service.anchorProductName.uppercase(Locale.ROOT)} ↔ ${service.linkedGroupName.uppercase(Locale.ROOT)}"
                    lines += "Demanda total del servicio: ${format1(service.allocation.totalWeeklyDemandKg)} kg/semana"
                    lines += "${service.anchorProductName}: ${format1(service.allocation.anchorWeeklyKg)} kg/semana"
                    lines += "${service.linkedGroupName}: ${format1(service.allocation.linkedGroupWeeklyKg)} kg/semana"
                    if (service.allocation.anchorWasRestrictedByStock) {
                        lines += "  ↳ El stock de ${service.anchorProductName} está trasladando parte de la demanda al grupo"
                    }
                }

                if (analysis.smartReasons.isNotEmpty()) {
                    lines += ""
                    lines += "— Avisos del motor —"
                    analysis.smartReasons.take(4).forEach { lines += "• $it" }
                }

                text.text = lines.joinToString("\n")
            } catch (e: Exception) {
                text.setTextColor(Color.parseColor("#991B1B"))
                text.text = "🧪 MOTOR V3 · DEV\nNo se pudo analizar: ${e.message}"
            }
        }
    }

    private fun seasonLabel(regime: com.cesar.bocana.predictive.v3.model.SeasonRegime): String = when (regime) {
        com.cesar.bocana.predictive.v3.model.SeasonRegime.NORMAL -> "Normal"
        com.cesar.bocana.predictive.v3.model.SeasonRegime.LENT -> "Cuaresma"
        com.cesar.bocana.predictive.v3.model.SeasonRegime.DECEMBER -> "Diciembre"
        com.cesar.bocana.predictive.v3.model.SeasonRegime.HOLIDAY -> "Festivo"
        com.cesar.bocana.predictive.v3.model.SeasonRegime.HIGH_SEASON -> "Temporada alta"
    }

    private fun formatWindowDays(days: Double): String {
        val rounded = days.roundToLong()
        return if (abs(days - rounded) < 0.05) "$rounded días" else "${format1(days)} días"
    }

    private fun groupDisplayName(id: String, configuredName: String): String = when (id.uppercase(Locale.ROOT)) {
        "FILETE" -> "Filetes"
        "PARGOS" -> "Pargos / Huachinangos"
        else -> configuredName
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun format1(value: Double): String =
        String.format(Locale.getDefault(), "%,.1f", value)

    private fun format0(value: Double): String =
        String.format(Locale.getDefault(), "%.0f", value)


    private fun formatQuantity(value: Double, unit: String): String {
        val normalized = if (abs(value) < 0.0001) 0.0 else value
        return if (abs(normalized - normalized.roundToLong()) < 0.05) {
            String.format(Locale.getDefault(), "%,d %s", normalized.roundToLong(), unit)
        } else {
            String.format(Locale.getDefault(), "%,.1f %s", normalized, unit)
        }
    }
}
