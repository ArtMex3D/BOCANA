package com.cesar.bocana.data.model

enum class UserRole {
    ADMIN,
}

data class User(
    val uid: String = "",
    val email: String = "",
    val name: String = "",
        val role: UserRole = UserRole.ADMIN,
    val isAccountActive: Boolean = true
) {

    constructor() : this("", "", "", UserRole.ADMIN, true)
}