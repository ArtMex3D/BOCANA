package com.cesar.bocana.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

/**
 * Clase de datos mejorada para la planificación de traspasos. Ahora utiliza LoteDesglosado
 * para manejar traspasos que se componen de múltiples lotes de origen.
 *
 * @param lotesParaTraspaso La lista detallada de qué lotes se usarán y cuánto se tomará de cada uno.
 * @param isRecalculating Flag para la UI, indica si este item está siendo procesado por el ViewModel.
 */
@Parcelize
data class TraspasoSugerenciaItem(
    val product: @RawValue Product, // Usar @RawValue si Product no es Parcelable
    var sugerenciaKg: Double,
    var lotesParaTraspaso: List<LoteDesglosado>, // <-- CAMBIO CLAVE: Usa la nueva clase para el desglose.
    var impactoStockMatriz: Double,
    var incluidoEnPdf: Boolean = sugerenciaKg > 0.0,
    var cantidadEditadaUnidades: Int = 0,
    var unidadDeEmpaqueEditada: String = "",
    var lotesSeleccionadosManualmente: @RawValue List<StockLot>? = null, // Se mantiene para recibir la selección del diálogo
    var isRecalculating: Boolean = false // Flag para mostrar el mini-loader en la UI.
) : Parcelable
