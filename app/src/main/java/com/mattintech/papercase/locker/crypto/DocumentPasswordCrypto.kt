package com.mattintech.papercase.locker.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object DocumentPasswordCrypto {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 15000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun generateDek(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    fun deriveKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptDek(dek: SecretKey, passwordKey: SecretKey): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, passwordKey)
        val encrypted = cipher.doFinal(dek.encoded)
        val iv = cipher.iv

        return Pair(
            Base64.getEncoder().encodeToString(encrypted),
            Base64.getEncoder().encodeToString(iv)
        )
    }

    fun decryptDek(encryptedDekBase64: String, ivBase64: String, passwordKey: SecretKey): SecretKey? {
        return try {
            val encryptedBytes = Base64.getDecoder().decode(encryptedDekBase64)
            val ivBytes = Base64.getDecoder().decode(ivBase64)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, passwordKey, spec)

            val dekBytes = cipher.doFinal(encryptedBytes)
            SecretKeySpec(dekBytes, "AES")
        } catch (_: AEADBadTagException) {
            // Wrong password
            null
        } catch (e: Exception) {
            null
        }
    }

    fun encryptWithDek(data: ByteArray, dek: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dek)
        val ciphertext = cipher.doFinal(data)
        return Pair(ciphertext, cipher.iv)
    }

    fun decryptWithDek(ciphertext: ByteArray, iv: ByteArray, dek: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, dek, spec)
        return cipher.doFinal(ciphertext)
    }
}
