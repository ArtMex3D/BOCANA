package com.cesar.bocana.data.model

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
    val fcmToken: String? = null // <-- CAMPO AÑADIDO PARA SOLUCIONAR EL ERROR

) {

    constructor() : this("", "", "", UserRole.ADMIN, true)
}