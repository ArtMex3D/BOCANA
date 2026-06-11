package com.cesar.bocana.data.model

import android.os.Parcelable
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import java.util.Date

enum class TraspasoEstado {
    PENDIENTE,
    CONFIRMADO,
    CANCELADO
}

@Parcelize
data class TraspasoPlanificado(
    @DocumentId val id: String = "",
    @ServerTimestamp val createdAt: Date? = null,
    val createdBy: String = "",
    val fechaPlan: Date? = null,
    var estado: TraspasoEstado = TraspasoEstado.PENDIENTE,
    @ServerTimestamp var confirmedAt: Date? = null,
    var confirmedBy: String? = null
) : Parcelable

@Parcelize
data class DetalleTraspasoPlan(
    @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val sugerenciaKg: Double = 0.0,
    val sugerenciaUnidades: Int = 0,
    val unidadDeEmpaque: String = "",
    val lotesSugeridos: @RawValue List<LoteDesglosado> = emptyList(),
    val orden: Int = 0
) : Parcelable