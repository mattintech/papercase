package com.mattintech.securepass.locker.transfer

import android.content.Context
import android.net.Uri
import com.mattintech.securepass.locker.model.SecureDocument
import com.mattintech.securepass.locker.repository.SecureLockerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object VaultExportImportManager {

    private const val MAGIC_HEADER = 0x53505654 // "SPVT"
    private const val FORMAT_VERSION = 1
    private const val PBKDF2_ITERATIONS = 20000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 16

    private fun deriveKey(token: String, salt: ByteArray): SecretKeySpec {
        val cleanToken = token.trim().uppercase().replace(" ", "")
        val spec = PBEKeySpec(cleanToken.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    suspend fun exportVault(
        context: Context,
        repository: SecureLockerRepository,
        token: String
    ): File = withContext(Dispatchers.IO) {
        val documents = repository.getAllDocuments()

        // 1. Build unencrypted ZIP archive in memory
        val zipByteStream = ByteArrayOutputStream()
        ZipOutputStream(zipByteStream).use { zipOut ->
            // Write manifest.json
            val docArray = JSONArray()
            documents.forEach { docArray.put(it.toJson()) }
            val manifestJson = JSONObject().apply {
                put("version", FORMAT_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("documentCount", documents.size)
                put("documents", docArray)
            }

            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifestJson.toString().toByteArray(StandardCharsets.UTF_8))
            zipOut.closeEntry()

            // Write all attachment files into zip
            for (doc in documents) {
                for (att in doc.attachments) {
                    val rawBytes = repository.readAttachmentBytes(att)
                    if (rawBytes.isNotEmpty()) {
                        zipOut.putNextEntry(ZipEntry("files/${att.id}"))
                        zipOut.write(rawBytes)
                        zipOut.closeEntry()
                    }
                }
            }
        }

        val unencryptedZipBytes = zipByteStream.toByteArray()

        // 2. Generate Salt & Derive Transfer Key
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        val transferKey = deriveKey(token, salt)

        // 3. Encrypt ZIP with AES-256-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, transferKey)
        val cipherBytes = cipher.doFinal(unencryptedZipBytes)
        val iv = cipher.iv

        // 4. Assemble container file: [MAGIC(4)][VERSION(1)][SALT(16)][IV_LEN(1)][IV(12)][PAYLOAD_LEN(4)][PAYLOAD]
        val exportDir = File(context.cacheDir, "vault_exports").apply { if (!exists()) mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "SecurePass_Vault_$dateStr.spvault")

        DataOutputStream(FileOutputStream(exportFile)).use { out ->
            out.writeInt(MAGIC_HEADER)
            out.writeByte(FORMAT_VERSION)
            out.write(salt)
            out.writeByte(iv.size)
            out.write(iv)
            out.writeInt(cipherBytes.size)
            out.write(cipherBytes)
        }

        exportFile
    }

    suspend fun importVault(
        context: Context,
        repository: SecureLockerRepository,
        fileUri: Uri,
        token: String
    ): Int = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: throw IllegalArgumentException("Could not open file at $fileUri")

        val fileBytes = inputStream.use { it.readBytes() }
        if (fileBytes.size < 30) {
            throw IllegalArgumentException("Corrupted or invalid vault file")
        }

        val dataIn = DataInputStream(ByteArrayInputStream(fileBytes))
        val magic = dataIn.readInt()
        if (magic != MAGIC_HEADER) {
            throw IllegalArgumentException("Not a valid SecurePass vault file (Invalid magic header)")
        }

        val version = dataIn.readByte().toInt()
        if (version > FORMAT_VERSION) {
            throw IllegalArgumentException("Vault format version $version is not supported by this version of SecurePass")
        }

        val salt = ByteArray(SALT_LENGTH)
        dataIn.readFully(salt)

        val ivLen = dataIn.readByte().toInt()
        val iv = ByteArray(ivLen)
        dataIn.readFully(iv)

        val cipherLen = dataIn.readInt()
        val cipherBytes = ByteArray(cipherLen)
        dataIn.readFully(cipherBytes)

        // Derive key and decrypt
        val key = deriveKey(token, salt)
        val decryptedZipBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("Decryption failed. The Secure Token entered is incorrect or the file is corrupted.")
        }

        // Unzip and restore documents & attachments
        val fileMap = mutableMapOf<String, ByteArray>()
        var manifestContent: String? = null

        ZipInputStream(ByteArrayInputStream(decryptedZipBytes)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") {
                    manifestContent = zipIn.readBytes().toString(StandardCharsets.UTF_8)
                } else if (entry.name.startsWith("files/")) {
                    val attId = entry.name.removePrefix("files/")
                    fileMap[attId] = zipIn.readBytes()
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }

        if (manifestContent == null) {
            throw IllegalArgumentException("Corrupted backup: manifest.json is missing")
        }

        val manifestJson = JSONObject(manifestContent!!)
        val docArray = manifestJson.getJSONArray("documents")
        var importedCount = 0

        for (i in 0 until docArray.length()) {
            val docObj = docArray.getJSONObject(i)
            val doc = SecureDocument.fromJson(docObj)

            // Re-encrypt and persist each attachment onto the new device's local keystore
            val updatedAttachments = mutableListOf<com.mattintech.securepass.locker.model.DocumentAttachment>()
            for (att in doc.attachments) {
                val rawBytes = fileMap[att.id]
                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    val savedAtt = repository.saveAttachmentBytes(
                        fileName = att.fileName,
                        mimeType = att.mimeType,
                        data = rawBytes,
                        slot = att.slot,
                        customTitle = att.customTitle
                    )
                    updatedAttachments.add(savedAtt)
                } else {
                    updatedAttachments.add(att)
                }
            }

            val docToSave = doc.copy(attachments = updatedAttachments)
            repository.saveDocument(docToSave)
            importedCount++
        }

        importedCount
    }
}
