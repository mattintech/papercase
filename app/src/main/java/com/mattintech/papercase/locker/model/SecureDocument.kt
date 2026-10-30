package com.mattintech.papercase.locker.model

import com.mattintech.papercase.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class DocumentType(val displayName: String, val iconRes: Int) {
    PASSPORT("Passport", R.drawable.ic_shield_lock),
    DRIVERS_LICENSE("Driver's License", R.drawable.ic_badge),
    ID_CARD("National ID Card", R.drawable.ic_badge),
    INSURANCE("Insurance / Health Card", R.drawable.ic_shield_check),
    PDF_DOCUMENT("Document / PDF", R.drawable.ic_description),
    OTHER("Secure Item", R.drawable.ic_folder_lock);

    companion object {
        fun fromString(name: String): DocumentType {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: OTHER
        }
    }
}

enum class AttachmentSlot(val displayName: String) {
    FRONT("Front"),
    BACK("Back"),
    VISA("Visa"),
    OTHER("Other");

    companion object {
        fun fromString(name: String): AttachmentSlot {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: OTHER
        }
    }
}

data class DocumentAttachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val mimeType: String,
    val encryptedFileName: String,
    val ivBase64: String,
    val sizeBytes: Long,
    val slot: AttachmentSlot = AttachmentSlot.FRONT,
    val customTitle: String = ""
) {
    val isPdf: Boolean
        get() = mimeType.contains("pdf", ignoreCase = true) || fileName.endsWith(".pdf", ignoreCase = true)

    fun getDisplayTitle(): String {
        if (customTitle.isNotBlank()) return customTitle
        return when (slot) {
            AttachmentSlot.FRONT -> "Front Side"
            AttachmentSlot.BACK -> "Back Side"
            AttachmentSlot.VISA -> "Visa"
            AttachmentSlot.OTHER -> fileName
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("encryptedFileName", encryptedFileName)
            put("ivBase64", ivBase64)
            put("sizeBytes", sizeBytes)
            put("slot", slot.name)
            put("customTitle", customTitle)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DocumentAttachment {
            val slotString = json.optString("slot", "")
            val slot = if (slotString.isNotBlank()) {
                AttachmentSlot.fromString(slotString)
            } else {
                AttachmentSlot.FRONT
            }

            return DocumentAttachment(
                id = json.optString("id", UUID.randomUUID().toString()),
                fileName = json.optString("fileName", "attachment"),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                encryptedFileName = json.optString("encryptedFileName", ""),
                ivBase64 = json.optString("ivBase64", ""),
                sizeBytes = json.optLong("sizeBytes", 0L),
                slot = slot,
                customTitle = json.optString("customTitle", "")
            )
        }
    }
}

data class SecureDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val type: DocumentType,
    val documentNumber: String = "",
    val expirationDate: String = "",
    val issuingCountry: String = "",
    val notes: String = "",
    val attachments: List<DocumentAttachment> = emptyList(),
    val isPasswordProtected: Boolean = false,
    val passwordSaltBase64: String = "",
    val encryptedDekBase64: String = "",
    val dekIvBase64: String = "",
    /** Days before [expirationDate] to send a reminder; 0 = no reminder. */
    val reminderDays: Int = 0,
    /** Chosen icon ([DocumentIcons] key); blank = the document type's icon. */
    val iconKey: String = "",
    /** Chosen icon colour ([DocumentIcons] colour key); blank = the app's accent colour. */
    val iconColor: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getFrontAttachment(): DocumentAttachment? =
        attachments.firstOrNull { it.slot == AttachmentSlot.FRONT }

    fun getBackAttachment(): DocumentAttachment? =
        attachments.firstOrNull { it.slot == AttachmentSlot.BACK }

    fun getVisaAttachments(): List<DocumentAttachment> =
        attachments.filter { it.slot == AttachmentSlot.VISA }

    fun getOtherAttachments(): List<DocumentAttachment> =
        attachments.filter { it.slot == AttachmentSlot.OTHER }

    fun getSummaryText(): String {
        val parts = mutableListOf<String>()
        val hasFront = getFrontAttachment() != null
        val hasBack = getBackAttachment() != null
        val visaCount = getVisaAttachments().size
        val otherCount = getOtherAttachments().size

        if (hasFront && hasBack) {
            parts.add("Front & Back")
        } else if (hasFront) {
            parts.add("Front")
        } else if (hasBack) {
            parts.add("Back")
        }

        if (visaCount > 0) {
            parts.add("$visaCount ${if (visaCount == 1) "Visa" else "Visas"}")
        }

        val pageCount = getOtherAttachments().count { !it.isPdf }
        val fileCount = otherCount - pageCount
        if (pageCount > 0) {
            parts.add("$pageCount ${if (pageCount == 1) "Page" else "Pages"}")
        }
        if (fileCount > 0) {
            parts.add("$fileCount ${if (fileCount == 1) "PDF" else "PDFs"}")
        }

        return if (parts.isNotEmpty()) parts.joinToString(" • ") else ""
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("type", type.name)
            put("documentNumber", documentNumber)
            put("expirationDate", expirationDate)
            put("issuingCountry", issuingCountry)
            put("notes", notes)
            put("isPasswordProtected", isPasswordProtected)
            put("passwordSaltBase64", passwordSaltBase64)
            put("encryptedDekBase64", encryptedDekBase64)
            put("dekIvBase64", dekIvBase64)
            put("reminderDays", reminderDays)
            put("iconKey", iconKey)
            put("iconColor", iconColor)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)

            val attArray = JSONArray()
            attachments.forEach { attArray.put(it.toJson()) }
            put("attachments", attArray)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): SecureDocument {
            val attList = mutableListOf<DocumentAttachment>()
            val attArray = json.optJSONArray("attachments")
            if (attArray != null) {
                for (i in 0 until attArray.length()) {
                    val attObj = attArray.optJSONObject(i)
                    if (attObj != null) {
                        attList.add(DocumentAttachment.fromJson(attObj))
                    }
                }
            }

            return SecureDocument(
                id = json.optString("id", UUID.randomUUID().toString()),
                title = json.optString("title", "Untitled Document"),
                type = DocumentType.fromString(json.optString("type", DocumentType.OTHER.name)),
                documentNumber = json.optString("documentNumber", ""),
                expirationDate = json.optString("expirationDate", ""),
                issuingCountry = json.optString("issuingCountry", ""),
                notes = json.optString("notes", ""),
                attachments = attList,
                isPasswordProtected = json.optBoolean("isPasswordProtected", false),
                passwordSaltBase64 = json.optString("passwordSaltBase64", ""),
                encryptedDekBase64 = json.optString("encryptedDekBase64", ""),
                dekIvBase64 = json.optString("dekIvBase64", ""),
                reminderDays = json.optInt("reminderDays", 0),
                iconKey = json.optString("iconKey", ""),
                iconColor = json.optString("iconColor", ""),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
