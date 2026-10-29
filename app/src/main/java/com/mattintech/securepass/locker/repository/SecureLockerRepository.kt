package com.mattintech.securepass.locker.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.mattintech.securepass.locker.crypto.LockerKeystoreHelper
import com.mattintech.securepass.locker.model.AttachmentSlot
import com.mattintech.securepass.locker.model.DocumentAttachment
import com.mattintech.securepass.locker.model.SecureDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SecureLockerRepository(private val context: Context) {

    private val lockerDir = File(context.filesDir, "locker").apply { if (!exists()) mkdirs() }
    private val filesDir = File(lockerDir, "files").apply { if (!exists()) mkdirs() }
    private val catalogFile = File(lockerDir, "catalog.enc")

    suspend fun getAllDocuments(): List<SecureDocument> = withContext(Dispatchers.IO) {
        if (!catalogFile.exists()) return@withContext emptyList()
        try {
            val fileContent = catalogFile.readText()
            val jsonWrapper = JSONObject(fileContent)
            val cipherBase64 = jsonWrapper.getString("ciphertext")
            val ivBase64 = jsonWrapper.getString("iv")

            val jsonString = LockerKeystoreHelper.decryptString(cipherBase64, ivBase64)
            val array = JSONArray(jsonString)
            val list = mutableListOf<SecureDocument>()
            for (i in 0 until array.length()) {
                val itemObj = array.getJSONObject(i)
                list.add(SecureDocument.fromJson(itemObj))
            }
            list.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getDocumentById(id: String): SecureDocument? = withContext(Dispatchers.IO) {
        getAllDocuments().find { it.id == id }
    }

    suspend fun saveDocument(doc: SecureDocument): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentList = getAllDocuments().toMutableList()
            val index = currentList.indexOfFirst { it.id == doc.id }
            if (index >= 0) {
                currentList[index] = doc.copy(updatedAt = System.currentTimeMillis())
            } else {
                currentList.add(0, doc.copy(updatedAt = System.currentTimeMillis()))
            }

            val array = JSONArray()
            currentList.forEach { array.put(it.toJson()) }

            val (cipherBase64, ivBase64) = LockerKeystoreHelper.encryptString(array.toString())
            val wrapper = JSONObject().apply {
                put("ciphertext", cipherBase64)
                put("iv", ivBase64)
            }
            catalogFile.writeText(wrapper.toString())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteDocument(docId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentList = getAllDocuments().toMutableList()
            val doc = currentList.find { it.id == docId } ?: return@withContext false

            // Delete associated encrypted files
            doc.attachments.forEach { attachment ->
                val f = File(filesDir, attachment.encryptedFileName)
                if (f.exists()) f.delete()
            }

            currentList.removeAll { it.id == docId }

            val array = JSONArray()
            currentList.forEach { array.put(it.toJson()) }

            val (cipherBase64, ivBase64) = LockerKeystoreHelper.encryptString(array.toString())
            val wrapper = JSONObject().apply {
                put("ciphertext", cipherBase64)
                put("iv", ivBase64)
            }
            catalogFile.writeText(wrapper.toString())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun saveAttachmentBytes(
        fileName: String,
        mimeType: String,
        data: ByteArray,
        slot: AttachmentSlot = AttachmentSlot.FRONT,
        customTitle: String = ""
    ): DocumentAttachment = withContext(Dispatchers.IO) {
        val (cipherBytes, ivBytes) = LockerKeystoreHelper.encryptBytes(data)
        val encryptedFileName = "${UUID.randomUUID()}.bin"
        val encFile = File(filesDir, encryptedFileName)
        encFile.writeBytes(cipherBytes)

        val ivBase64 = java.util.Base64.getEncoder().encodeToString(ivBytes)
        DocumentAttachment(
            fileName = fileName,
            mimeType = mimeType,
            encryptedFileName = encryptedFileName,
            ivBase64 = ivBase64,
            sizeBytes = data.size.toLong(),
            slot = slot,
            customTitle = customTitle
        )
    }

    suspend fun readAttachmentBytes(attachment: DocumentAttachment): ByteArray = withContext(Dispatchers.IO) {
        val encFile = File(filesDir, attachment.encryptedFileName)
        if (!encFile.exists()) return@withContext ByteArray(0)

        val cipherBytes = encFile.readBytes()
        val ivBytes = java.util.Base64.getDecoder().decode(attachment.ivBase64)
        LockerKeystoreHelper.decryptBytes(cipherBytes, ivBytes)
    }

    suspend fun exportAttachmentForShare(attachment: DocumentAttachment): Uri = withContext(Dispatchers.IO) {
        val sharedDir = File(context.cacheDir, "shared_docs").apply { if (!exists()) mkdirs() }
        val tempFile = File(sharedDir, attachment.fileName)
        val decryptedBytes = readAttachmentBytes(attachment)
        tempFile.writeBytes(decryptedBytes)

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }
}
