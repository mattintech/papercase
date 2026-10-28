package com.mattintech.securepass

import com.mattintech.securepass.locker.model.DocumentAttachment
import com.mattintech.securepass.locker.model.DocumentType
import com.mattintech.securepass.locker.model.SecureDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SecureDocumentTest {

    @Test
    fun testSecureDocumentJsonSerialization() {
        val attachment = DocumentAttachment(
            fileName = "passport_scan.jpg",
            mimeType = "image/jpeg",
            encryptedFileName = "uuid_file.bin",
            ivBase64 = "randomIV123456",
            sizeBytes = 204800L
        )

        val doc = SecureDocument(
            title = "Canadian Passport",
            type = DocumentType.PASSPORT,
            documentNumber = "AB123456",
            expirationDate = "2029-10-15",
            issuingCountry = "Canada",
            notes = "Stored safely",
            attachments = listOf(attachment)
        )

        val json = doc.toJson()
        val restored = SecureDocument.fromJson(json)

        assertEquals(doc.id, restored.id)
        assertEquals("Canadian Passport", restored.title)
        assertEquals(DocumentType.PASSPORT, restored.type)
        assertEquals("AB123456", restored.documentNumber)
        assertEquals("2029-10-15", restored.expirationDate)
        assertEquals("Canada", restored.issuingCountry)
        assertEquals(1, restored.attachments.size)

        val restoredAtt = restored.attachments.first()
        assertEquals("passport_scan.jpg", restoredAtt.fileName)
        assertEquals("image/jpeg", restoredAtt.mimeType)
        assertEquals(204800L, restoredAtt.sizeBytes)
    }
}
