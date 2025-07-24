package com.cesar.bocana.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.JvmField
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import androidx.room.Index

@Parcelize
@Entity(
    tableName = "products",
    indices = [Index(value = ["name"], unique = false)]  // Índice para búsqueda por nombre
)
data class Product(
    @PrimaryKey
    @DocumentId
    val id: String = "",
    val name: String = "",
    val unit: String = "",
    val minStock: Double = 0.0,
    val providerDetails: String = "",
    val stockMatriz: Double = 0.0,
    val stockCongelador04: Double = 0.0,
    val totalStock: Double = 0.0,
    @ServerTimestamp
    val createdAt: Date? = null,
    @ServerTimestamp
    val updatedAt: Date? = null,
    val lastUpdatedByName: String? = null,
    @JvmField
    val isActive: Boolean = true,
    @JvmField
    val requiresPackaging: Boolean = false,
    val labelConfig: @RawValue Map<String, Any>? = null
) : Parcelable {
    constructor() : this(
        id = "", name = "", unit = "", minStock = 0.0, providerDetails = "",
        stockMatriz = 0.0, stockCongelador04 = 0.0, totalStock = 0.0,
        createdAt = null, updatedAt = null, lastUpdatedByName = null, isActive = true,
        requiresPackaging = false,
        labelConfig = null
    )
}