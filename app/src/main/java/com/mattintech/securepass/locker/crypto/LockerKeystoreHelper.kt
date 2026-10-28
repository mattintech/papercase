package com.mattintech.securepass.locker.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object LockerKeystoreHelper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val LOCKER_KEY_ALIAS = "securepass_locker_master_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH_BYTES = 12

    private fun getKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun hasKey(): Boolean {
        return try {
            getKeyStore().containsAlias(LOCKER_KEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    fun generateKey() {
        val keyStore = getKeyStore()
        if (keyStore.containsAlias(LOCKER_KEY_ALIAS)) {
            keyStore.deleteEntry(LOCKER_KEY_ALIAS)
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            LOCKER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false) // Hardware protected AES key in TEE for file operations
            .build()

        keyGenerator.init(builder)
        keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        if (!hasKey()) {
            generateKey()
        }
        return getKeyStore().getKey(LOCKER_KEY_ALIAS, null) as SecretKey
    }

    fun encryptBytes(data: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(data)
        return Pair(ciphertext, cipher.iv)
    }

    fun decryptBytes(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val key = getSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    fun encryptString(plaintext: String): Pair<String, String> {
        val (cipherBytes, ivBytes) = encryptBytes(plaintext.toByteArray(Charsets.UTF_8))
        val cipherBase64 = Base64.getEncoder().encodeToString(cipherBytes)
        val ivBase64 = Base64.getEncoder().encodeToString(ivBytes)
        return Pair(cipherBase64, ivBase64)
    }

    fun decryptString(cipherBase64: String, ivBase64: String): String {
        val cipherBytes = Base64.getDecoder().decode(cipherBase64)
        val ivBytes = Base64.getDecoder().decode(ivBase64)
        val decryptedBytes = decryptBytes(cipherBytes, ivBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
