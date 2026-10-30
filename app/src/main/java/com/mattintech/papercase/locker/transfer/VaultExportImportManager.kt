package com.mattintech.papercase.locker.transfer

import android.content.Context
import android.net.Uri
import com.mattintech.papercase.locker.backup.BackupExtras
import com.mattintech.papercase.locker.model.DocumentAttachment
import com.mattintech.papercase.locker.model.Folder
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.repository.SecureLockerRepository
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
    val fileMap: Map<String, ByteArray>,
    /** Local documents the other device deleted (and that weren't edited here since). */
    val deletedDocuments: List<SecureDocument> = emptyList(),
    /** Incoming documents this device already deleted; skipped unless replacing everything. */
    val previouslyDeletedDocuments: List<SecureDocument> = emptyList(),
    /** The other device's deletion records (doc id -> deletedAt). */
    val incomingDeletions: Map<String, Long> = emptyMap(),
    /** Settings, passphrase and reminder status from a backup file. */
    val extras: BackupExtras = BackupExtras(),
    /** This phone's locker had no documents, so it's a restore onto a new phone. */
    val localWasEmpty: Boolean = false,
    val incomingFolders: List<Folder> = emptyList()
) {
    val hasConflicts: Boolean
        get() = conflictingDocuments.isNotEmpty()

    val hasChanges: Boolean
        get() = newDocuments.isNotEmpty() || conflictingDocuments.isNotEmpty() || deletedDocuments.isNotEmpty()
}

object VaultExportImportManager {

    private const val MAGIC_HEADER = 0x53505654 // "SPVT"
    private const val FORMAT_VERSION = 1           // token-protected file backup (PBKDF2)
    private const val FORMAT_VERSION_RAW_KEY = 2   // P2P transfer, random 256-bit key from the QR
    private const val PBKDF2_ITERATIONS = 20000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 16

    private fun deriveKey(token: String, salt: ByteArray): SecretKeySpec {
        val cleanToken = KdbxBackupFormat.normalizeToken(token)
        val spec = PBEKeySpec(cleanToken.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    /** Writes a KeePass KDBX 4 backup that opens with [token] as its master password. */
    suspend fun exportVault(
        context: Context,
        repository: SecureLockerRepository,
        token: String
    ): File = withContext(Dispatchers.IO) {
        val documents = repository.getAllDocuments()
        val deletions = repository.getDeletedDocuments()

        val exportDir = File(context.cacheDir, "vault_exports").apply { if (!exists()) mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "Papercase_Backup_$dateStr.kdbx")
        try {
            FileOutputStream(exportFile).use { out ->
                KdbxBackupFormat.write(out, token, documents, deletions, BackupExtras.collect(context), repository.getFolders()) { doc, att ->
                    // Password-protected documents stay encrypted with their own key
                    if (doc.isPasswordProtected) repository.readStoredAttachmentBytes(att)
                    else repository.readAttachmentBytes(att)
                }
            }
        } catch (e: Exception) {
            exportFile.delete()
            throw e
        }
        exportFile
    }

    /**
     * Exports the vault for a P2P transfer, encrypted with a key derived from the QR transfer key.
     */
    suspend fun exportVaultWithKey(
        context: Context,
        repository: SecureLockerRepository,
        encryptionKey: ByteArray
    ): File = withContext(Dispatchers.IO) {
        val unencryptedZipBytes = buildArchive(repository)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"))
        val cipherBytes = cipher.doFinal(unencryptedZipBytes)

        // Container: [MAGIC(4)][VERSION(1)][IV_LEN(1)][IV(12)][PAYLOAD_LEN(4)][PAYLOAD]
        writeContainer(context, FORMAT_VERSION_RAW_KEY, cipher.iv, cipherBytes)
    }

    private suspend fun buildArchive(repository: SecureLockerRepository): ByteArray {
        val documents = repository.getAllDocuments()
        val deletions = repository.getDeletedDocuments()

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
                put("deleted", SecureLockerRepository.deletionsToJson(deletions))
                put("folders", Folder.listToJson(repository.getFolders()))
            }

            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifestJson.toString().toByteArray(StandardCharsets.UTF_8))
            zipOut.closeEntry()

            // Write all attachment files into zip. Password-protected documents stay encrypted with
            // their own key (salt + wrapped key travel in the manifest), so only the password opens them.
            for (doc in documents) {
                for (att in doc.attachments) {
                    val rawBytes = if (doc.isPasswordProtected) {
                        repository.readStoredAttachmentBytes(att)
                    } else {
                        repository.readAttachmentBytes(att)
                    }
                    if (rawBytes.isNotEmpty()) {
                        zipOut.putNextEntry(ZipEntry("files/${att.id}"))
                        zipOut.write(rawBytes)
                        zipOut.closeEntry()
                    }
                }
            }
        }
        return zipByteStream.toByteArray()
    }

    private fun writeContainer(
        context: Context,
        version: Int,
        iv: ByteArray,
        cipherBytes: ByteArray
    ): File {
        val exportDir = File(context.cacheDir, "vault_exports").apply { if (!exists()) mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "Papercase_Transfer_$dateStr.spvault")

        DataOutputStream(FileOutputStream(exportFile)).use { out ->
            out.writeInt(MAGIC_HEADER)
            out.writeByte(version)
            out.writeByte(iv.size)
            out.write(iv)
            out.writeInt(cipherBytes.size)
            out.write(cipherBytes)
        }
        return exportFile
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

    suspend fun prepareVaultImportFromFileWithKey(
        repository: SecureLockerRepository,
        file: File,
        encryptionKey: ByteArray
    ): VaultConflictReport = withContext(Dispatchers.IO) {
        if (!file.exists()) throw IllegalArgumentException("File does not exist: ${file.path}")
        val dataIn = DataInputStream(ByteArrayInputStream(file.readBytes()))
        if (dataIn.readInt() != MAGIC_HEADER) {
            throw IllegalArgumentException("Not a valid Papercase vault transfer")
        }
        if (dataIn.readByte().toInt() != FORMAT_VERSION_RAW_KEY) {
            throw IllegalArgumentException("Unsupported transfer format. Update Papercase on both devices.")
        }
        val iv = ByteArray(dataIn.readByte().toInt())
        dataIn.readFully(iv)
        val cipherBytes = ByteArray(dataIn.readInt())
        dataIn.readFully(cipherBytes)

        val decryptedZipBytes = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("Decryption failed. The transfer was corrupted or tampered with.")
        }
        analyzeArchive(repository, decryptedZipBytes)
    }

    private suspend fun prepareVaultImportFromBytes(
        repository: SecureLockerRepository,
        fileBytes: ByteArray,
        token: String
    ): VaultConflictReport = withContext(Dispatchers.IO) {
        if (KdbxBackupFormat.isKdbx(fileBytes)) {
            val contents = KdbxBackupFormat.read(ByteArrayInputStream(fileBytes), token)
            return@withContext analyzeIncoming(repository, contents.documents, contents.files, contents.deletions)
                .copy(extras = contents.extras, incomingFolders = contents.folders)
        }

        // Legacy .spvault backups written by earlier versions
        if (fileBytes.size < 30) {
            throw IllegalArgumentException("Corrupted or invalid vault file")
        }

        val dataIn = DataInputStream(ByteArrayInputStream(fileBytes))
        val magic = dataIn.readInt()
        if (magic != MAGIC_HEADER) {
            throw IllegalArgumentException("Not a valid Papercase vault file (Invalid magic header)")
        }

        val version = dataIn.readByte().toInt()
        if (version != FORMAT_VERSION) {
            throw IllegalArgumentException("Vault format version $version is not supported by this version of Papercase")
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
        analyzeArchive(repository, decryptedZipBytes)
    }

    private suspend fun analyzeArchive(
        repository: SecureLockerRepository,
        decryptedZipBytes: ByteArray
    ): VaultConflictReport {
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

        val incomingDeletions = SecureLockerRepository.deletionsFromJson(manifestJson.optJSONArray("deleted"))
        return analyzeIncoming(repository, incomingDocuments, fileMap, incomingDeletions)
            .copy(incomingFolders = Folder.listFromJson(manifestJson.optJSONArray("folders")))
    }

    private suspend fun analyzeIncoming(
        repository: SecureLockerRepository,
        incomingDocuments: List<SecureDocument>,
        fileMap: Map<String, ByteArray>,
        incomingDeletions: Map<String, Long>
    ): VaultConflictReport {
        val localDocuments = repository.getAllDocuments()
        val localMap = localDocuments.associateBy { it.id }
        val localDeletions = repository.getDeletedDocuments()

        val newDocs = mutableListOf<SecureDocument>()
        val identicalDocs = mutableListOf<SecureDocument>()
        val conflictingDocs = mutableListOf<ConflictPair>()
        val previouslyDeleted = mutableListOf<SecureDocument>()

        for (incoming in incomingDocuments) {
            val local = localMap[incoming.id]
            val deletedHereAt = localDeletions[incoming.id]
            if (local == null && deletedHereAt != null && deletedHereAt >= incoming.updatedAt) {
                // Deleted here after the other device last changed it: don't bring it back
                previouslyDeleted.add(incoming)
            } else if (local == null) {
                newDocs.add(incoming)
            } else if (local.updatedAt == incoming.updatedAt && local.title == incoming.title && local.documentNumber == incoming.documentNumber) {
                identicalDocs.add(incoming)
            } else {
                conflictingDocs.add(ConflictPair(incoming = incoming, local = local))
            }
        }

        // Deleted on the other device after the last local edit -> delete here too
        val incomingIds = incomingDocuments.mapTo(HashSet()) { it.id }
        val deletedDocs = localDocuments.filter { local ->
            val deletedThereAt = incomingDeletions[local.id]
            local.id !in incomingIds && deletedThereAt != null && deletedThereAt >= local.updatedAt
        }

        return VaultConflictReport(
            totalIncoming = incomingDocuments.size,
            newDocuments = newDocs,
            identicalDocuments = identicalDocs,
            conflictingDocuments = conflictingDocs,
            fileMap = fileMap,
            deletedDocuments = deletedDocs,
            previouslyDeletedDocuments = previouslyDeleted,
            incomingDeletions = incomingDeletions,
            localWasEmpty = localDocuments.isEmpty()
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
                repository.mergeFolders(report.incomingFolders, report.incomingDeletions, replace = true)

                val allIncoming = report.newDocuments + report.identicalDocuments +
                    report.conflictingDocuments.map { it.incoming } + report.previouslyDeletedDocuments
                for (doc in allIncoming) {
                    val savedDoc = saveDocWithAttachments(repository, doc, report.fileMap)
                    repository.saveDocument(savedDoc, preserveTimestamp = true)
                    processedCount++
                }
                repository.recordDeletions(report.incomingDeletions)
            }

            ConflictResolutionStrategy.SMART_MERGE -> {
                repository.mergeFolders(report.incomingFolders, report.incomingDeletions)
                // 1. Add all new documents
                for (doc in report.newDocuments) {
                    val savedDoc = saveDocWithAttachments(repository, doc, report.fileMap)
                    repository.saveDocument(savedDoc, preserveTimestamp = true)
                    processedCount++
                }

                // 2. Handle conflicts: keep the newer version
                for (conflict in report.conflictingDocuments) {
                    if (conflict.incoming.updatedAt > conflict.local.updatedAt) {
                        // Incoming is newer -> overwrite local, then drop the replaced files
                        val savedDoc = saveDocWithAttachments(repository, conflict.incoming, report.fileMap)
                        if (repository.saveDocument(savedDoc, preserveTimestamp = true)) {
                            val stillUsed = savedDoc.attachments.mapTo(HashSet()) { it.encryptedFileName }
                            conflict.local.attachments
                                .filter { it.encryptedFileName !in stillUsed }
                                .forEach { repository.deleteAttachmentFile(it) }
                        }
                        processedCount++
                    } else {
                        // Local is newer or equal -> keep local as-is
                        // No changes needed
                    }
                }

                processedCount += applyDeletions(repository, report)
            }

            ConflictResolutionStrategy.KEEP_BOTH -> {
                repository.mergeFolders(report.incomingFolders, report.incomingDeletions)
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

                processedCount += applyDeletions(repository, report)
            }
        }

        // Sweep files left behind by earlier imports / interrupted saves
        repository.pruneOrphanFiles()

        processedCount
    }

    private suspend fun applyDeletions(repository: SecureLockerRepository, report: VaultConflictReport): Int {
        var count = 0
        for (doc in report.deletedDocuments) {
            val deletedAt = report.incomingDeletions[doc.id] ?: System.currentTimeMillis()
            if (repository.deleteDocument(doc.id, deletedAt)) count++
        }
        repository.recordDeletions(report.incomingDeletions)
        return count
    }

    private suspend fun saveDocWithAttachments(
        repository: SecureLockerRepository,
        doc: SecureDocument,
        fileMap: Map<String, ByteArray>
    ): SecureDocument {
        val updatedAttachments = mutableListOf<DocumentAttachment>()
        for (att in doc.attachments) {
            val rawBytes = fileMap[att.id]
            if (rawBytes != null && rawBytes.isNotEmpty() && doc.isPasswordProtected) {
                // Still encrypted with the document's own key: store as-is
                updatedAttachments.add(repository.saveStoredAttachmentBytes(att, rawBytes))
            } else if (rawBytes != null && rawBytes.isNotEmpty()) {
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
