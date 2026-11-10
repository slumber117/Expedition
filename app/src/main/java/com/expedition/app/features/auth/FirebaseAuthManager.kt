package com.expedition.app.features.auth

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Manages user authentication, account verification UX, and hardware-bound
 * Device Developer authentication using Firebase + AndroidKeyStore.
 */
class FirebaseAuthManager private constructor(appContext: Context) {

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val db = Firebase.firestore
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _pendingVerificationUser = MutableStateFlow<User?>(null)
    val pendingVerificationUser: StateFlow<User?> = _pendingVerificationUser.asStateFlow()

    init {
        // 1. Check if this device has an active Hardware-Bound Developer session
        val devSessionActive = prefs.getBoolean(KEY_DEV_SESSION_ACTIVE, false)
        if (devSessionActive) {
            val verifiedFp = DeviceHardwareIdentity.verifyAndGetHardwareKeyFingerprint(appContext).getOrNull()
            val storedFp = prefs.getString(KEY_DEV_FINGERPRINT, null)
            if (verifiedFp != null && verifiedFp == storedFp) {
                val devUser = buildDeviceDeveloperUser(appContext, verifiedFp)
                _currentUser.value = devUser
                _isLoggedIn.value = true
            }
        }

        // 2. Listen for Firebase Auth state changes
        firebaseAuth.addAuthStateListener { auth ->
            val firebaseUser = auth.currentUser
            if (firebaseUser != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    val user = fromFirebaseUserSafe(firebaseUser)
                    if (user.isVerified || user.accountType == AccountType.DEVELOPER) {
                        _currentUser.value = user
                        _isLoggedIn.value = true
                    } else {
                        _pendingVerificationUser.value = user
                    }
                }
            } else if (!prefs.getBoolean(KEY_DEV_SESSION_ACTIVE, false)) {
                _currentUser.value = null
                _isLoggedIn.value = false
            }
        }
    }

    /**
     * Register a new user and trigger Email / Account Verification step
     */
    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        accountType: AccountType
    ): AuthResult {
        val cleanEmail = email.trim()
        val cleanName = displayName.trim()

        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return AuthResult.Error("Please enter a valid email address")
        }

        if (password.length < 6) {
            return AuthResult.Error("Password must be at least 6 characters")
        }

        if (cleanName.isBlank()) {
            return AuthResult.Error("Please enter a display name")
        }

        return try {
            val authResult = firebaseAuth.createUserWithEmailAndPassword(cleanEmail, password).await()
            val firebaseUser = authResult.user
            if (firebaseUser != null) {
                try {
                    val profileUpdates = userProfileChangeRequest {
                        this.displayName = cleanName
                    }
                    firebaseUser.updateProfile(profileUpdates).await()
                } catch (_: Exception) {}

                // Send verification email for the Account Verification UX step
                try {
                    firebaseUser.sendEmailVerification().await()
                } catch (_: Exception) {}

                val isDevAccount = accountType == AccountType.DEVELOPER
                val user = User(
                    id = firebaseUser.uid,
                    email = cleanEmail,
                    displayName = cleanName,
                    accountType = accountType,
                    isPro = isDevAccount,
                    isVerified = isDevAccount || firebaseUser.isEmailVerified,
                    createdAt = System.currentTimeMillis(),
                    lastLoginAt = System.currentTimeMillis()
                )

                // Best-effort Firestore profile write (never block registration if rules/network fail)
                try {
                    db.collection("users").document(firebaseUser.uid).set(user, SetOptions.merge()).await()
                } catch (_: Exception) {}

                if (user.isVerified) {
                    _currentUser.value = user
                    _isLoggedIn.value = true
                    AuthResult.Success(user)
                } else {
                    _pendingVerificationUser.value = user
                    AuthResult.VerificationRequired(
                        user = user,
                        verificationSentTo = cleanEmail,
                        message = "Verification link sent to $cleanEmail. Verify your email or use your Device Hardware Key below."
                    )
                }
            } else {
                AuthResult.Error("Registration failed: User not created")
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Registration failed. You can also use the Device Developer Account below.")
        }
    }

    /**
     * Login with email and password, enforcing the Account Verification UX step
     */
    suspend fun login(email: String, password: String): AuthResult {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) {
            return AuthResult.Error("Please enter your email")
        }

        if (password.isBlank()) {
            return AuthResult.Error("Please enter your password")
        }

        return try {
            val authResult = firebaseAuth.signInWithEmailAndPassword(cleanEmail, password).await()
            val firebaseUser = authResult.user
            if (firebaseUser != null) {
                try {
                    firebaseUser.reload().await()
                } catch (_: Exception) {}

                val user = fromFirebaseUserSafe(firebaseUser)
                if (user.isVerified || user.accountType == AccountType.DEVELOPER) {
                    _pendingVerificationUser.value = null
                    _currentUser.value = user
                    _isLoggedIn.value = true
                    AuthResult.Success(user)
                } else {
                    _pendingVerificationUser.value = user
                    AuthResult.VerificationRequired(
                        user = user,
                        verificationSentTo = cleanEmail,
                        message = "Account verification required for $cleanEmail."
                    )
                }
            } else {
                AuthResult.Error("Login failed: User not found")
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Login failed. Use Device Developer Key if testing on this device.")
        }
    }

    /**
     * Authenticates a Developer testing account bound exclusively to this device's AndroidKeyStore.
     * Only this physical/emulator device possesses the hardware private key required to pass verification.
     */
    suspend fun loginWithDeviceDeveloperAccount(context: Context): AuthResult {
        val fingerprintResult = DeviceHardwareIdentity.verifyAndGetHardwareKeyFingerprint(context)
        val fingerprint = fingerprintResult.getOrElse { err ->
            return AuthResult.Error("Hardware Key Verification Failed: ${err.message}")
        }

        val devUser = buildDeviceDeveloperUser(context, fingerprint)

        // Persist verified hardware session locally
        prefs.edit()
            .putBoolean(KEY_DEV_SESSION_ACTIVE, true)
            .putString(KEY_DEV_FINGERPRINT, fingerprint)
            .apply()

        // Opportunistically provision/sign-in matching Firebase developer identity in background when online
        CoroutineScope(Dispatchers.IO).launch {
            syncDeviceDeveloperWithFirebase(devUser, fingerprint)
        }

        _pendingVerificationUser.value = null
        _currentUser.value = devUser
        _isLoggedIn.value = true
        return AuthResult.Success(devUser)
    }

    private fun buildDeviceDeveloperUser(context: Context, fingerprint: String): User {
        val hardwareId = DeviceHardwareIdentity.getDeviceHardwareId(context)
        val shortId = hardwareId.take(8).lowercase()
        return User(
            id = "dev_hw_$shortId",
            email = "dev.$shortId@device.expedition.local",
            displayName = "Developer ($fingerprint)",
            accountType = AccountType.DEVELOPER,
            isPro = true,
            isVerified = true,
            deviceHardwareId = hardwareId,
            hardwareKeyFingerprint = fingerprint,
            createdAt = System.currentTimeMillis(),
            lastLoginAt = System.currentTimeMillis(),
            status = "ONLINE"
        )
    }

    private suspend fun syncDeviceDeveloperWithFirebase(devUser: User, fingerprint: String) {
        val derivedPassword = "ExpDevKey!${fingerprint.replace("-", "")}"
        try {
            val signInResult = try {
                firebaseAuth.signInWithEmailAndPassword(devUser.email, derivedPassword).await()
            } catch (_: Exception) {
                firebaseAuth.createUserWithEmailAndPassword(devUser.email, derivedPassword).await()
            }
            val fbUser = signInResult.user ?: return
            val syncedUser = devUser.copy(id = fbUser.uid)
            _currentUser.value = syncedUser
            db.collection("users").document(fbUser.uid).set(
                mapOf(
                    "id" to fbUser.uid,
                    "email" to devUser.email,
                    "displayName" to devUser.displayName,
                    "accountType" to AccountType.DEVELOPER.name,
                    "isPro" to true,
                    "is_pro" to true,
                    "isVerified" to true,
                    "deviceHardwareId" to devUser.deviceHardwareId,
                    "hardwareKeyFingerprint" to fingerprint,
                    "lastLoginAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
        } catch (_: Exception) {
            // Offline or restricted Firebase rules: local AndroidKeyStore developer session remains active
        }
    }

    /**
     * Resends the verification email to the currently pending Firebase user
     */
    suspend fun sendEmailVerification(): AuthResult {
        val fbUser = firebaseAuth.currentUser
        val pending = _pendingVerificationUser.value
        if (fbUser == null || pending == null) {
            return AuthResult.Error("No pending account to verify")
        }
        return try {
            fbUser.sendEmailVerification().await()
            AuthResult.VerificationRequired(
                user = pending,
                verificationSentTo = pending.email,
                message = "Verification email re-sent to ${pending.email}. Check your inbox."
            )
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to resend verification email")
        }
    }

    /**
     * Checks whether the user has completed email verification, or allows hardware-key verification
     * on this developer device.
     */
    suspend fun checkVerificationStatus(context: Context, allowDevBypass: Boolean): AuthResult {
        val fbUser = firebaseAuth.currentUser
        val pending = _pendingVerificationUser.value

        if (allowDevBypass) {
            val fp = DeviceHardwareIdentity.verifyAndGetHardwareKeyFingerprint(context).getOrNull()
            if (fp != null && pending != null) {
                val verifiedUser = pending.copy(
                    isVerified = true,
                    hardwareKeyFingerprint = fp,
                    deviceHardwareId = DeviceHardwareIdentity.getDeviceHardwareId(context)
                )
                prefs.edit()
                    .putBoolean("verified_${verifiedUser.id}", true)
                    .apply()
                try {
                    db.collection("users").document(verifiedUser.id)
                        .set(mapOf("isVerified" to true, "hardwareKeyFingerprint" to fp), SetOptions.merge())
                        .await()
                } catch (_: Exception) {}
                _pendingVerificationUser.value = null
                _currentUser.value = verifiedUser
                _isLoggedIn.value = true
                return AuthResult.Success(verifiedUser)
            }
        }

        if (fbUser != null) {
            try {
                fbUser.reload().await()
            } catch (_: Exception) {}
            if (fbUser.isEmailVerified) {
                val verifiedUser = (pending ?: fromFirebaseUserSafe(fbUser)).copy(isVerified = true)
                _pendingVerificationUser.value = null
                _currentUser.value = verifiedUser
                _isLoggedIn.value = true
                return AuthResult.Success(verifiedUser)
            }
        }

        return if (pending != null) {
            AuthResult.VerificationRequired(
                user = pending,
                verificationSentTo = pending.email,
                message = "Email not verified yet. Click the link in your email or use Hardware Key Verification below."
            )
        } else {
            AuthResult.Error("Could not verify account status")
        }
    }

    /**
     * Logout current user (clears both Firebase and Device Developer sessions)
     */
    fun logout(context: Context) {
        prefs.edit()
            .putBoolean(KEY_DEV_SESSION_ACTIVE, false)
            .remove(KEY_DEV_FINGERPRINT)
            .apply()
        _pendingVerificationUser.value = null
        _currentUser.value = null
        _isLoggedIn.value = false
        try {
            firebaseAuth.signOut()
        } catch (_: Exception) {}
    }

    private suspend fun fromFirebaseUserSafe(firebaseUser: FirebaseUser): User {
        val locallyVerified = prefs.getBoolean("verified_${firebaseUser.uid}", false)
        return try {
            val userDoc = db.collection("users").document(firebaseUser.uid).get().await()
            val user = userDoc.toObject<User>()
            if (user != null) {
                user.copy(
                    id = firebaseUser.uid,
                    isVerified = user.isVerified || firebaseUser.isEmailVerified || locallyVerified
                )
            } else {
                User(
                    id = firebaseUser.uid,
                    email = firebaseUser.email ?: "",
                    displayName = firebaseUser.displayName ?: (firebaseUser.email?.substringBefore("@") ?: "Rider"),
                    accountType = AccountType.REGULAR,
                    isPro = false,
                    isVerified = firebaseUser.isEmailVerified || locallyVerified,
                    createdAt = firebaseUser.metadata?.creationTimestamp ?: 0,
                    lastLoginAt = firebaseUser.metadata?.lastSignInTimestamp ?: 0
                )
            }
        } catch (_: Exception) {
            User(
                id = firebaseUser.uid,
                email = firebaseUser.email ?: "",
                displayName = firebaseUser.displayName ?: (firebaseUser.email?.substringBefore("@") ?: "Rider"),
                accountType = AccountType.REGULAR,
                isPro = false,
                isVerified = firebaseUser.isEmailVerified || locallyVerified,
                createdAt = firebaseUser.metadata?.creationTimestamp ?: 0,
                lastLoginAt = firebaseUser.metadata?.lastSignInTimestamp ?: 0
            )
        }
    }

    /**
     * Upgrade current user to Pro for £0 cost
     */
    suspend fun upgradeToPro(context: Context): AuthResult {
        val current = _currentUser.value ?: return AuthResult.Error("Not logged in")
        val updatedUser = current.copy(isPro = true)
        _currentUser.value = updatedUser
        return try {
            db.collection("users").document(current.id)
                .set(mapOf("is_pro" to true, "isPro" to true), SetOptions.merge())
                .await()
            AuthResult.Success(updatedUser)
        } catch (_: Exception) {
            // Still succeed locally for Developer / offline sessions
            AuthResult.Success(updatedUser)
        }
    }

    companion object {
        private const val PREFS_NAME = "expedition_auth_prefs"
        private const val KEY_DEV_SESSION_ACTIVE = "dev_session_active"
        private const val KEY_DEV_FINGERPRINT = "dev_hardware_fingerprint"

        @Volatile
        private var INSTANCE: FirebaseAuthManager? = null

        fun getInstance(context: Context): FirebaseAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FirebaseAuthManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
