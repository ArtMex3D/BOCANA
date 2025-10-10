package com.cesar.bocana.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import java.util.Date

/**
 * Representa una porción de un lote que se utilizará en un traspaso.
 * AHORA ALMACENA SOLO EL ID DEL LOTE Y DATOS PRIMITIVOS PARA EVITAR ERRORES DE SERIALIZACIÓN.
 */
@Parcelize
data class LoteDesglosado(
    val loteId: String = "",
    val cantidadATomarKg: Double = 0.0,
    val cantidadATomarUnidades: Double? = null,
    // Se guardan datos primitivos para mostrar en la UI sin necesidad de leer el lote completo.
    val loteFecha: @RawValue Date? = null,
    val loteProveedor: String? = null,
    val loteUnidad: String? = null,
    val lotePesoPorUnidad: Double? = null,
    // El objeto lote completo solo se usa temporalmente en la UI, no se guarda en Firestore.
    @Transient val lote: @RawValue StockLot? = null
) : Parcelable
