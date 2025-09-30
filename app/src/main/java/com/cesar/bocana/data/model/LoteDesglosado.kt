package com.cesar.bocana.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Representa una porción de un lote que se utilizará en un traspaso.
 * Es crucial para desglosar una solicitud de traspaso en múltiples lotes de origen.
 *
 * @param lote El objeto StockLot original del que se está tomando producto.
 * @param cantidadATomarKg La cantidad exacta en Kilogramos que se tomará de este lote específico.
 * @param cantidadATomarUnidades La cantidad en unidades de empaque (Cajas, Costales, etc.) que se tomará. Es nulo si el producto es a granel.
 */
@Parcelize
data class LoteDesglosado(
    val lote: StockLot,
    val cantidadATomarKg: Double,
    val cantidadATomarUnidades: Double?
) : Parcelable
