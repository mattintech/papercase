package com.mattintech.papercase.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeystoreHelper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "papercase_master_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    data class EncryptedPayload(
        val ciphertextBase64: String,
        val ivBase64: String
    ) {
        val ciphertext: ByteArray
            get() = Base64.getDecoder().decode(ciphertextBase64)

        val iv: ByteArray
            get() = Base64.getDecoder().decode(ivBase64)
    }

    private fun getKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    }

    fun hasKey(): Boolean {
        return try {
            getKeyStore().containsAlias(KEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    fun deleteKey() {
        try {
            val keyStore = getKeyStore()
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun generateKey(requireUserAuth: Boolean = true) {
        deleteKey()

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (requireUserAuth) {
            builder.setUserAuthenticationRequired(true)
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            builder.setUserAuthenticationRequired(false)
        }

        try {
            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        } catch (_: Exception) {
            if (requireUserAuth) {
                // If device has no screen lock enrolled, fallback to generating without user auth
                generateKey(requireUserAuth = false)
            }
        }
    }

    private fun getSecretKey(): SecretKey {
        if (!hasKey()) {
            generateKey()
        }
        val keyStore = getKeyStore()
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun initEncryptCipher(): Cipher {
        val key = getSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    fun initDecryptCipher(iv: ByteArray): Cipher {
        val key = getSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher
    }

    fun encryptWithCipher(cipher: Cipher, plaintext: String): EncryptedPayload {
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return EncryptedPayload(
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            ivBase64 = Base64.getEncoder().encodeToString(iv)
        )
    }

    fun decryptWithCipher(cipher: Cipher, ciphertext: ByteArray): String {
        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, Charsets.UTF_8)
    }
}
