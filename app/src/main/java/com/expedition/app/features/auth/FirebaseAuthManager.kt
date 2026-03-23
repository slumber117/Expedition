package com.expedition.app.features.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Manages user authentication, registration, and session state using Firebase
 */
class FirebaseAuthManager {

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    init {
        // Check for existing session
        firebaseAuth.addAuthStateListener { auth ->
            val firebaseUser = auth.currentUser
            if (firebaseUser != null) {
                // User is signed in, load user data from Firestore
                // This part needs to be implemented to fetch user data and update _currentUser
            } else {
                _currentUser.value = null
                _isLoggedIn.value = false
            }
        }
    }

    /**
     * Register a new user
     */
    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        accountType: AccountType
    ): AuthResult {
        if (email.isBlank() || !email.contains("@")) {
            return AuthResult.Error("Please enter a valid email address")
        }

        if (password.length < 6) {
            return AuthResult.Error("Password must be at least 6 characters")
        }

        if (displayName.isBlank()) {
            return AuthResult.Error("Please enter a display name")
        }

        return try {
            val authResult = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user
            if (firebaseUser != null) {
                val profileUpdates = userProfileChangeRequest {
                    this.displayName = displayName
                }
                firebaseUser.updateProfile(profileUpdates).await()

                // Create user in Firestore
                val user = User(
                    id = firebaseUser.uid,
                    email = email,
                    displayName = displayName,
                    accountType = accountType,
                    createdAt = System.currentTimeMillis(),
                    lastLoginAt = System.currentTimeMillis()
                )
                db.collection("users").document(firebaseUser.uid).set(user).await()

                AuthResult.Success(user)
            } else {
                AuthResult.Error("Registration failed: User not created")
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "An unknown error occurred")
        }
    }

    /**
     * Login with email and password
     */
    suspend fun login(email: String, password: String): AuthResult {
        if (email.isBlank()) {
            return AuthResult.Error("Please enter your email")
        }

        if (password.isBlank()) {
            return AuthResult.Error("Please enter your password")
        }

        return try {
            val authResult = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user
            if (firebaseUser != null) {
                val user = fromFirebaseUser(firebaseUser)
                _currentUser.value = user
                _isLoggedIn.value = true
                AuthResult.Success(user)
            } else {
                AuthResult.Error("Login failed: User not found")
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "An unknown error occurred")
        }
    }

    /**
     * Logout current user
     */
    fun logout() {
        firebaseAuth.signOut()
    }

    private suspend fun fromFirebaseUser(firebaseUser: FirebaseUser): User {
        val userDoc = db.collection("users").document(firebaseUser.uid).get().await()
        val user = userDoc.toObject<User>()
        return user ?: User(
            id = firebaseUser.uid,
            email = firebaseUser.email ?: "",
            displayName = firebaseUser.displayName ?: "",
            accountType = AccountType.REGULAR, // Default value
            isPro = false,
            createdAt = firebaseUser.metadata?.creationTimestamp ?: 0,
            lastLoginAt = firebaseUser.metadata?.lastSignInTimestamp ?: 0
        )
    }
    
    /**
     * Upgrade current user to Pro for £0 cost by updating Firestore directly
     */
    suspend fun upgradeToPro(): AuthResult {
        val current = _currentUser.value
        if (current == null) {
            return AuthResult.Error("Not logged in")
        }
        return try {
            // Update Firestore is_pro field
            db.collection("users").document(current.id).update("is_pro", true).await()
            val updatedUser = current.copy(isPro = true)
            _currentUser.value = updatedUser
            AuthResult.Success(updatedUser)
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Could not update Pro status")
        }
    }
}
