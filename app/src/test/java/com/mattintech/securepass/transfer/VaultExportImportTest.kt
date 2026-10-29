package com.mattintech.securepass.transfer

import com.mattintech.securepass.locker.transfer.QrCodeHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultExportImportTest {

    @Test
    fun testGenerateSecureTokenFormat() {
        val token = QrCodeHelper.generateSecureToken()
        assertNotNull(token)
        assertTrue(token.startsWith("SPVT-"))
        // Check format: SPVT-XXXX-XXXX-XXXX-XXXX-XXXX
        val parts = token.split("-")
        assertEquals(6, parts.size)
        assertEquals("SPVT", parts[0])
        for (i in 1..5) {
            assertEquals(4, parts[i].length)
        }
    }

    @Test
    fun testQrCodeBitMatrixGeneration() {
        val originalToken = QrCodeHelper.generateSecureToken()
        val bitMatrix = QrCodeHelper.generateBitMatrix(originalToken, 256)
        assertNotNull(bitMatrix)
        assertEquals(256, bitMatrix.width)
        assertEquals(256, bitMatrix.height)
    }
}
