package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.JvmField // Importar anotación

data class Supplier(
    @DocumentId val id: String = "",
    val name: String = "",
    val contactPerson: String? = null,
    val phone: String? = null,
    val email: String? = null,
    // Añadir @JvmField
    @JvmField
    var isActive: Boolean = true,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    constructor() : this("", "", null, null, null, true, null, null)
}