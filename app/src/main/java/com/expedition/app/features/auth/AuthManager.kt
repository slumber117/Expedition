package com.expedition.app.features.auth

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

/**
 * User account types
 */
enum class AccountType {
    REGULAR,
    ADMIN
}

/**
 * User data model
 */
data class User(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val passwordHash: String,
    val displayName: String,
    val accountType: AccountType = AccountType.REGULAR,
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long = System.currentTimeMillis()
)

/**
 * Authentication result
 */
sealed class AuthResult {
    data class Success(val user: User) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

/**
 * Manages user authentication, registration, and session state
 */
class AuthManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("expedition_auth", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()
    
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    
    init {
        // Check for existing session
        loadCurrentSession()
    }
    
    /**
     * Register a new user
     */
    fun register(
        email: String,
        password: String,
        confirmPassword: String,
        displayName: String,
        accountType: AccountType
    ): AuthResult {
        // Validation
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult.Error("Please enter a valid email address")
        }
        
        if (password.length < 6) {
            return AuthResult.Error("Password must be at least 6 characters")
        }
        
        if (password != confirmPassword) {
            return AuthResult.Error("Passwords do not match")
        }
        
        if (displayName.isBlank()) {
            return AuthResult.Error("Please enter a display name")
        }
        
        // Check if email already exists
        val existingUsers = getAllUsers()
        if (existingUsers.any { it.email.equals(email, ignoreCase = true) }) {
            return AuthResult.Error("An account with this email already exists")
        }
        
        // Create user
        val user = User(
            email = email.lowercase().trim(),
            passwordHash = hashPassword(password),
            displayName = displayName.trim(),
            accountType = accountType
        )
        
        // Save user
        saveUser(user)
        
        // Log in
        setCurrentUser(user)
        
        return AuthResult.Success(user)
    }
    
    /**
     * Login with email and password
     */
    fun login(email: String, password: String): AuthResult {
        if (email.isBlank()) {
            return AuthResult.Error("Please enter your email")
        }
        
        if (password.isBlank()) {
            return AuthResult.Error("Please enter your password")
        }
        
        val users = getAllUsers()
        val user = users.find { it.email.equals(email.trim(), ignoreCase = true) }
        
        if (user == null) {
            return AuthResult.Error("No account found with this email")
        }
        
        if (user.passwordHash != hashPassword(password)) {
            return AuthResult.Error("Incorrect password")
        }
        
        // Update last login time
        val updatedUser = user.copy(lastLoginAt = System.currentTimeMillis())
        saveUser(updatedUser)
        setCurrentUser(updatedUser)
        
        return AuthResult.Success(updatedUser)
    }
    
    /**
     * Logout current user
     */
    fun logout() {
        _currentUser.value = null
        _isLoggedIn.value = false
        prefs.edit().remove("current_user_id").apply()
    }
    
    /**
     * Check if current user is admin
     */
    fun isAdmin(): Boolean {
        return _currentUser.value?.accountType == AccountType.ADMIN
    }
    
    /**
     * Update user profile
     */
    fun updateProfile(displayName: String): AuthResult {
        val current = _currentUser.value ?: return AuthResult.Error("Not logged in")
        
        val updated = current.copy(displayName = displayName.trim())
        saveUser(updated)
        _currentUser.value = updated
        
        return AuthResult.Success(updated)
    }
    
    /**
     * Change password
     */
    fun changePassword(currentPassword: String, newPassword: String, confirmPassword: String): AuthResult {
        val current = _currentUser.value ?: return AuthResult.Error("Not logged in")
        
        if (current.passwordHash != hashPassword(currentPassword)) {
            return AuthResult.Error("Current password is incorrect")
        }
        
        if (newPassword.length < 6) {
            return AuthResult.Error("New password must be at least 6 characters")
        }
        
        if (newPassword != confirmPassword) {
            return AuthResult.Error("New passwords do not match")
        }
        
        val updated = current.copy(passwordHash = hashPassword(newPassword))
        saveUser(updated)
        _currentUser.value = updated
        
        return AuthResult.Success(updated)
    }
    
    /**
     * Delete account
     */
    fun deleteAccount(): Boolean {
        val current = _currentUser.value ?: return false
        
        val users = getAllUsers().filter { it.id != current.id }
        saveAllUsers(users)
        logout()
        
        return true
    }
    
    /**
     * Get all users (admin only)
     */
    fun getAllUsersAdmin(): List<User>? {
        if (!isAdmin()) return null
        return getAllUsers()
    }
    
    private fun setCurrentUser(user: User) {
        _currentUser.value = user
        _isLoggedIn.value = true
        prefs.edit().putString("current_user_id", user.id).apply()
    }
    
    private fun loadCurrentSession() {
        val userId = prefs.getString("current_user_id", null) ?: return
        val users = getAllUsers()
        val user = users.find { it.id == userId }
        
        if (user != null) {
            _currentUser.value = user
            _isLoggedIn.value = true
        }
    }
    
    private fun getAllUsers(): List<User> {
        val json = prefs.getString("users", "[]") ?: "[]"
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<User>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun saveUser(user: User) {
        val users = getAllUsers().toMutableList()
        val index = users.indexOfFirst { it.id == user.id }
        
        if (index >= 0) {
            users[index] = user
        } else {
            users.add(user)
        }
        
        saveAllUsers(users)
    }
    
    private fun saveAllUsers(users: List<User>) {
        prefs.edit().putString("users", gson.toJson(users)).apply()
    }
    
    private fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
