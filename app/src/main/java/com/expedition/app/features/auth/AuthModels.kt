package com.expedition.app.features.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

data class User(
    val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val accountType: AccountType = AccountType.REGULAR,
    val isPro: Boolean = false,
    val isVerified: Boolean = false,
    val deviceHardwareId: String? = null,
    val hardwareKeyFingerprint: String? = null,
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
    data class VerificationRequired(
        val user: User,
        val verificationSentTo: String,
        val message: String
    ) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

/**
 * Hardware-bound cryptographic verifier using AndroidKeyStore.
 * Generates a non-exportable ECDSA keypair tied exclusively to this device's hardware keystore,
 * ensuring the Developer testing account can only be authenticated on this physical/virtual device.
 */
object DeviceHardwareIdentity {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val DEV_KEY_ALIAS = "expedition_device_exclusive_dev_key_v1"

    @SuppressLint("HardwareIds")
    fun getDeviceHardwareId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown_device"
    }

    /**
     * Ensures the hardware-backed ECDSA keypair exists in AndroidKeyStore, signs a challenge bound
     * to this device's ANDROID_ID, verifies the cryptographic signature, and returns the key fingerprint.
     */
    fun verifyAndGetHardwareKeyFingerprint(context: Context): Result<String> = runCatching {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (!keyStore.containsAlias(DEV_KEY_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                KEYSTORE_PROVIDER
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                DEV_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
        }

        val entry = keyStore.getEntry(DEV_KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val deviceId = getDeviceHardwareId(context)
        val challengeBytes = "EXPEDITION_DEV_AUTH:$deviceId".toByteArray(Charsets.UTF_8)

        // Sign challenge with non-exportable AndroidKeyStore private key
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(entry.privateKey)
            update(challengeBytes)
        }.sign()

        // Verify signature with device public certificate
        val verified = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(entry.certificate.publicKey)
            update(challengeBytes)
        }.verify(signature)

        if (!verified) {
            throw SecurityException("Hardware Keystore signature verification failed")
        }

        // Compute deterministic fingerprint of the public key + device ID
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(entry.certificate.publicKey.encoded)
        digest.update(deviceId.toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        val hex = hash.take(6).joinToString("") { "%02X".format(it) }
        "DEV-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}"
    }
}

class AuthManager(private val context: Context) {
    private val firebaseAuthManager = FirebaseAuthManager.getInstance(context.applicationContext)

    val currentUser: StateFlow<User?> = firebaseAuthManager.currentUser
    val isLoggedIn: StateFlow<Boolean> = firebaseAuthManager.isLoggedIn
    val pendingVerificationUser: StateFlow<User?> = firebaseAuthManager.pendingVerificationUser

    fun getDeviceFingerprintPreview(): String {
        return DeviceHardwareIdentity.verifyAndGetHardwareKeyFingerprint(context)
            .getOrElse { "DEV-${DeviceHardwareIdentity.getDeviceHardwareId(context).take(8).uppercase()}" }
    }

    suspend fun register(
        email: String,
        password: String,
        displayName: String,
        accountType: AccountType
    ): AuthResult {
        return firebaseAuthManager.register(email, password, displayName, accountType)
    }

    suspend fun login(email: String, password: String): AuthResult {
        return firebaseAuthManager.login(email, password)
    }

    /**
     * Authenticates using the device-exclusive AndroidKeyStore cryptographic identity.
     * Works both online and offline and provisions DEVELOPER + Pro access bound to this device.
     */
    suspend fun loginWithDeviceDeveloperAccount(): AuthResult {
        return firebaseAuthManager.loginWithDeviceDeveloperAccount(context)
    }

    suspend fun sendEmailVerification(): AuthResult {
        return firebaseAuthManager.sendEmailVerification()
    }

    suspend fun checkVerificationStatus(allowDevBypass: Boolean = false): AuthResult {
        return firebaseAuthManager.checkVerificationStatus(context, allowDevBypass)
    }

    fun logout() {
        firebaseAuthManager.logout(context)
    }

    suspend fun upgradeToPro(): AuthResult {
        return firebaseAuthManager.upgradeToPro(context)
    }
}
