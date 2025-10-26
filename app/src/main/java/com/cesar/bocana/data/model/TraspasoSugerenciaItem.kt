// main/java/com/cesar/bocana/data/model/TraspasoSugerenciaItem.kt
package com.cesar.bocana.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import java.util.Date // Asegúrate de importar Date

/**
 * Clase de datos mejorada para la planificación de traspasos. Ahora utiliza LoteDesglosado
 * para manejar traspasos que se componen de múltiples lotes de origen.
 *
 * @param product El objeto Product completo con todos sus campos (ideal, maximo, categoria, etc.).
 * @param sugerenciaKg Los KG totales sugeridos (o recalculados tras edición manual).
 * @param lotesParaTraspaso La lista detallada de qué lotes se usarán y cuánto se tomará de cada uno.
 * @param impactoStockMatriz Stock estimado restante en Matriz después del traspaso sugerido.
 * @param incluidoEnPdf Si este item se incluirá en el PDF generado.
 * @param cantidadEditadaUnidades Cantidad en unidades (para Productos Fijos o para anotación PDF en Granel).
 * @param unidadDeEmpaqueEditada Unidad (para Productos Fijos o para anotación PDF en Granel).
 * @param lotesSeleccionadosManualmente Guarda la lista de StockLot seleccionada manualmente en el diálogo. Null si se usa FIFO/sugerencia.
 * @param isRecalculating Flag para la UI, indica si este item está siendo procesado por el ViewModel.
 * @param isSugerenciaLiquidacion NUEVO: Flag para indicar si la sugerencia incluye KGs extra para liquidar un lote (icono 💡).
 */
@Parcelize
data class TraspasoSugerenciaItem(
    val product: @RawValue Product, // Usar @RawValue si Product no es Parcelable
    var sugerenciaKg: Double,
    var lotesParaTraspaso: List<LoteDesglosado>,
    var impactoStockMatriz: Double,
    var incluidoEnPdf: Boolean = sugerenciaKg > 0.0, // Incluir por defecto si hay sugerencia
    var cantidadEditadaUnidades: Int = 0,
    var unidadDeEmpaqueEditada: String = "",
    var lotesSeleccionadosManualmente: @RawValue List<StockLot>? = null,
    var isRecalculating: Boolean = false,
    var isSugerenciaLiquidacion: Boolean = false // NUEVO Flag para icono 💡
) : Parcelable