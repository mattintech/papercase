package com.mattintech.securepass

import com.mattintech.securepass.locker.model.AttachmentSlot
import com.mattintech.securepass.locker.model.DocumentAttachment
import com.mattintech.securepass.locker.model.DocumentType
import com.mattintech.securepass.locker.model.SecureDocument
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SecureDocumentTest {

    @Test
    fun testSecureDocumentJsonSerialization() {
        val front = DocumentAttachment(
            fileName = "passport_front.jpg",
            mimeType = "image/jpeg",
            encryptedFileName = "uuid_front.bin",
            ivBase64 = "randomIV123456",
            sizeBytes = 204800L,
            slot = AttachmentSlot.FRONT
        )

        val back = DocumentAttachment(
            fileName = "passport_back.jpg",
            mimeType = "image/jpeg",
            encryptedFileName = "uuid_back.bin",
            ivBase64 = "randomIV654321",
            sizeBytes = 180000L,
            slot = AttachmentSlot.BACK
        )

        val visa = DocumentAttachment(
            fileName = "us_visa.jpg",
            mimeType = "image/jpeg",
            encryptedFileName = "uuid_visa.bin",
            ivBase64 = "randomIV999888",
            sizeBytes = 350000L,
            slot = AttachmentSlot.VISA,
            customTitle = "US Visa B1/B2"
        )

        val doc = SecureDocument(
            title = "Canadian Passport",
            type = DocumentType.PASSPORT,
            documentNumber = "AB123456",
            expirationDate = "2029-10-15",
            issuingCountry = "Canada",
            notes = "Stored safely",
            attachments = listOf(front, back, visa)
        )

        val json = doc.toJson()
        val restored = SecureDocument.fromJson(json)

        assertEquals(doc.id, restored.id)
        assertEquals("Canadian Passport", restored.title)
        assertEquals(DocumentType.PASSPORT, restored.type)
        assertEquals("AB123456", restored.documentNumber)
        assertEquals("2029-10-15", restored.expirationDate)
        assertEquals("Canada", restored.issuingCountry)
        assertEquals(3, restored.attachments.size)

        assertNotNull(restored.getFrontAttachment())
        assertEquals(AttachmentSlot.FRONT, restored.getFrontAttachment()?.slot)

        assertNotNull(restored.getBackAttachment())
        assertEquals(AttachmentSlot.BACK, restored.getBackAttachment()?.slot)

        assertEquals(1, restored.getVisaAttachments().size)
        val restoredVisa = restored.getVisaAttachments().first()
        assertEquals(AttachmentSlot.VISA, restoredVisa.slot)
        assertEquals("US Visa B1/B2", restoredVisa.customTitle)
        assertEquals("US Visa B1/B2", restoredVisa.getDisplayTitle())

        assertEquals("Front & Back • 1 Visa", restored.getSummaryText())
    }

    @Test
    fun testBackwardCompatibilityOldJsonWithoutSlot() {
        // Simulates JSON created before slot and customTitle were added
        val oldJson = JSONObject().apply {
            put("id", "old-doc-123")
            put("title", "Ontario Driver License")
            put("type", "DRIVERS_LICENSE")
            put("documentNumber", "D1234-56789-01234")
            val attachmentsArray = org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "att-1")
                    put("fileName", "license.jpg")
                    put("mimeType", "image/jpeg")
                    put("encryptedFileName", "enc_1.bin")
                    put("ivBase64", "iv123")
                    put("sizeBytes", 1024L)
                    // "slot" and "customTitle" omitted intentionally
                })
            }
            put("attachments", attachmentsArray)
        }

        val restored = SecureDocument.fromJson(oldJson)
        assertEquals("Ontario Driver License", restored.title)
        assertEquals(1, restored.attachments.size)

        val att = restored.attachments.first()
        assertEquals(AttachmentSlot.FRONT, att.slot) // Defaults gracefully to FRONT
        assertEquals("", att.customTitle)
        assertEquals("Front Side", att.getDisplayTitle())
        assertEquals("Front", restored.getSummaryText())
    }

    @Test
    fun testSummaryTextVariations() {
        val frontOnly = SecureDocument(
            title = "ID Card",
            type = DocumentType.ID_CARD,
            attachments = listOf(
                DocumentAttachment(
                    fileName = "f.jpg", mimeType = "image/jpeg", encryptedFileName = "1", ivBase64 = "1", sizeBytes = 1,
                    slot = AttachmentSlot.FRONT
                )
            )
        )
        assertEquals("Front", frontOnly.getSummaryText())

        val frontAndBack = SecureDocument(
            title = "ID Card",
            type = DocumentType.ID_CARD,
            attachments = listOf(
                DocumentAttachment(
                    fileName = "f.jpg", mimeType = "image/jpeg", encryptedFileName = "1", ivBase64 = "1", sizeBytes = 1,
                    slot = AttachmentSlot.FRONT
                ),
                DocumentAttachment(
                    fileName = "b.jpg", mimeType = "image/jpeg", encryptedFileName = "2", ivBase64 = "2", sizeBytes = 1,
                    slot = AttachmentSlot.BACK
                )
            )
        )
        assertEquals("Front & Back", frontAndBack.getSummaryText())

        val passportWithVisas = SecureDocument(
            title = "Passport",
            type = DocumentType.PASSPORT,
            attachments = listOf(
                DocumentAttachment(
                    fileName = "f.jpg", mimeType = "image/jpeg", encryptedFileName = "1", ivBase64 = "1", sizeBytes = 1,
                    slot = AttachmentSlot.FRONT
                ),
                DocumentAttachment(
                    fileName = "b.jpg", mimeType = "image/jpeg", encryptedFileName = "2", ivBase64 = "2", sizeBytes = 1,
                    slot = AttachmentSlot.BACK
                ),
                DocumentAttachment(
                    fileName = "v1.jpg", mimeType = "image/jpeg", encryptedFileName = "3", ivBase64 = "3", sizeBytes = 1,
                    slot = AttachmentSlot.VISA, customTitle = "US Visa"
                ),
                DocumentAttachment(
                    fileName = "v2.jpg", mimeType = "image/jpeg", encryptedFileName = "4", ivBase64 = "4", sizeBytes = 1,
                    slot = AttachmentSlot.VISA, customTitle = "Schengen Visa"
                )
            )
        )
        assertEquals("Front & Back • 2 Visas", passportWithVisas.getSummaryText())
    }
}
