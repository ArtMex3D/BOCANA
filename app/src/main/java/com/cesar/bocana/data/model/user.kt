
package com.cesar.bocana.data.model

import java.util.Date

enum class UserRole {
    ADMIN,
}

data class User(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
        val role: UserRole = UserRole.ADMIN,
    @JvmField // <--- AÑADE ESTA LÍNEA
    val isAccountActive: Boolean = true,
    val fcmToken: String? = null,
    // Campo existente en Firestore; se conserva para evitar desajustes de mapeo.
    val lastLogin: Date? = null

) {

    constructor() : this("", "", "", UserRole.ADMIN, true)
}

