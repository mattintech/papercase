package com.mattintech.papercase.crypto

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

object BiometricAuthHelper {

    const val AUTH_BIOMETRIC = BiometricManager.Authenticators.BIOMETRIC_STRONG
    const val AUTH_ANY = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    sealed class SecurityLevel {
        object Biometric : SecurityLevel()
        object DeviceCredential : SecurityLevel()
        object NoneEnrolled : SecurityLevel()
        object NoHardware : SecurityLevel()
        object Unavailable : SecurityLevel()

        val isProtected: Boolean
            get() = this is Biometric || this is DeviceCredential
    }

    sealed class BiometricStatus {
        object Ready : BiometricStatus()
        object NoneEnrolled : BiometricStatus()
        object NoHardware : BiometricStatus()
        object Unavailable : BiometricStatus()
    }

    fun getDeviceSecurityLevel(context: Context): SecurityLevel {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(AUTH_ANY)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val canBio = manager.canAuthenticate(AUTH_BIOMETRIC) == BiometricManager.BIOMETRIC_SUCCESS
                if (canBio) SecurityLevel.Biometric else SecurityLevel.DeviceCredential
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> SecurityLevel.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> SecurityLevel.NoHardware
            else -> SecurityLevel.Unavailable
        }
    }

    fun checkBiometricAvailability(context: Context): BiometricStatus {
        return when (getDeviceSecurityLevel(context)) {
            SecurityLevel.Biometric, SecurityLevel.DeviceCredential -> BiometricStatus.Ready
            SecurityLevel.NoneEnrolled -> BiometricStatus.NoneEnrolled
            SecurityLevel.NoHardware -> BiometricStatus.NoHardware
            SecurityLevel.Unavailable -> BiometricStatus.Unavailable
        }
    }

    fun promptBiometricWithCrypto(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTH_ANY)
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val authenticatedCipher = result.cryptoObject?.cipher
                    onSuccess(authenticatedCipher ?: cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            }
        )

        val cryptoObject = BiometricPrompt.CryptoObject(cipher)
        try {
            biometricPrompt.authenticate(promptInfo, cryptoObject)
        } catch (_: Exception) {
            // If manufacturer ROM doesn't support CryptoObject with device credential, authenticate without CryptoObject
            try {
                biometricPrompt.authenticate(promptInfo)
            } catch (e: Exception) {
                onError(-1, e.message ?: "Authentication failed")
            }
        }
    }

    fun promptGeneralUnlock(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTH_ANY)
            .build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errorCode, errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            }
        )

        biometricPrompt.authenticate(promptInfo)
    }
}
