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
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.predictive.v3.PredictiveV3Coordinator
import com.cesar.bocana.predictive.v3.model.BacktestSignal
import com.cesar.bocana.predictive.v3.model.ConfidenceLevel
import com.cesar.bocana.predictive.v3.model.GroupAnalysisV3
import com.cesar.bocana.predictive.v3.model.InventoryDeepSignal
import com.cesar.bocana.predictive.v3.model.LotInsight
import com.cesar.bocana.predictive.v3.model.MonthStockSummary
import com.cesar.bocana.predictive.v3.model.PredictiveV3Analysis
import com.cesar.bocana.predictive.v3.model.SeasonRegime
import com.cesar.bocana.util.PredictiveConsumptionEngine
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Popup predictivo V3.
 * Regla de oro: analiza y sugiere. NUNCA ejecuta compras, consumos ni traspasos.
 */
class ConsumoPredictivoBottomSheet(private val product: Product) : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ConsumoPredictivoBottomSheet"
        private const val DEV_PROJECT_ID = "testserver-89"
    }

    private lateinit var progressGauge: ProgressBar
    private lateinit var tvGaugeNumber: TextView
    private lateinit var tvGaugeText: TextView
    private lateinit var tvGaugeExtra: TextView
    private lateinit var cardStatusBadge: MaterialCardView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvMainPrediction: TextView
    private lateinit var tvProbableRange: TextView
    private lateinit var tvForecastWeekly: TextView
    private lateinit var tvForecast30Days: TextView
    private lateinit var tvAverageWeekly: TextView
    private lateinit var tvAverageMonthly: TextView
    private lateinit var cardHighDemandScenario: MaterialCardView
    private lateinit var cardLowDemandScenario: MaterialCardView
    private lateinit var tvHighDemandScenario: TextView
    private lateinit var tvLowDemandScenario: TextView
    private lateinit var tvSeasonInsight: TextView
    private lateinit var v3DeepContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_consumo_predictivo, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.tvPopupProductName).text = product.name
        view.findViewById<TextView>(R.id.tvPopupProductId).text = "ID: ${product.id.takeLast(6).uppercase(Locale.ROOT)}"
        view.findViewById<ImageButton>(R.id.btnClosePopup).setOnClickListener { dismiss() }

        progressGauge = view.findViewById(R.id.progressGauge)
        tvGaugeNumber = view.findViewById(R.id.tvGaugeNumber)
        tvGaugeText = view.findViewById(R.id.tvGaugeText)
        tvGaugeExtra = view.findViewById(R.id.tvGaugeExtra)
        cardStatusBadge = view.findViewById(R.id.cardStatusBadge)
        tvStatusBadge = view.findViewById(R.id.tvStatusBadge)
        tvMainPrediction = view.findViewById(R.id.tvMainPrediction)
        tvProbableRange = view.findViewById(R.id.tvProbableRange)
        tvForecastWeekly = view.findViewById(R.id.tvForecastWeekly)
        tvForecast30Days = view.findViewById(R.id.tvForecast30Days)
        tvAverageWeekly = view.findViewById(R.id.tvAverageWeekly)
        tvAverageMonthly = view.findViewById(R.id.tvAverageMonthly)
        cardHighDemandScenario = view.findViewById(R.id.cardHighDemandScenario)
        cardLowDemandScenario = view.findViewById(R.id.cardLowDemandScenario)
        tvHighDemandScenario = view.findViewById(R.id.tvHighDemandScenario)
        tvLowDemandScenario = view.findViewById(R.id.tvLowDemandScenario)
        tvSeasonInsight = view.findViewById(R.id.tvSeasonInsight)
        v3DeepContainer = view.findViewById(R.id.v3DeepContainer)

        // IMPORTANTE: activar nested scrolling DESPUÉS de inflar la vista.
        // En algunos Xiaomi/AndroidX, declararlo como atributo XML provoca un NPE durante el constructor.
        view.findViewById<NestedScrollView>(R.id.scrollPredictive).apply {
            isNestedScrollingEnabled = true
            isFillViewport = true
        }

        // Fallback inmediato con V2. En DEV, V3 reemplaza estos valores al terminar el análisis.
        renderLegacyFallback()

        val projectId = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
        if (projectId == DEV_PROJECT_ID) {
            addLoadingCard()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val analysis = PredictiveV3Coordinator(FirebaseFirestore.getInstance())
                        .analyzeProduct(product.id)
                    if (!isAdded) return@launch
                    renderV3(analysis)
                } catch (e: Exception) {
                    if (!isAdded) return@launch
                    v3DeepContainer.removeAllViews()
                    addSectionCard(
                        title = "No se pudo completar el análisis V3",
                        subtitle = "Los datos V2 siguen visibles. Detalle: ${e.message ?: "error desconocido"}",
                        accent = "#B91C1C",
                        background = "#FFF7F7"
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val targetHeight = (resources.displayMetrics.heightPixels * 0.92f).roundToInt()
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = targetHeight }
        BottomSheetBehavior.from(bottomSheet).apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isFitToContents = true
            peekHeight = targetHeight
        }
        view?.findViewById<NestedScrollView>(R.id.scrollPredictive)?.post {
            view?.findViewById<NestedScrollView>(R.id.scrollPredictive)?.scrollTo(0, 0)
        }
    }

    private fun renderLegacyFallback() {
        val weekly = product.demandaSemanalPrevista
        val average = product.consumoSemanalPromedio
        tvForecastWeekly.text = formatQuantity(weekly, product.unit)
        tvForecast30Days.text = formatQuantity(weekly * 30.0 / 7.0, product.unit)
        tvAverageWeekly.text = if (average > 0.0) formatQuantity(average, product.unit) else "Sin dato reciente"
        tvAverageMonthly.text = if (average > 0.0) formatQuantity(average * 4.0, product.unit) else "Sin dato reciente"

        val days = PredictiveConsumptionEngine.coverageDays(product.totalStock, weekly)
        if (days == null) {
            renderCoverage(null, "Sin datos suficientes", "Sin rango disponible")
            cardHighDemandScenario.visibility = View.GONE
            cardLowDemandScenario.visibility = View.GONE
            tvSeasonInsight.text = "V3 está preparando un análisis más completo con historial, lotes y grupos."
            return
        }

        renderCoverage(
            days = days,
            mainText = if (days == 0) "El stock está agotado" else "Stock estimado para ${PredictiveConsumptionEngine.formatDuration(days)}",
            rangeText = "Referencia inicial V2"
        )
        tvHighDemandScenario.text = "Si aumenta el consumo, la cobertura puede reducirse."
        tvLowDemandScenario.text = "Si disminuye el consumo, la cobertura puede extenderse."
        tvSeasonInsight.text = "Referencia inicial mientras V3 termina de analizar los datos reales."
    }

    private fun renderV3(analysis: PredictiveV3Analysis) {
        val forecast = analysis.individualForecast
        val operational = analysis.individualOperational
        val coverage = analysis.effectiveCoverageDays
        val coverageInt = coverage?.coerceAtLeast(0.0)?.roundToInt()

        tvForecastWeekly.text = formatQuantity(operational.effectiveWeeklyKg, product.unit)
        tvForecast30Days.text = formatQuantity(operational.effectiveWeeklyKg * 30.0 / 7.0, product.unit)
        tvAverageWeekly.text = formatQuantity(forecast.baselineWeeklyKg, product.unit)
        tvAverageMonthly.text = formatQuantity(forecast.baselineWeeklyKg * 4.0, product.unit)

        val highDays = coverageDays(product.totalStock, forecast.highScenarioWeeklyKg)
        val lowDays = coverageDays(product.totalStock, forecast.lowScenarioWeeklyKg)
        val probableMin = listOfNotNull(highDays, coverageInt, lowDays).minOrNull()
        val probableMax = listOfNotNull(highDays, coverageInt, lowDays).maxOrNull()

        renderCoverage(
            days = coverageInt,
            mainText = coverageInt?.let { "Stock estimado para ${PredictiveConsumptionEngine.formatDuration(it)}" }
                ?: "Cobertura no calculable",
            rangeText = if (probableMin != null && probableMax != null) {
                "Rango probable: ${PredictiveConsumptionEngine.formatDuration(probableMin)} a ${PredictiveConsumptionEngine.formatDuration(probableMax)}"
            } else "Rango probable no disponible"
        )

        tvHighDemandScenario.text = highDays?.let {
            "Si aumenta el consumo, podría alcanzar aproximadamente ${PredictiveConsumptionEngine.formatDuration(it)}."
        } ?: "No hay suficiente información para el escenario de mayor consumo."
        tvLowDemandScenario.text = lowDays?.let {
            "Si baja el consumo, podría alcanzar aproximadamente ${PredictiveConsumptionEngine.formatDuration(it)}."
        } ?: "No hay suficiente información para el escenario de menor consumo."
        tvSeasonInsight.text = seasonExplanation(analysis.regime)

        v3DeepContainer.removeAllViews()
        addOperationalCard(analysis)
        analysis.groupAnalysis?.let { addGroupCard(it, analysis) }
        analysis.serviceAnalysis?.let { service ->
            addSectionCard(
                title = "Relación de servicio",
                rows = listOf(
                    "Demanda total" to "${format1(service.allocation.totalWeeklyDemandKg)} kg/semana",
                    service.anchorProductName to "${format1(service.allocation.anchorWeeklyKg)} kg/semana",
                    service.linkedGroupName to "${format1(service.allocation.linkedGroupWeeklyKg)} kg/semana"
                ),
                note = if (service.allocation.anchorWasRestrictedByStock) {
                    "El stock de ${service.anchorProductName} es limitado; el motor desplaza parte de la necesidad al grupo sin aumentar la demanda total."
                } else {
                    "El reparto se mantiene cerca de su comportamiento histórico normal."
                },
                accent = "#0F766E",
                background = "#F0FDFA"
            )
        }
        addInventoryCard(analysis.inventoryDeep)
        addPendingCard(analysis)
        addHistoryCard(analysis)
        addAdvancedCard(analysis)
    }

    private fun addOperationalCard(analysis: PredictiveV3Analysis) {
        val operational = analysis.individualOperational
        val groupName = analysis.groupAnalysis?.let { groupDisplayName(it.config.id, it.config.name) } ?: "No"
        val pattern = analysis.consumptionPattern?.title ?: "Sin clasificar"

        addSectionCard(
            title = "Resumen operativo",
            badge = "DEV · SOLO SUGIERE",
            rows = listOf(
                "Temporada" to seasonLabel(analysis.regime),
                "Cubierto por" to formatWindowDays(analysis.targetWindowDays),
                "Demanda estimada" to "${format1(operational.effectiveWeeklyKg)} kg/semana",
                "Cobertura total" to (analysis.effectiveCoverageDays?.let { "${format0(it)} días" } ?: "Sin dato"),
                "Comportamiento" to pattern,
                "C04 actual" to "${format1(analysis.selectedProduct.stockCongelador04)} kg",
                "Objetivo C04" to "${format1(operational.dynamicC04TargetKg)} kg",
                "Traspaso sugerido" to "${format1(operational.suggestedTransferKg)} kg",
                "Pertenece a grupo" to groupName
            ),
            note = analysis.consumptionPattern?.explanation,
            accent = "#6D28D9",
            background = "#FAF5FF"
        )
    }

    private fun addGroupCard(group: GroupAnalysisV3, analysis: PredictiveV3Analysis) {
        val allocationLines = group.allocation.members.map { member ->
            val name = group.memberNames[member.productId] ?: member.productId.takeLast(6)
            "$name: ${format1(member.suggestedKg)} kg"
        }
        val monthLines = formatMonthSummaries(analysis.groupInventory?.matrizByMonth.orEmpty(), maxItems = 4)

        addSectionCard(
            title = "Grupo ${groupDisplayName(group.config.id, group.config.name)}",
            rows = listOf(
                "Demanda conjunta" to "${format1(group.operational.effectiveWeeklyKg)} kg/semana",
                "Objetivo C04 conjunto" to "${format1(group.operational.dynamicC04TargetKg)} kg",
                "Traspaso conjunto" to "${format1(group.allocation.allocatedKg)} kg"
            ),
            bullets = buildList {
                if (allocationLines.isNotEmpty()) {
                    add("Reparto sugerido: ${allocationLines.joinToString(" · ")}")
                }
                if (monthLines.isNotEmpty()) {
                    add("Mercancía del grupo por mes en Matriz: ${monthLines.joinToString(" | ")}")
                }
                if (group.allocation.unallocatedKg > 0.01) {
                    add("Faltan ${format1(group.allocation.unallocatedKg)} kg por disponibilidad o reserva de Matriz.")
                }
            },
            note = "La demanda se calcula primero para todo el grupo y después se reparte por antigüedad, stock disponible y prioridad operativa.",
            accent = "#1D4ED8",
            background = "#EFF6FF"
        )
    }

    private fun addInventoryCard(inventory: InventoryDeepSignal?) {
        if (inventory == null) return
        val matrizTotal = inventory.matrizLots.sumOf { it.currentKg }
        val c04Total = inventory.c04Lots.sumOf { it.currentKg }
        val matrizOldest = inventory.matrizLots.firstOrNull()
        val c04Oldest = inventory.c04Lots.firstOrNull()

        val bullets = buildList {
            matrizOldest?.let {
                add("Matriz · lote más antiguo: ${formatDate(it.effectiveReceivedAt())} · ${format1(it.currentKg)} kg${supplierSuffix(it)}")
            }
            c04Oldest?.let {
                add("C04 · lote más antiguo: ${formatDate(it.effectiveReceivedAt())} · ${format1(it.currentKg)} kg${supplierSuffix(it)}")
            }
            val matrizMonths = formatMonthSummaries(inventory.matrizByMonth, 5)
            if (matrizMonths.isNotEmpty()) add("Matriz por mes: ${matrizMonths.joinToString(" | ")}")
            val c04Months = formatMonthSummaries(inventory.c04ByMonth, 5)
            if (c04Months.isNotEmpty()) add("C04 por mes: ${c04Months.joinToString(" | ")}")
            if (inventory.residualLotCount > 0) {
                add("Se detectaron ${inventory.residualLotCount} lote(s) menores a 0.10 kg. Se consideran agotados y no cuentan como stock disponible.")
            }
            if (inventory.negativeLotCount > 0) {
                add("Atención: existen ${inventory.negativeLotCount} lote(s) con cantidad negativa; requieren revisión administrativa.")
            }
        }

        addSectionCard(
            title = "Lotes y antigüedad",
            rows = listOf(
                "Matriz" to "${inventory.matrizLots.size} lotes · ${format1(matrizTotal)} kg",
                "C04" to "${inventory.c04Lots.size} lotes · ${format1(c04Total)} kg"
            ),
            bullets = bullets,
            note = "Para la antigüedad se usa la fecha original de recepción, aunque el lote después haya sido traspasado a C04.",
            accent = "#B45309",
            background = "#FFFBEB"
        )
    }

    private fun addPendingCard(analysis: PredictiveV3Analysis) {
        val packaging = analysis.packagingSignal
        val returns = analysis.returnSignal
        val purchase = analysis.purchaseSignal
        val transfer = analysis.transferSignal

        val rows = mutableListOf<Pair<String, String>>()
        if (packaging != null) rows += "Pendiente de empacar" to "${packaging.pendingCount} · ${format1(packaging.pendingKg)} kg"
        else rows += "Pendiente de empacar" to "Ninguno"
        if (returns != null && returns.pendingCount > 0) rows += "Devoluciones pendientes" to "${returns.pendingCount} · ${format1(returns.pendingKg)} kg"
        else rows += "Devoluciones pendientes" to "Ninguna"
        purchase?.let { rows += "Compra promedio" to "${format1(it.averagePurchaseKg)} kg · ${it.purchaseCount} veces" }
        purchase?.averageDaysBetweenPurchases?.let { rows += "Frecuencia de compra" to "Cada ~${format1(it)} días" }

        val bullets = buildList {
            transfer?.let {
                val a = it.averageKgWindowA?.let { kg -> "Lun/Mar ~${format1(kg)} kg" }
                val b = it.averageKgWindowB?.let { kg -> "Jue/Vie ~${format1(kg)} kg" }
                val parts = listOfNotNull(a, b)
                if (parts.isNotEmpty()) add("Traspasos habituales a C04: ${parts.joinToString(" · ")}")
            }
            returns?.takeIf { it.historicalCount > 0 }?.let {
                val providers = if (it.mainProviders.isNotEmpty()) " · Proveedores: ${it.mainProviders.joinToString()}" else ""
                add("Histórico de devoluciones: ${it.historicalCount} · ${format1(it.historicalKg)} kg$providers")
            }
            packaging?.oldestPendingAt?.let { add("Empaque pendiente más antiguo: ${formatDate(it)}") }
        }

        addSectionCard(
            title = "Compras y pendientes",
            rows = rows,
            bullets = bullets,
            accent = "#047857",
            background = "#ECFDF5"
        )
    }

    private fun addHistoryCard(analysis: PredictiveV3Analysis) {
        val history = analysis.historicalReference
        val rows = listOf(
            "Hace un año · semana" to (history?.weekOneYearAgoKg?.let { "${format1(it)} kg" } ?: "Sin dato"),
            "Hace un año · mes" to (history?.monthOneYearAgoKg?.let { "${format1(it)} kg" } ?: "Sin dato")
        )
        addSectionCard(
            title = "Referencias históricas",
            rows = rows,
            note = "Estas referencias ayudan a distinguir una temporada normal de un cambio temporal de consumo.",
            accent = "#475569",
            background = "#F8FAFC"
        )
    }

    private fun addAdvancedCard(analysis: PredictiveV3Analysis) {
        val forecast = analysis.individualForecast
        val backtest = analysis.backtest
        val bodyLines = buildList {
            add("Nivel de confianza interno: ${confidenceLabel(forecast.confidence)}")
            add("Referencia histórica interna: ${format1(forecast.baselineWeeklyKg)} kg/semana")
            add("Ritmo reciente observado: ${format1(forecast.liveWeeklyPaceKg)} kg/semana")
            analysis.recentDeviationPct?.let {
                add("Cambio reciente: ${if (it >= 0) "+" else ""}${format0(it)}% frente a la base")
            }
            addBacktestLines(this, backtest)
            analysis.purchaseAttention?.let { add("Compra: ${it.message}") }
            analysis.smartReasons.forEach { add(it) }
        }.distinct()

        val card = MaterialCardView(requireContext()).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#CBD5E1")
            setCardBackgroundColor(Color.WHITE)
            layoutParams = sectionLayoutParams(top = 12)
        }
        val wrapper = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val header = TextView(requireContext()).apply {
            text = "Información avanzada  ▾"
            setTextColor(Color.parseColor("#334155"))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        val body = TextView(requireContext()).apply {
            text = bodyLines.joinToString("\n") { "• $it" }
            setTextColor(Color.parseColor("#475569"))
            textSize = 12f
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        header.setOnClickListener {
            val showing = body.visibility == View.VISIBLE
            body.visibility = if (showing) View.GONE else View.VISIBLE
            header.text = if (showing) "Información avanzada  ▾" else "Información avanzada  ▴"
        }
        wrapper.addView(header)
        wrapper.addView(body)
        card.addView(wrapper)
        v3DeepContainer.addView(card)
    }

    private fun addBacktestLines(target: MutableList<String>, backtest: BacktestSignal?) {
        if (backtest == null || backtest.sampleCount <= 0) {
            target += "Validación histórica: todavía insuficiente"
            return
        }
        target += "Validación histórica: ${backtest.qualityLabel} · ${backtest.sampleCount} pruebas"
        backtest.meanAbsolutePercentError?.let { target += "Error histórico medio aproximado: ${format0(it)}%" }
        target += backtest.explanation
    }

    private fun addLoadingCard() {
        v3DeepContainer.removeAllViews()
        addSectionCard(
            title = "Análisis predictivo",
            subtitle = "Revisando consumo, lotes, compras, grupos y pendientes...",
            badge = "DEV · SOLO SUGIERE",
            accent = "#6D28D9",
            background = "#FAF5FF"
        )
    }

    private fun addSectionCard(
        title: String,
        subtitle: String? = null,
        rows: List<Pair<String, String>> = emptyList(),
        bullets: List<String> = emptyList(),
        note: String? = null,
        badge: String? = null,
        accent: String = "#334155",
        background: String = "#FFFFFF"
    ) {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.parseColor(lightenStroke(accent))
            setCardBackgroundColor(Color.parseColor(background))
            layoutParams = sectionLayoutParams(top = 12)
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(requireContext()).apply {
            text = title
            setTextColor(Color.parseColor(accent))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleView)
        badge?.let {
            titleRow.addView(TextView(requireContext()).apply {
                text = it
                setTextColor(Color.parseColor(accent))
                textSize = 9f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            })
        }
        content.addView(titleRow)

        subtitle?.let {
            content.addView(TextView(requireContext()).apply {
                text = it
                setTextColor(Color.parseColor("#64748B"))
                textSize = 12f
                setPadding(0, dp(5), 0, 0)
            })
        }

        rows.forEachIndexed { index, pair ->
            if (index == 0) content.addView(spacer(dp(8)))
            content.addView(valueRow(pair.first, pair.second))
        }

        if (bullets.isNotEmpty()) {
            content.addView(spacer(dp(8)))
            content.addView(TextView(requireContext()).apply {
                text = bullets.joinToString("\n") { "• $it" }
                setTextColor(Color.parseColor("#475569"))
                textSize = 11.5f
                setLineSpacing(0f, 1.1f)
            })
        }

        note?.takeIf { it.isNotBlank() }?.let {
            content.addView(TextView(requireContext()).apply {
                text = it
                setTextColor(Color.parseColor("#64748B"))
                textSize = 10.5f
                setPadding(0, dp(8), 0, 0)
            })
        }

        card.addView(content)
        v3DeepContainer.addView(card)
    }

    private fun valueRow(label: String, value: String): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(requireContext()).apply {
                text = label
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11.5f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(requireContext()).apply {
                text = value
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 12.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.END
            })
        }
    }

    private fun renderCoverage(days: Int?, mainText: String, rangeText: String) {
        if (days == null) {
            val gray = Color.parseColor("#94A3B8")
            tvGaugeNumber.text = "–"
            tvGaugeText.text = "SIN DATO"
            tvGaugeExtra.visibility = View.GONE
            tvGaugeNumber.setTextColor(gray)
            tvGaugeText.setTextColor(gray)
            progressGauge.progressDrawable.setTint(gray)
            progressGauge.progress = 0
            cardStatusBadge.setCardBackgroundColor(Color.parseColor("#F1F5F9"))
            tvStatusBadge.setTextColor(Color.parseColor("#64748B"))
            tvStatusBadge.text = "Historial insuficiente"
            tvMainPrediction.text = mainText
            tvProbableRange.text = rangeText
            return
        }

        val status = PredictiveConsumptionEngine.coverageStatus(days)
        val statusColor = Color.parseColor(status.colorHex)
        val badgeColor = Color.parseColor(status.badgeBackgroundHex)
        tvGaugeNumber.text = days.toString()
        tvGaugeText.text = if (days == 1) "DÍA" else "DÍAS"
        tvGaugeExtra.visibility = View.GONE
        tvGaugeNumber.setTextColor(statusColor)
        tvGaugeText.setTextColor(statusColor)
        progressGauge.progressDrawable.setTint(statusColor)
        progressGauge.progress = PredictiveConsumptionEngine.gaugeProgress(days)
        cardStatusBadge.setCardBackgroundColor(badgeColor)
        tvStatusBadge.setTextColor(statusColor)
        tvStatusBadge.text = when (status) {
            PredictiveConsumptionEngine.CoverageStatus.CRITICAL -> "⚠ Cobertura crítica"
            PredictiveConsumptionEngine.CoverageStatus.BUY_SOON -> "● Revisar compra"
            PredictiveConsumptionEngine.CoverageStatus.ATTENTION -> "● Atención"
            PredictiveConsumptionEngine.CoverageStatus.HEALTHY -> "✓ Cobertura saludable"
            PredictiveConsumptionEngine.CoverageStatus.VERY_HEALTHY -> "✓ Cobertura amplia"
        }
        tvMainPrediction.text = mainText
        tvProbableRange.text = rangeText
    }

    private fun coverageDays(stock: Double, weeklyKg: Double): Int? {
        if (weeklyKg <= 0.01) return null
        return (stock.coerceAtLeast(0.0) / (weeklyKg / 7.0)).roundToInt().coerceAtLeast(0)
    }

    private fun seasonExplanation(regime: SeasonRegime): String = when (regime) {
        SeasonRegime.LENT -> "Temporada: Cuaresma. El motor compara principalmente contra periodos equivalentes de Cuaresma."
        SeasonRegime.DECEMBER -> "Temporada: Diciembre. El motor separa este comportamiento del resto del año."
        SeasonRegime.HOLIDAY -> "Temporada: Festivo. El consumo esperado se interpreta como un periodo especial."
        SeasonRegime.HIGH_SEASON -> "Temporada alta. El objetivo operativo aumenta junto con la demanda esperada."
        SeasonRegime.NORMAL -> "Temporada normal. Se combinan historial, comportamiento reciente y referencia del año anterior."
    }

    private fun seasonLabel(regime: SeasonRegime): String = when (regime) {
        SeasonRegime.NORMAL -> "Normal"
        SeasonRegime.LENT -> "Cuaresma"
        SeasonRegime.DECEMBER -> "Diciembre"
        SeasonRegime.HOLIDAY -> "Festivo"
        SeasonRegime.HIGH_SEASON -> "Temporada alta"
    }

    private fun confidenceLabel(value: ConfidenceLevel): String = when (value) {
        ConfidenceLevel.HIGH -> "Alta"
        ConfidenceLevel.MEDIUM -> "Media"
        ConfidenceLevel.LOW -> "Baja"
    }

    private fun formatMonthSummaries(items: List<MonthStockSummary>, maxItems: Int): List<String> {
        return items.takeLast(maxItems).map { item ->
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, item.year)
                set(Calendar.MONTH, item.month - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            val month = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(cal.time)
            val products = item.byProductKg.entries
                .sortedByDescending { it.value }
                .take(4)
                .joinToString(", ") { "${it.key} ${format1(it.value)} kg" }
            if (products.isBlank()) "$month: ${format1(item.totalKg)} kg" else "$month: $products"
        }
    }

    private fun supplierSuffix(lot: LotInsight): String =
        lot.supplierName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""

    private fun formatDate(date: Date?): String =
        date?.let { SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(it) } ?: "Sin fecha"

    private fun groupDisplayName(id: String, configuredName: String): String = when (id.uppercase(Locale.ROOT)) {
        "FILETE" -> "Filetes"
        "PARGOS" -> "Pargos / Huachinangos"
        else -> configuredName
    }

    private fun formatWindowDays(days: Double): String {
        val rounded = days.roundToLong()
        return if (abs(days - rounded) < 0.05) "$rounded días" else "${format1(days)} días"
    }

    private fun formatQuantity(value: Double, unit: String): String {
        val normalized = if (abs(value) < 0.0001) 0.0 else value
        return if (abs(normalized - normalized.roundToLong()) < 0.05) {
            String.format(Locale.getDefault(), "%,d %s", normalized.roundToLong(), unit)
        } else {
            String.format(Locale.getDefault(), "%,.1f %s", normalized, unit)
        }
    }

    private fun format1(value: Double): String = String.format(Locale.getDefault(), "%,.1f", value)
    private fun format0(value: Double): String = String.format(Locale.getDefault(), "%.0f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun spacer(height: Int): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun sectionLayoutParams(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        }

    private fun lightenStroke(accent: String): String = when (accent.uppercase(Locale.ROOT)) {
        "#6D28D9" -> "#D8B4FE"
        "#1D4ED8" -> "#BFDBFE"
        "#0F766E" -> "#99F6E4"
        "#B45309" -> "#FDE68A"
        "#047857" -> "#A7F3D0"
        "#B91C1C" -> "#FECACA"
        else -> "#CBD5E1"
    }
}
