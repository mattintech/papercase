package com.mattintech.securepass.locker.transfer

import android.content.Context
import android.net.Uri
import com.mattintech.securepass.locker.model.DocumentAttachment
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
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class ConflictResolutionStrategy {
    SMART_MERGE,  // Keep newest for conflicts, add all new
    KEEP_BOTH,    // Keep local, clone incoming as copy, add all new
    REPLACE_ALL   // Clean replace entire locker
}

data class ConflictPair(
    val incoming: SecureDocument,
    val local: SecureDocument
)

data class VaultConflictReport(
    val totalIncoming: Int,
    val newDocuments: List<SecureDocument>,
    val identicalDocuments: List<SecureDocument>,
    val conflictingDocuments: List<ConflictPair>,
    val fileMap: Map<String, ByteArray>
) {
    val hasConflicts: Boolean
        get() = conflictingDocuments.isNotEmpty()
}

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

    /**
     * Decrypts the vault package and analyzes differences against local repository to generate a conflict report.
     */
    suspend fun prepareVaultImport(
        context: Context,
        repository: SecureLockerRepository,
        fileUri: Uri,
        token: String
    ): VaultConflictReport = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(fileUri)
            ?: throw IllegalArgumentException("Could not open file at $fileUri")

        val fileBytes = inputStream.use { it.readBytes() }
        prepareVaultImportFromBytes(repository, fileBytes, token)
    }

    suspend fun prepareVaultImportFromFile(
        repository: SecureLockerRepository,
        file: File,
        token: String
    ): VaultConflictReport = withContext(Dispatchers.IO) {
        if (!file.exists()) throw IllegalArgumentException("File does not exist: ${file.path}")
        val fileBytes = file.readBytes()
        prepareVaultImportFromBytes(repository, fileBytes, token)
    }

    private suspend fun prepareVaultImportFromBytes(
        repository: SecureLockerRepository,
        fileBytes: ByteArray,
        token: String
    ): VaultConflictReport = withContext(Dispatchers.IO) {
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
        val incomingDocuments = mutableListOf<SecureDocument>()

        for (i in 0 until docArray.length()) {
            val docObj = docArray.getJSONObject(i)
            incomingDocuments.add(SecureDocument.fromJson(docObj))
        }

        val localDocuments = repository.getAllDocuments()
        val localMap = localDocuments.associateBy { it.id }

        val newDocs = mutableListOf<SecureDocument>()
        val identicalDocs = mutableListOf<SecureDocument>()
        val conflictingDocs = mutableListOf<ConflictPair>()

        for (incoming in incomingDocuments) {
            val local = localMap[incoming.id]
            if (local == null) {
                newDocs.add(incoming)
            } else if (local.updatedAt == incoming.updatedAt && local.title == incoming.title && local.documentNumber == incoming.documentNumber) {
                identicalDocs.add(incoming)
            } else {
                conflictingDocs.add(ConflictPair(incoming = incoming, local = local))
            }
        }

        VaultConflictReport(
            totalIncoming = incomingDocuments.size,
            newDocuments = newDocs,
            identicalDocuments = identicalDocs,
            conflictingDocuments = conflictingDocs,
            fileMap = fileMap
        )
    }

    /**
     * Commits the import according to the chosen conflict resolution strategy.
     */
    suspend fun applyVaultImport(
        repository: SecureLockerRepository,
        report: VaultConflictReport,
        strategy: ConflictResolutionStrategy
    ): Int = withContext(Dispatchers.IO) {
        var processedCount = 0

        when (strategy) {
            ConflictResolutionStrategy.REPLACE_ALL -> {
                repository.clearAll()

                val allIncoming = report.newDocuments + report.identicalDocuments + report.conflictingDocuments.map { it.incoming }
                for (doc in allIncoming) {
                    val savedDoc = saveDocWithAttachments(repository, doc, report.fileMap)
                    repository.saveDocument(savedDoc, preserveTimestamp = true)
                    processedCount++
                }
            }

            ConflictResolutionStrategy.SMART_MERGE -> {
                // 1. Add all new documents
                for (doc in report.newDocuments) {
                    val savedDoc = saveDocWithAttachments(repository, doc, report.fileMap)
                    repository.saveDocument(savedDoc, preserveTimestamp = true)
                    processedCount++
                }

                // 2. Handle conflicts: keep the newer version
                for (conflict in report.conflictingDocuments) {
                    if (conflict.incoming.updatedAt > conflict.local.updatedAt) {
                        // Incoming is newer -> overwrite local
                        val savedDoc = saveDocWithAttachments(repository, conflict.incoming, report.fileMap)
                        repository.saveDocument(savedDoc, preserveTimestamp = true)
                        processedCount++
                    } else {
                        // Local is newer or equal -> keep local as-is
                        // No changes needed
                    }
                }
            }

            ConflictResolutionStrategy.KEEP_BOTH -> {
                // 1. Add all new documents
                for (doc in report.newDocuments) {
                    val savedDoc = saveDocWithAttachments(repository, doc, report.fileMap)
                    repository.saveDocument(savedDoc, preserveTimestamp = true)
                    processedCount++
                }

                // 2. Handle conflicts: preserve local, add incoming as a separate copy
                for (conflict in report.conflictingDocuments) {
                    val cloneDoc = conflict.incoming.copy(
                        id = UUID.randomUUID().toString(),
                        title = "${conflict.incoming.title} (Synced Copy)"
                    )
                    val savedDoc = saveDocWithAttachments(repository, cloneDoc, report.fileMap)
                    repository.saveDocument(savedDoc, preserveTimestamp = true)
                    processedCount++
                }
            }
        }

        processedCount
    }

    private suspend fun saveDocWithAttachments(
        repository: SecureLockerRepository,
        doc: SecureDocument,
        fileMap: Map<String, ByteArray>
    ): SecureDocument {
        val updatedAttachments = mutableListOf<DocumentAttachment>()
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
        return doc.copy(attachments = updatedAttachments)
    }

    // Default legacy method for backward compatibility
    suspend fun importVault(
        context: Context,
        repository: SecureLockerRepository,
        fileUri: Uri,
        token: String
    ): Int = withContext(Dispatchers.IO) {
        val report = prepareVaultImport(context, repository, fileUri, token)
        applyVaultImport(repository, report, ConflictResolutionStrategy.SMART_MERGE)
    }
}
