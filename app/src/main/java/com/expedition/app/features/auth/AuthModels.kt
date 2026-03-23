package com.expedition.app.features.auth

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

data class User(
    val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val accountType: AccountType = AccountType.REGULAR,
    val isPro: Boolean = false,
    val createdAt: Long = 0,
    val lastLoginAt: Long = 0,
    val status: String = "OFFLINE",
    val lastKnownLat: Double? = null,
    val lastKnownLon: Double? = null,
    val currentSpeed: Float = 0f,
    val friendIds: List<String> = emptyList()
)

enum class AccountType { REGULAR, SUPERVISOR, DEVELOPER }

sealed class AuthResult {
    data class Success(val user: User) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class AuthManager(private val context: Context) {
    private val firebaseAuthManager = FirebaseAuthManager()

    val currentUser: StateFlow<User?> = firebaseAuthManager.currentUser
    val isLoggedIn: StateFlow<Boolean> = firebaseAuthManager.isLoggedIn

    suspend fun register(email: String, password: String, displayName: String, accountType: AccountType): AuthResult {
        return firebaseAuthManager.register(email, password, displayName, accountType)
    }

    suspend fun login(email: String, password: String): AuthResult {
        return firebaseAuthManager.login(email, password)
    }

    fun logout() {
        firebaseAuthManager.logout()
    }

    suspend fun upgradeToPro(): AuthResult {
        return firebaseAuthManager.upgradeToPro()
    }
}
