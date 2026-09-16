package com.cesar.bocana.predictive.v3

import com.cesar.bocana.predictive.v3.model.BacktestSignal
import com.cesar.bocana.predictive.v3.model.DemandPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Validación histórica ligera del motor.
 * Usa sólo información anterior a cada semana evaluada.
 * No escribe nada en Firestore.
 */
object PredictiveV3Backtest {

    fun evaluate(series: List<DemandPoint>): BacktestSignal {
        val ordered = series.filter { it.consumedKg >= 0.0 }
        if (ordered.size < 6) {
            return BacktestSignal(
                sampleCount = 0,
                meanAbsolutePercentError = null,
                meanAbsoluteKgError = null,
                qualityLabel = "Historial insuficiente",
                explanation = "Todavía no hay suficientes semanas comparables para medir la precisión."
            )
        }

        val percentErrors = mutableListOf<Double>()
        val kgErrors = mutableListOf<Double>()

        for (index in 4 until ordered.size) {
            val train = ordered.subList(max(0, index - 8), index).map { it.consumedKg }
            if (train.size < 4) continue
            val prediction = robustPrediction(train)
            val actual = ordered[index].consumedKg
            val kgError = abs(prediction - actual)
            kgErrors += kgError

            // Evita porcentajes absurdos cuando una semana real fue casi cero.
            val scale = max(max(actual, median(train)), 1.0)
            percentErrors += (kgError / scale) * 100.0
        }

        if (kgErrors.isEmpty()) {
            return BacktestSignal(
                sampleCount = 0,
                meanAbsolutePercentError = null,
                meanAbsoluteKgError = null,
                qualityLabel = "Historial insuficiente",
                explanation = "No hubo suficientes periodos continuos para validar el cálculo."
            )
        }

        val mape = percentErrors.average()
        val mae = kgErrors.average()
        val label = when {
            mape <= 15.0 -> "Muy consistente"
            mape <= 25.0 -> "Consistente"
            mape <= 40.0 -> "Variable"
            else -> "Necesita más calibración"
        }
        val explanation = when {
            mape <= 15.0 -> "En las semanas probadas, el comportamiento histórico fue bastante predecible."
            mape <= 25.0 -> "La predicción histórica se mantiene razonablemente cerca del consumo real."
            mape <= 40.0 -> "El producto cambia bastante entre semanas; conviene dar más peso a la tendencia reciente."
            else -> "El producto cambia mucho o tiene consumo esporádico; el motor debe ser más conservador."
        }

        return BacktestSignal(
            sampleCount = kgErrors.size,
            meanAbsolutePercentError = mape,
            meanAbsoluteKgError = mae,
            qualityLabel = label,
            explanation = explanation
        )
    }

    private fun robustPrediction(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val trimmed = if (sorted.size >= 6) sorted.drop(1).dropLast(1) else sorted
        val base = trimmed.average()
        val recent = values.takeLast(minOf(3, values.size)).average()
        return base * 0.65 + recent * 0.35
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
