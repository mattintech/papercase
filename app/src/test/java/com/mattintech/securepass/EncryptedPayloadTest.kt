package com.mattintech.securepass

import com.mattintech.securepass.crypto.KeystoreHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class EncryptedPayloadTest {

    @Test
    fun testEncryptedPayloadEncodingDecoding() {
        val testCiphertext = "MySecretCiphertextBytes12345".toByteArray(Charsets.UTF_8)
        val testIv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)

        val ciphertextBase64 = Base64.getEncoder().encodeToString(testCiphertext)
        val ivBase64 = Base64.getEncoder().encodeToString(testIv)

        val payload = KeystoreHelper.EncryptedPayload(
            ciphertextBase64 = ciphertextBase64,
            ivBase64 = ivBase64
        )

        assertArrayEquals(testCiphertext, payload.ciphertext)
        assertArrayEquals(testIv, payload.iv)
        assertEquals(ciphertextBase64, payload.ciphertextBase64)
        assertEquals(ivBase64, payload.ivBase64)
    }
}
