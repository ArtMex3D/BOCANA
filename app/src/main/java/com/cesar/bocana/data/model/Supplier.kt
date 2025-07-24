package com.cesar.bocana.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.JvmField

@Entity(tableName = "suppliers")
data class Supplier(
    @PrimaryKey
    @DocumentId val id: String = "",
    val name: String = "",
    val contactPerson: String? = null,
    val phone: String? = null,
    val email: String? = null,
    @JvmField
    var isActive: Boolean = true,
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
) {
    constructor() : this("", "", null, null, null, true, null, null)
}