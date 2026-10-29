package com.mattintech.papercase.crypto

import com.mattintech.papercase.locker.crypto.DocumentPasswordCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentPasswordCryptoTest {

    @Test
    fun testPasswordEncryptionAndDecryptionRoundTrip() {
        val password = "MySuperSecretDocumentPassword123!".toCharArray()
        val wrongPassword = "WrongPassword".toCharArray()
        val salt = DocumentPasswordCrypto.generateSalt()

        // 1. Generate DEK and derive key from password
        val passwordKey = DocumentPasswordCrypto.deriveKeyFromPassword(password, salt)
        val originalDek = DocumentPasswordCrypto.generateDek()

        // 2. Encrypt DEK
        val (encDek, iv) = DocumentPasswordCrypto.encryptDek(originalDek, passwordKey)

        // 3. Decrypt with correct password
        val decryptedDek = DocumentPasswordCrypto.decryptDek(encDek, iv, passwordKey)
        assertNotNull(decryptedDek)
        assertArrayEquals(originalDek.encoded, decryptedDek?.encoded)

        // 4. Try decrypting with wrong password -> must fail cleanly (return null)
        val wrongKey = DocumentPasswordCrypto.deriveKeyFromPassword(wrongPassword, salt)
        val failedDek = DocumentPasswordCrypto.decryptDek(encDek, iv, wrongKey)
        assertNull(failedDek)
    }

    @Test
    fun testDataEncryptionWithDek() {
        val dek = DocumentPasswordCrypto.generateDek()
        val sampleData = "Sensitive Passport Number: AB123456789".toByteArray(Charsets.UTF_8)

        val (ciphertext, iv) = DocumentPasswordCrypto.encryptWithDek(sampleData, dek)
        val decrypted = DocumentPasswordCrypto.decryptWithDek(ciphertext, iv, dek)

        assertArrayEquals(sampleData, decrypted)
        assertEquals("Sensitive Passport Number: AB123456789", String(decrypted, Charsets.UTF_8))
    }
}
