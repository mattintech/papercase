package com.mattintech.securepass.locker.model

import com.mattintech.securepass.R
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

data class DocumentAttachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val mimeType: String,
    val encryptedFileName: String,
    val ivBase64: String,
    val sizeBytes: Long
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("encryptedFileName", encryptedFileName)
            put("ivBase64", ivBase64)
            put("sizeBytes", sizeBytes)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DocumentAttachment {
            return DocumentAttachment(
                id = json.optString("id", UUID.randomUUID().toString()),
                fileName = json.optString("fileName", "attachment"),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                encryptedFileName = json.optString("encryptedFileName", ""),
                ivBase64 = json.optString("ivBase64", ""),
                sizeBytes = json.optLong("sizeBytes", 0L)
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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("type", type.name)
            put("documentNumber", documentNumber)
            put("expirationDate", expirationDate)
            put("issuingCountry", issuingCountry)
            put("notes", notes)
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
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}
