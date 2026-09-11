package com.cesar.bocana.util

import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Motor matemático del Consumo Predictivo.
 *
 * IMPORTANTE:
 * - No consulta Firebase.
 * - No modifica inventario.
 * - Solo recibe checkpoints y produce una fotografía de demanda.
 * - Mantiene el mismo esquema Año + WEEK_OF_YEAR que ya usa consumption_history.
 */
object PredictiveConsumptionEngine {

    const val MODEL_VERSION = 2
    const val FIRESTORE_IN_QUERY_CHUNK_SIZE = 30

    data class WeekKey(
        val year: Int,
        val week: Int
    ) {
        fun checkpointId(productId: String): String = "${productId}_${year}_${week}"
    }

    data class ForecastReferences(
        val periodKey: String,
        val recentWeeks: List<WeekKey>,
        val seasonalWeeks: List<WeekKey>,
        val isLentSeason: Boolean
    )

    data class ForecastResult(
        val averageWeeklyConsumption: Double,
        val predictedWeeklyDemand: Double,
        val highDemandWeekly: Double,
        val lowDemandWeekly: Double,
        val usesSeasonality: Boolean
    )

    enum class CoverageStatus(
        val label: String,
        val colorHex: String,
        val badgeBackgroundHex: String
    ) {
        CRITICAL(
            label = "Stock crítico",
            colorHex = "#D32F2F",
            badgeBackgroundHex = "#FFEBEE"
        ),
        BUY_SOON(
            label = "Comprar pronto",
            colorHex = "#F57C00",
            badgeBackgroundHex = "#FFF3E0"
        ),
        ATTENTION(
            label = "Atención",
            colorHex = "#F9A825",
            badgeBackgroundHex = "#FFF8E1"
        ),
        HEALTHY(
            label = "Stock saludable",
            colorHex = "#43A047",
            badgeBackgroundHex = "#E8F5E9"
        ),
        VERY_HEALTHY(
            label = "Muy abastecido",
            colorHex = "#1B5E20",
            badgeBackgroundHex = "#E8F5E9"
        )
    }

    data class GaugeDisplay(
        val mainValue: String,
        val unitLabel: String,
        val extraLabel: String
    )

    /**
     * Crea las semanas que necesita el predictor sin usar aritmética manual de
     * "semana 52". Calendar se encarga de años con semana 53 y cambios de año.
     *
     * recentWeeks:
     *   W-1, W-2, W-3 (solo semanas completas)
     *
     * seasonalWeeks:
     *   misma posición relativa respecto a Semana Santa del año anterior,
     *   usando centro -1, centro, centro +1.
     */
    fun buildReferences(now: Date = Date()): ForecastReferences {
        val current = Calendar.getInstance().apply { time = now }
        val currentWeekStart = startOfWeek(current)

        val currentPeriod = weekKey(currentWeekStart)
        val recentWeeks = listOf(1, 2, 3).map { weeksAgo ->
            val cal = currentWeekStart.clone() as Calendar
            cal.add(Calendar.WEEK_OF_YEAR, -weeksAgo)
            weekKey(cal)
        }

        val currentYear = current.get(Calendar.YEAR)
        val easterCurrent = easterSunday(currentYear)
        val easterCurrentWeekStart = startOfWeek(easterCurrent)
        val offsetFromEasterWeeks = weekDistance(easterCurrentWeekStart, currentWeekStart)

        val easterPrevious = easterSunday(currentYear - 1)
        val seasonalCenter = startOfWeek(easterPrevious).apply {
            add(Calendar.WEEK_OF_YEAR, offsetFromEasterWeeks)
        }

        val seasonalWeeks = listOf(-1, 0, 1).map { offset ->
            val cal = seasonalCenter.clone() as Calendar
            cal.add(Calendar.WEEK_OF_YEAR, offset)
            weekKey(cal)
        }

        val ashWednesday = easterCurrent.clone() as Calendar
        ashWednesday.add(Calendar.DAY_OF_YEAR, -46)

        val todayOnly = dayOnly(current)
        val ashOnly = dayOnly(ashWednesday)
        val easterOnly = dayOnly(easterCurrent)
        val isLentSeason = !todayOnly.before(ashOnly) && !todayOnly.after(easterOnly)

        return ForecastReferences(
            periodKey = "${currentPeriod.year}-W${currentPeriod.week}",
            recentWeeks = recentWeeks,
            seasonalWeeks = seasonalWeeks,
            isLentSeason = isLentSeason
        )
    }

    /**
     * Calcula el pronóstico sin convertir checkpoints faltantes en cero.
     * Los pesos se renormalizan usando únicamente valores existentes.
     */
    fun calculate(
        recentValues: List<Double?>,
        seasonalValues: List<Double?>,
        isLentSeason: Boolean
    ): ForecastResult {
        val cleanRecent = recentValues.map { sanitize(it) }
        val cleanSeasonal = seasonalValues.map { sanitize(it) }

        val recentAverage = cleanRecent.filterNotNull().averageOrNull() ?: 0.0

        val recentWeighted = weightedAverage(
            cleanRecent,
            listOf(0.55, 0.30, 0.15)
        )

        val seasonalWeighted = weightedAverage(
            cleanSeasonal,
            listOf(0.25, 0.50, 0.25)
        )

        val predicted = when {
            recentWeighted != null && seasonalWeighted != null -> {
                // En Cuaresma/Semana Santa la referencia estacional pesa más.
                val seasonalWeight = if (isLentSeason) 0.50 else 0.35
                val recentWeight = 1.0 - seasonalWeight
                (recentWeighted * recentWeight) + (seasonalWeighted * seasonalWeight)
            }
            recentWeighted != null -> recentWeighted
            seasonalWeighted != null -> seasonalWeighted
            else -> 0.0
        }

        if (predicted <= 0.0) {
            return ForecastResult(
                averageWeeklyConsumption = recentAverage,
                predictedWeeklyDemand = 0.0,
                highDemandWeekly = 0.0,
                lowDemandWeekly = 0.0,
                usesSeasonality = seasonalWeighted != null
            )
        }

        val allAvailable = (cleanRecent + cleanSeasonal).filterNotNull()
        val scenarioMargin = calculateScenarioMargin(allAvailable, predicted)

        return ForecastResult(
            averageWeeklyConsumption = recentAverage,
            predictedWeeklyDemand = predicted,
            highDemandWeekly = predicted * (1.0 + scenarioMargin),
            lowDemandWeekly = (predicted * (1.0 - scenarioMargin)).coerceAtLeast(predicted * 0.10),
            usesSeasonality = seasonalWeighted != null
        )
    }

    /**
     * Convierte stock + demanda semanal a días de cobertura.
     * Devuelve null cuando no existe una demanda válida.
     */
    fun coverageDays(stock: Double, weeklyDemand: Double): Int? {
        if (weeklyDemand <= 0.0) return null
        if (stock <= 0.0) return 0

        val dailyDemand = weeklyDemand / 7.0
        return (stock / dailyDemand).roundToInt().coerceAtLeast(0)
    }

    /** Límite inferior del rango: redondeo hacia abajo. */
    fun coverageDaysFloor(stock: Double, weeklyDemand: Double): Int? {
        if (weeklyDemand <= 0.0) return null
        if (stock <= 0.0) return 0

        val dailyDemand = weeklyDemand / 7.0
        return floor(stock / dailyDemand).toInt().coerceAtLeast(0)
    }

    /** Límite superior del rango: redondeo hacia arriba. */
    fun coverageDaysCeil(stock: Double, weeklyDemand: Double): Int? {
        if (weeklyDemand <= 0.0) return null
        if (stock <= 0.0) return 0

        val dailyDemand = weeklyDemand / 7.0
        return ceil(stock / dailyDemand).toInt().coerceAtLeast(0)
    }

    fun coverageStatus(days: Int): CoverageStatus = when {
        days <= 7 -> CoverageStatus.CRITICAL
        days <= 14 -> CoverageStatus.BUY_SOON
        days <= 28 -> CoverageStatus.ATTENTION
        days <= 60 -> CoverageStatus.HEALTHY
        else -> CoverageStatus.VERY_HEALTHY
    }

    /** Gauge de cobertura: 60 días o más llenan el 100%. */
    fun gaugeProgress(days: Int): Int {
        return ((days.coerceIn(0, 60) / 60.0) * 100.0).roundToInt()
    }

    /** Texto humano completo para el popup. */
    fun formatDuration(days: Int): String {
        val safeDays = days.coerceAtLeast(0)
        if (safeDays == 0) return "hoy"
        if (safeDays == 1) return "1 día"
        if (safeDays < 7) return "$safeDays días"

        if (safeDays < 30) {
            val weeks = safeDays / 7
            val remainingDays = safeDays % 7
            val weekText = if (weeks == 1) "1 semana" else "$weeks semanas"
            return if (remainingDays == 0) {
                weekText
            } else {
                val dayText = if (remainingDays == 1) "1 día" else "$remainingDays días"
                "$weekText y $dayText"
            }
        }

        val months = safeDays / 30
        val remainingDays = safeDays % 30
        val monthText = if (months == 1) "1 mes" else "$months meses"
        return if (remainingDays == 0) {
            monthText
        } else {
            val dayText = if (remainingDays == 1) "1 día" else "$remainingDays días"
            "$monthText y $dayText"
        }
    }

    /** Texto compacto para la lista de productos. */
    fun formatDurationCompact(days: Int): String {
        val safeDays = days.coerceAtLeast(0)
        if (safeDays == 0) return "Hoy"
        if (safeDays < 7) return if (safeDays == 1) "1 día" else "$safeDays días"
        if (safeDays < 30) {
            val weeks = safeDays / 7
            val remainingDays = safeDays % 7
            return if (remainingDays == 0) {
                if (weeks == 1) "1 semana" else "$weeks semanas"
            } else {
                "$weeks sem $remainingDays d"
            }
        }

        val months = safeDays / 30
        val remainingDays = safeDays % 30
        return if (remainingDays == 0) {
            if (months == 1) "1 mes" else "$months meses"
        } else {
            "$months mes ${remainingDays} d"
        }
    }

    /** Contenido central del círculo sin decimales ambiguos. */
    fun gaugeDisplay(days: Int): GaugeDisplay {
        val safeDays = days.coerceAtLeast(0)

        return when {
            safeDays < 7 -> GaugeDisplay(
                mainValue = safeDays.toString(),
                unitLabel = if (safeDays == 1) "DÍA" else "DÍAS",
                extraLabel = ""
            )

            safeDays < 30 -> {
                val weeks = safeDays / 7
                val remainingDays = safeDays % 7
                GaugeDisplay(
                    mainValue = weeks.toString(),
                    unitLabel = if (weeks == 1) "SEMANA" else "SEMANAS",
                    extraLabel = if (remainingDays == 0) "" else "+ $remainingDays ${if (remainingDays == 1) "día" else "días"}"
                )
            }

            else -> {
                val months = safeDays / 30
                val remainingDays = safeDays % 30
                GaugeDisplay(
                    mainValue = months.toString(),
                    unitLabel = if (months == 1) "MES" else "MESES",
                    extraLabel = if (remainingDays == 0) "" else "+ $remainingDays ${if (remainingDays == 1) "día" else "días"}"
                )
            }
        }
    }

    private fun sanitize(value: Double?): Double? {
        return value?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun weightedAverage(values: List<Double?>, weights: List<Double>): Double? {
        var weightedSum = 0.0
        var appliedWeight = 0.0

        values.forEachIndexed { index, value ->
            if (value != null) {
                val weight = weights.getOrElse(index) { 0.0 }
                weightedSum += value * weight
                appliedWeight += weight
            }
        }

        return if (appliedWeight > 0.0) weightedSum / appliedWeight else null
    }

    private fun Iterable<Double>.averageOrNull(): Double? {
        val values = this.toList()
        return if (values.isEmpty()) null else values.average()
    }

    /**
     * El rango alto/bajo nace de la variación real de los checkpoints disponibles.
     * Nunca baja de ±15% ni supera ±45% para evitar rangos absurdos.
     */
    private fun calculateScenarioMargin(values: List<Double>, predicted: Double): Double {
        if (values.isEmpty() || predicted <= 0.0) return 0.20
        if (values.size == 1) return 0.20

        val mean = values.average()
        if (mean <= 0.0) return 0.20

        val variance = values.sumOf { value ->
            val diff = value - mean
            diff * diff
        } / values.size

        val standardDeviation = sqrt(variance)
        val coefficientOfVariation = standardDeviation / mean
        return coefficientOfVariation.coerceIn(0.15, 0.45)
    }

    private fun weekKey(calendar: Calendar): WeekKey {
        return WeekKey(
            year = calendar.get(Calendar.YEAR),
            week = calendar.get(Calendar.WEEK_OF_YEAR)
        )
    }

    private fun startOfWeek(source: Calendar): Calendar {
        return (source.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        }
    }

    private fun dayOnly(source: Calendar): Date {
        return (source.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time
    }

    private fun weekDistance(fromWeekStart: Calendar, toWeekStart: Calendar): Int {
        val millisPerWeek = 7L * 24L * 60L * 60L * 1000L
        val diff = toWeekStart.timeInMillis - fromWeekStart.timeInMillis
        return (diff.toDouble() / millisPerWeek.toDouble()).roundToInt()
    }

    /** Algoritmo gregoriano de Meeus/Jones/Butcher. */
    private fun easterSunday(year: Int): Calendar {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1

        return GregorianCalendar(year, month - 1, day).apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
