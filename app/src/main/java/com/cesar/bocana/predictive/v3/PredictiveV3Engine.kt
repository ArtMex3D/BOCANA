package com.cesar.bocana.predictive.v3

import com.cesar.bocana.predictive.v3.model.ConfidenceLevel
import com.cesar.bocana.predictive.v3.model.ForecastContext
import com.cesar.bocana.predictive.v3.model.ForecastResultV3
import com.cesar.bocana.predictive.v3.model.TrendSignal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Núcleo matemático V3.
 *
 * Reglas de diseño:
 * - Es puro: no conoce Firebase, Room, UI ni nombres de productos.
 * - No modifica inventario.
 * - Los parámetros están centralizados para poder calibrarlos con backtesting.
 */
object PredictiveV3Engine {

    const val MODEL_VERSION = 3002

    data class Tuning(
        val maxCompleteWeeks: Int = 16,
        val recentWeeks: Int = 8,
        val recencyDecay: Double = 0.84,
        val priorEquivalentDaysForLivePace: Double = 3.0,
        val minLiveWeight: Double = 0.12,
        val maxLiveWeight: Double = 0.52,
        val seasonalWeightNormal: Double = 0.18,
        val seasonalWeightSpecial: Double = 0.30,
        val maxTrendBoost: Double = 1.30,
        val minTrendFactor: Double = 0.78,
        val surgeRatio: Double = 1.75,
        val risingRatio: Double = 1.20,
        val fallingRatio: Double = 0.78,
        val minScenarioMargin: Double = 0.15,
        val maxScenarioMargin: Double = 0.55,
        val minSafetyDays: Double = 0.50,
        val maxSafetyDays: Double = 3.00
    )

    val DEFAULT_TUNING = Tuning()

    fun forecast(
        context: ForecastContext,
        tuning: Tuning = DEFAULT_TUNING
    ): ForecastResultV3 {
        val rawHistory = context.completeWeeks
            .takeLast(tuning.maxCompleteWeeks)
            .map { sanitize(it.consumedKg) }

        val robustHistory = winsorizeByMad(rawHistory)
        val longBaseline = robustHistory.averageOrZero()
        val recentHistory = robustHistory.takeLast(tuning.recentWeeks)
        val recentBaseline = exponentialAverage(recentHistory, tuning.recencyDecay)

        val baseline = when {
            recentBaseline > 0.0 && longBaseline > 0.0 -> recentBaseline * 0.68 + longBaseline * 0.32
            recentBaseline > 0.0 -> recentBaseline
            else -> longBaseline
        }

        val elapsedDays = context.currentWeekElapsedDays.coerceIn(0.0, 7.0)
        val currentConsumed = sanitize(context.currentWeekConsumedKg)
        val liveWeekly = calculateLiveWeeklyPace(
            currentWeekConsumedKg = currentConsumed,
            elapsedDays = elapsedDays,
            priorWeeklyKg = baseline,
            priorEquivalentDays = tuning.priorEquivalentDaysForLivePace
        )

        val liveRatio = if (baseline > 0.01) liveWeekly / baseline else 1.0
        val trendSignal = when {
            liveRatio >= tuning.surgeRatio -> TrendSignal.SURGE
            liveRatio >= tuning.risingRatio -> TrendSignal.RISING
            liveRatio <= tuning.fallingRatio -> TrendSignal.FALLING
            else -> TrendSignal.STABLE
        }

        val slopeFactor = trendFactor(recentHistory, tuning)
        val seasonal = context.seasonalReferenceWeeklyKg
            ?.let(::sanitize)
            ?.takeIf { it > 0.0 }

        var seasonalWeight = if (context.regime.name == "NORMAL") {
            tuning.seasonalWeightNormal
        } else {
            tuning.seasonalWeightSpecial
        }
        if (seasonal == null) seasonalWeight = 0.0

        var liveWeight = tuning.minLiveWeight +
            (tuning.maxLiveWeight - tuning.minLiveWeight) * (elapsedDays / 7.0)

        if (trendSignal == TrendSignal.SURGE) {
            liveWeight = min(tuning.maxLiveWeight + 0.10, 0.65)
        }

        val baselineWeight = (1.0 - liveWeight - seasonalWeight).coerceAtLeast(0.15)
        val totalWeight = baselineWeight + liveWeight + seasonalWeight

        val blended = if (baseline > 0.0 || liveWeekly > 0.0 || seasonal != null) {
            ((baseline * baselineWeight) +
                (liveWeekly * liveWeight) +
                ((seasonal ?: 0.0) * seasonalWeight)) / totalWeight
        } else {
            0.0
        }

        val forecast = sanitize(blended * slopeFactor * context.regimeMultiplier.coerceIn(0.40, 3.00))

        val variability = robustCoefficientOfVariation(robustHistory)
        val confidence = confidenceLevel(robustHistory.size, variability)
        val confidencePenalty = when (confidence) {
            ConfidenceLevel.HIGH -> 0.00
            ConfidenceLevel.MEDIUM -> 0.06
            ConfidenceLevel.LOW -> 0.12
        }

        val scenarioMargin = (
            tuning.minScenarioMargin + variability * 0.55 + confidencePenalty
        ).coerceIn(tuning.minScenarioMargin, tuning.maxScenarioMargin)

        val low = forecast * (1.0 - scenarioMargin)
        val high = forecast * (1.0 + scenarioMargin)

        val safetyDays = (
            tuning.minSafetyDays + variability * 2.0 +
                when (confidence) {
                    ConfidenceLevel.HIGH -> 0.0
                    ConfidenceLevel.MEDIUM -> 0.35
                    ConfidenceLevel.LOW -> 0.75
                }
            ).coerceIn(tuning.minSafetyDays, tuning.maxSafetyDays)

        val targetDays = context.targetWindowDays.coerceAtLeast(0.0) + safetyDays
        val dynamicC04Target = if (forecast > 0.0) forecast / 7.0 * targetDays else 0.0
        val rawTransferNeed = max(0.0, dynamicC04Target - sanitize(context.stockC04Kg))
        val matrixUsable = max(0.0, sanitize(context.stockMatrizKg) - sanitize(context.generalReserveKg))
        val suggestedTransfer = min(rawTransferNeed, matrixUsable)
        val limitedByReserve = rawTransferNeed > matrixUsable + 0.01

        val coverageDays = if (forecast > 0.0) {
            sanitize(context.stockTotalKg) / (forecast / 7.0)
        } else {
            null
        }

        val reasons = buildList {
            add("Base robusta: ${format1(baseline)} kg/sem")
            if (elapsedDays > 0.0) add("Ritmo vivo: ${format1(liveWeekly)} kg/sem")
            if (seasonal != null) add("Referencia estacional activa: ${format1(seasonal)} kg/sem")
            if (trendSignal == TrendSignal.SURGE) add("Aceleración fuerte detectada")
            if (trendSignal == TrendSignal.RISING) add("Consumo reciente al alza")
            if (trendSignal == TrendSignal.FALLING) add("Consumo reciente por debajo de la base")
            add("Objetivo C04 dinámico para ${format1(context.targetWindowDays)} días + seguridad")
            if (context.legacyC04ReferenceKg > 0.0) {
                add("Referencia C04 existente: ${format1(context.legacyC04ReferenceKg)} kg (no usada como piso fijo)")
            }
            if (limitedByReserve) add("Sugerencia limitada para no invadir la reserva general de Matriz")
        }

        return ForecastResultV3(
            entityId = context.entityId,
            entityType = context.entityType,
            baselineWeeklyKg = baseline,
            liveWeeklyPaceKg = liveWeekly,
            forecastWeeklyKg = forecast,
            lowScenarioWeeklyKg = low,
            highScenarioWeeklyKg = high,
            trendSignal = trendSignal,
            confidence = confidence,
            variability = variability,
            coverageDays = coverageDays,
            safetyDays = safetyDays,
            dynamicC04TargetKg = dynamicC04Target,
            suggestedTransferKg = suggestedTransfer,
            limitedByMatrizReserve = limitedByReserve,
            legacyC04ReferenceKg = context.legacyC04ReferenceKg,
            reasons = reasons
        )
    }

    fun calculateLiveWeeklyPace(
        currentWeekConsumedKg: Double,
        elapsedDays: Double,
        priorWeeklyKg: Double,
        priorEquivalentDays: Double = DEFAULT_TUNING.priorEquivalentDaysForLivePace
    ): Double {
        val safeElapsed = elapsedDays.coerceIn(0.0, 7.0)
        val priorDaily = sanitize(priorWeeklyKg) / 7.0
        val priorDays = priorEquivalentDays.coerceAtLeast(0.0)
        val denominator = safeElapsed + priorDays
        if (denominator <= 0.0) return sanitize(priorWeeklyKg)

        val posteriorDaily = (
            sanitize(currentWeekConsumedKg) + priorDaily * priorDays
        ) / denominator

        return sanitize(posteriorDaily * 7.0)
    }

    private fun trendFactor(values: List<Double>, tuning: Tuning): Double {
        if (values.size < 4) return 1.0
        val ys = values.takeLast(6)
        val n = ys.size
        val xMean = (n - 1) / 2.0
        val yMean = ys.average()
        if (yMean <= 0.01) return 1.0

        var numerator = 0.0
        var denominator = 0.0
        ys.forEachIndexed { index, y ->
            val dx = index - xMean
            numerator += dx * (y - yMean)
            denominator += dx * dx
        }
        if (denominator <= 0.0) return 1.0

        val slopePerWeek = numerator / denominator
        val normalizedSlope = slopePerWeek / yMean
        return (1.0 + normalizedSlope * 1.6)
            .coerceIn(tuning.minTrendFactor, tuning.maxTrendBoost)
    }

    private fun exponentialAverage(values: List<Double>, decay: Double): Double {
        if (values.isEmpty()) return 0.0
        var weight = 1.0
        var weighted = 0.0
        var total = 0.0
        for (index in values.indices.reversed()) {
            weighted += values[index] * weight
            total += weight
            weight *= decay.coerceIn(0.50, 0.99)
        }
        return if (total > 0.0) weighted / total else 0.0
    }

    private fun winsorizeByMad(values: List<Double>): List<Double> {
        if (values.size < 4) return values.map(::sanitize)
        val clean = values.map(::sanitize)
        val median = median(clean)
        val deviations = clean.map { abs(it - median) }
        val mad = median(deviations)

        if (mad <= 0.0001) {
            val upper = percentile(clean, 0.90)
            return clean.map { min(it, upper) }
        }

        val robustSigma = mad * 1.4826
        val lower = max(0.0, median - robustSigma * 3.0)
        val upper = median + robustSigma * 3.0
        return clean.map { it.coerceIn(lower, upper) }
    }

    private fun robustCoefficientOfVariation(values: List<Double>): Double {
        if (values.size < 2) return 0.50
        val clean = winsorizeByMad(values)
        val mean = clean.averageOrZero()
        if (mean <= 0.01) return 1.0
        val variance = clean.sumOf { (it - mean) * (it - mean) } / clean.size
        return (sqrt(variance) / mean).coerceIn(0.0, 1.5)
    }

    private fun confidenceLevel(points: Int, variability: Double): ConfidenceLevel {
        return when {
            points >= 10 && variability <= 0.35 -> ConfidenceLevel.HIGH
            points >= 6 && variability <= 0.70 -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun percentile(values: List<Double>, percentile: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val p = percentile.coerceIn(0.0, 1.0)
        val index = ((sorted.size - 1) * p).toInt()
        return sorted[index]
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun sanitize(value: Double): Double = if (value.isFinite() && value > 0.0) value else 0.0

    private fun format1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
