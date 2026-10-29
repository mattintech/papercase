package com.mattintech.securepass.locker.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The locker key needs a fresh biometric / device credential authentication. */
class LockerLockedException(cause: Throwable? = null) : Exception("Secure Locker is locked", cause)

/** The auth-bound locker key was destroyed by Android (e.g. the screen lock was removed). */
class LockerKeyInvalidatedException(cause: Throwable? = null) : Exception("Secure Locker key is no longer available", cause)

object LockerKeystoreHelper {

    /** Original key: hardware-backed, but usable without user authentication. */
    const val KEY_VERSION_LEGACY = 1

    /** Key that can only be used within [AUTH_VALIDITY_SECONDS] of a biometric / device credential unlock. */
    const val KEY_VERSION_AUTH_BOUND = 2

    /** Must outlast the in-app locker session (see SessionManager.MAX_AUTH_AGE_MS). */
    const val AUTH_VALIDITY_SECONDS = 600

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val LOCKER_KEY_ALIAS = "securepass_locker_master_key_v1"
    private const val LOCKER_KEY_ALIAS_AUTH_BOUND = "securepass_locker_master_key_v2"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private fun alias(version: Int) =
        if (version == KEY_VERSION_AUTH_BOUND) LOCKER_KEY_ALIAS_AUTH_BOUND else LOCKER_KEY_ALIAS

    private fun getKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun hasKey(version: Int): Boolean {
        return try {
            getKeyStore().containsAlias(alias(version))
        } catch (e: Exception) {
            false
        }
    }

    fun deleteKey(version: Int) {
        try {
            val keyStore = getKeyStore()
            if (keyStore.containsAlias(alias(version))) keyStore.deleteEntry(alias(version))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Creates the key for [version]. The auth-bound key can only be created on a device with a
     * secure screen lock; Android permanently invalidates it if that screen lock is later removed.
     */
    fun generateKey(version: Int) {
        deleteKey(version)

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            alias(version),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (version == KEY_VERSION_AUTH_BOUND) {
            builder.setUserAuthenticationRequired(true)
            builder.setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            builder.setUserAuthenticationRequired(false)
        }

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    private fun getSecretKey(version: Int): SecretKey {
        if (!hasKey(version)) {
            if (version == KEY_VERSION_AUTH_BOUND) throw LockerKeyInvalidatedException()
            generateKey(version)
        }
        return getKeyStore().getKey(alias(version), null) as? SecretKey
            ?: throw LockerKeyInvalidatedException()
    }

    private fun initCipher(mode: Int, version: Int, iv: ByteArray? = null): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            if (iv == null) {
                cipher.init(mode, getSecretKey(version))
            } else {
                cipher.init(mode, getSecretKey(version), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            }
        } catch (e: UserNotAuthenticatedException) {
            throw LockerLockedException(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw LockerKeyInvalidatedException(e)
        }
        return cipher
    }

    fun encryptBytes(data: ByteArray, version: Int): Pair<ByteArray, ByteArray> {
        val cipher = initCipher(Cipher.ENCRYPT_MODE, version)
        val ciphertext = cipher.doFinal(data)
        return Pair(ciphertext, cipher.iv)
    }

    fun decryptBytes(ciphertext: ByteArray, iv: ByteArray, version: Int): ByteArray {
        return initCipher(Cipher.DECRYPT_MODE, version, iv).doFinal(ciphertext)
    }

    fun encryptString(plaintext: String, version: Int): Pair<String, String> {
        val (cipherBytes, ivBytes) = encryptBytes(plaintext.toByteArray(Charsets.UTF_8), version)
        val cipherBase64 = Base64.getEncoder().encodeToString(cipherBytes)
        val ivBase64 = Base64.getEncoder().encodeToString(ivBytes)
        return Pair(cipherBase64, ivBase64)
    }

    fun decryptString(cipherBase64: String, ivBase64: String, version: Int): String {
        val cipherBytes = Base64.getDecoder().decode(cipherBase64)
        val ivBytes = Base64.getDecoder().decode(ivBase64)
        val decryptedBytes = decryptBytes(cipherBytes, ivBytes, version)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
