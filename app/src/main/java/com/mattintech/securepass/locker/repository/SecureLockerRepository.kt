package com.mattintech.securepass.locker.repository

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.mattintech.securepass.locker.crypto.DocumentPasswordCrypto
import com.mattintech.securepass.locker.crypto.LockerKeyInvalidatedException
import com.mattintech.securepass.locker.crypto.LockerKeystoreHelper
import com.mattintech.securepass.locker.crypto.LockerLockedException
import com.mattintech.securepass.locker.model.AttachmentSlot
import com.mattintech.securepass.locker.model.DocumentAttachment
import com.mattintech.securepass.locker.model.SecureDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey

class SecureLockerRepository(private val context: Context) {

    companion object {
        private const val ORPHAN_MIN_AGE_MS = 60 * 60 * 1000L

        fun deletionsToJson(deletions: Map<String, Long>): JSONArray = JSONArray().apply {
            deletions.forEach { (id, at) -> put(JSONObject().put("id", id).put("deletedAt", at)) }
        }

        fun deletionsFromJson(array: JSONArray?): Map<String, Long> {
            if (array == null) return emptyMap()
            val map = mutableMapOf<String, Long>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val at = o.optLong("deletedAt", 0L)
                if (id.isNotEmpty() && at > 0) map[id] = maxOf(at, map[id] ?: 0L)
            }
            return map
        }
    }

    private val lockerDir = File(context.filesDir, "locker").apply { if (!exists()) mkdirs() }
    private val filesDir = File(lockerDir, "files").apply { if (!exists()) mkdirs() }
    private val catalogFile = File(lockerDir, "catalog.enc")

    private fun isDeviceSecure(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** Key version recorded in the catalog; catalogs written before versioning used the legacy key. */
    private fun catalogKeyVersion(): Int? {
        if (!catalogFile.exists()) return null
        return JSONObject(catalogFile.readText())
            .optInt("keyVersion", LockerKeystoreHelper.KEY_VERSION_LEGACY)
    }

    /** Key used for locker-encrypted attachments and the catalog. New lockers use the auth-bound key when possible. */
    private fun activeKeyVersion(): Int {
        catalogKeyVersion()?.let { return it }
        return if (isDeviceSecure()) LockerKeystoreHelper.KEY_VERSION_AUTH_BOUND else LockerKeystoreHelper.KEY_VERSION_LEGACY
    }

    private fun ensureKey(version: Int) {
        if (!LockerKeystoreHelper.hasKey(version) && catalogKeyVersion() == null) {
            LockerKeystoreHelper.generateKey(version)
        }
    }

    /** True when the locker still uses the key that works without authentication. */
    fun needsAuthBoundMigration(): Boolean =
        isDeviceSecure() && catalogKeyVersion() == LockerKeystoreHelper.KEY_VERSION_LEGACY

    /** Documents plus deletion records (doc id -> deletedAt), so deletes can sync to other devices. */
    private class Catalog(
        val documents: MutableList<SecureDocument>,
        val deleted: MutableMap<String, Long>
    )

    /**
     * Reads the catalog. Throws on any failure so callers never mistake an unreadable locker for an
     * empty one (writing an "empty" list back would destroy every document).
     */
    private fun loadCatalogStrict(): Catalog {
        if (!catalogFile.exists()) return Catalog(mutableListOf(), mutableMapOf())
        val jsonWrapper = JSONObject(catalogFile.readText())
        val version = jsonWrapper.optInt("keyVersion", LockerKeystoreHelper.KEY_VERSION_LEGACY)
        val jsonString = LockerKeystoreHelper.decryptString(
            jsonWrapper.getString("ciphertext"),
            jsonWrapper.getString("iv"),
            version
        )

        // Older catalogs are a bare array of documents
        val payload = jsonString.trimStart()
        val array: JSONArray
        val deleted = mutableMapOf<String, Long>()
        if (payload.startsWith("[")) {
            array = JSONArray(payload)
        } else {
            val obj = JSONObject(payload)
            array = obj.getJSONArray("documents")
            deleted.putAll(deletionsFromJson(obj.optJSONArray("deleted")))
        }

        val list = mutableListOf<SecureDocument>()
        for (i in 0 until array.length()) {
            list.add(SecureDocument.fromJson(array.getJSONObject(i)))
        }
        return Catalog(list, deleted)
    }

    /** Atomically replaces the catalog, encrypted with [version]. */
    private fun writeCatalog(catalog: Catalog, version: Int) {
        ensureKey(version)
        val array = JSONArray()
        catalog.documents.forEach { array.put(it.toJson()) }
        val payload = JSONObject().apply {
            put("documents", array)
            put("deleted", deletionsToJson(catalog.deleted))
        }

        val (cipherBase64, ivBase64) = LockerKeystoreHelper.encryptString(payload.toString(), version)
        val wrapper = JSONObject().apply {
            put("ciphertext", cipherBase64)
            put("iv", ivBase64)
            put("keyVersion", version)
        }
        val tempFile = File(lockerDir, "catalog.enc.tmp")
        tempFile.writeText(wrapper.toString())
        if (!tempFile.renameTo(catalogFile)) {
            tempFile.delete()
            throw IllegalStateException("Could not write locker catalog")
        }
    }

    /**
     * @throws LockerLockedException if the user must authenticate again
     * @throws LockerKeyInvalidatedException if Android destroyed the locker key
     */
    suspend fun getAllDocuments(): List<SecureDocument> = withContext(Dispatchers.IO) {
        try {
            loadCatalogStrict().documents.sortedByDescending { it.updatedAt }
        } catch (e: LockerLockedException) {
            throw e
        } catch (e: LockerKeyInvalidatedException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getDocumentById(id: String): SecureDocument? = withContext(Dispatchers.IO) {
        getAllDocuments().find { it.id == id }
    }

    suspend fun saveDocument(doc: SecureDocument, preserveTimestamp: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val catalog = loadCatalogStrict()
            val currentList = catalog.documents
            val newUpdatedAt = if (preserveTimestamp) doc.updatedAt else System.currentTimeMillis()
            val docToSave = doc.copy(updatedAt = newUpdatedAt)

            val index = currentList.indexOfFirst { it.id == doc.id }
            if (index >= 0) {
                currentList[index] = docToSave
            } else {
                currentList.add(0, docToSave)
            }
            catalog.deleted.remove(doc.id)

            writeCatalog(catalog, activeKeyVersion())
            true
        } catch (e: LockerLockedException) {
            throw e
        } catch (e: LockerKeyInvalidatedException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        try {
            val catalog = loadCatalogStrict()
            catalog.documents.forEach { doc -> doc.attachments.forEach { deleteAttachmentFile(it) } }
            if (catalogFile.exists()) catalogFile.delete()
            true
        } catch (e: LockerLockedException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Used after Android destroyed the auth-bound key: the documents can't be decrypted any more,
     * so wipe the unreadable files and start a fresh locker.
     */
    suspend fun resetAfterKeyLoss() = withContext(Dispatchers.IO) {
        filesDir.listFiles()?.forEach { it.delete() }
        catalogFile.delete()
        LockerKeystoreHelper.deleteKey(LockerKeystoreHelper.KEY_VERSION_AUTH_BOUND)
        LockerKeystoreHelper.deleteKey(LockerKeystoreHelper.KEY_VERSION_LEGACY)
    }

    /** Deletes the document and remembers the deletion so it propagates on the next sync. */
    suspend fun deleteDocument(
        docId: String,
        deletedAt: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val catalog = loadCatalogStrict()
            val doc = catalog.documents.find { it.id == docId } ?: return@withContext false

            catalog.documents.removeAll { it.id == docId }
            catalog.deleted[docId] = maxOf(deletedAt, catalog.deleted[docId] ?: 0L)

            writeCatalog(catalog, activeKeyVersion())
            // Only remove the files once the catalog no longer points at them
            doc.attachments.forEach { deleteAttachmentFile(it) }
            true
        } catch (e: LockerLockedException) {
            throw e
        } catch (e: LockerKeyInvalidatedException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Deletion records: document id -> time it was deleted. */
    suspend fun getDeletedDocuments(): Map<String, Long> = withContext(Dispatchers.IO) {
        loadCatalogStrict().deleted.toMap()
    }

    /** Merges deletion records received from another device (keeps the latest time per id). */
    suspend fun recordDeletions(deletions: Map<String, Long>) = withContext(Dispatchers.IO) {
        if (deletions.isEmpty()) return@withContext
        val catalog = loadCatalogStrict()
        val existingIds = catalog.documents.mapTo(HashSet()) { it.id }
        var changed = false
        for ((id, deletedAt) in deletions) {
            // A document that exists here was saved after that deletion, so it wins
            if (id in existingIds) continue
            if (deletedAt > (catalog.deleted[id] ?: 0L)) {
                catalog.deleted[id] = deletedAt
                changed = true
            }
        }
        if (changed) writeCatalog(catalog, activeKeyVersion())
    }

    /**
     * Removes encrypted files that no document references any more (e.g. left behind by an
     * interrupted save). Recent files are kept because an editor may be about to reference them.
     */
    suspend fun pruneOrphanFiles(minAgeMs: Long = ORPHAN_MIN_AGE_MS): Int = withContext(Dispatchers.IO) {
        val referenced = loadCatalogStrict().documents
            .flatMap { it.attachments }
            .mapTo(HashSet()) { it.encryptedFileName }
        val cutoff = System.currentTimeMillis() - minAgeMs
        var removed = 0
        filesDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in referenced && f.lastModified() < cutoff && f.delete()) removed++
        }
        removed
    }

    fun deleteAttachmentFile(attachment: DocumentAttachment) {
        val f = File(filesDir, attachment.encryptedFileName)
        if (f.exists()) f.delete()
    }

    suspend fun saveAttachmentBytes(
        fileName: String,
        mimeType: String,
        data: ByteArray,
        slot: AttachmentSlot = AttachmentSlot.FRONT,
        customTitle: String = "",
        customDek: SecretKey? = null
    ): DocumentAttachment = withContext(Dispatchers.IO) {
        val (cipherBytes, ivBytes) = if (customDek != null) {
            DocumentPasswordCrypto.encryptWithDek(data, customDek)
        } else {
            val version = activeKeyVersion()
            ensureKey(version)
            LockerKeystoreHelper.encryptBytes(data, version)
        }
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

    suspend fun readAttachmentBytes(
        attachment: DocumentAttachment,
        customDek: SecretKey? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val encFile = File(filesDir, attachment.encryptedFileName)
        if (!encFile.exists()) return@withContext ByteArray(0)

        val cipherBytes = encFile.readBytes()
        val ivBytes = java.util.Base64.getDecoder().decode(attachment.ivBase64)

        if (customDek != null) {
            DocumentPasswordCrypto.decryptWithDek(cipherBytes, ivBytes, customDek)
        } else {
            LockerKeystoreHelper.decryptBytes(cipherBytes, ivBytes, activeKeyVersion())
        }
    }

    /**
     * Raw ciphertext of a password-protected attachment. It's encrypted with the document's own key,
     * so it can be transferred as-is and only opened with the document password.
     */
    suspend fun readStoredAttachmentBytes(attachment: DocumentAttachment): ByteArray = withContext(Dispatchers.IO) {
        val encFile = File(filesDir, attachment.encryptedFileName)
        if (encFile.exists()) encFile.readBytes() else ByteArray(0)
    }

    /** Stores ciphertext received from another device without re-encrypting it. */
    suspend fun saveStoredAttachmentBytes(
        attachment: DocumentAttachment,
        cipherBytes: ByteArray
    ): DocumentAttachment = withContext(Dispatchers.IO) {
        val encryptedFileName = "${UUID.randomUUID()}.bin"
        File(filesDir, encryptedFileName).writeBytes(cipherBytes)
        attachment.copy(encryptedFileName = encryptedFileName)
    }

    /**
     * One-time move from the legacy key to the auth-bound key. Must be called right after the user
     * authenticated. The old catalog and files stay valid until the new catalog is written, so an
     * interrupted migration simply runs again next time.
     */
    suspend fun migrateToAuthBoundKey(): Boolean = withContext(Dispatchers.IO) {
        if (!needsAuthBoundMigration()) return@withContext false
        val legacy = LockerKeystoreHelper.KEY_VERSION_LEGACY
        val authBound = LockerKeystoreHelper.KEY_VERSION_AUTH_BOUND

        val catalog = loadCatalogStrict()
        val documents = catalog.documents
        // Safe to (re)create: nothing uses the auth-bound key until the catalog below is written
        LockerKeystoreHelper.generateKey(authBound)

        val newFiles = mutableListOf<File>()
        try {
            val migrated = documents.map { doc ->
                // Password-protected attachments use the document's own key and don't change
                if (doc.isPasswordProtected) return@map doc
                doc.copy(attachments = doc.attachments.map { att ->
                    val encFile = File(filesDir, att.encryptedFileName)
                    if (!encFile.exists()) return@map att
                    val plain = LockerKeystoreHelper.decryptBytes(
                        encFile.readBytes(),
                        java.util.Base64.getDecoder().decode(att.ivBase64),
                        legacy
                    )
                    val (cipherBytes, ivBytes) = LockerKeystoreHelper.encryptBytes(plain, authBound)
                    val newFile = File(filesDir, "${UUID.randomUUID()}.bin")
                    newFile.writeBytes(cipherBytes)
                    newFiles.add(newFile)
                    att.copy(
                        encryptedFileName = newFile.name,
                        ivBase64 = java.util.Base64.getEncoder().encodeToString(ivBytes)
                    )
                })
            }
            writeCatalog(Catalog(migrated.toMutableList(), catalog.deleted), authBound)
        } catch (e: Exception) {
            newFiles.forEach { it.delete() }
            throw e
        }

        // New catalog is in place: remove the legacy copies and key
        documents.filter { !it.isPasswordProtected }
            .flatMap { it.attachments }
            .forEach { deleteAttachmentFile(it) }
        LockerKeystoreHelper.deleteKey(legacy)
        true
    }

    suspend fun exportAttachmentForShare(
        attachment: DocumentAttachment,
        customDek: SecretKey? = null
    ): Uri = withContext(Dispatchers.IO) {
        val sharedDir = File(context.cacheDir, "shared_docs").apply { if (!exists()) mkdirs() }
        val tempFile = File(sharedDir, attachment.fileName)
        val decryptedBytes = readAttachmentBytes(attachment, customDek)
        tempFile.writeBytes(decryptedBytes)

        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
    }
}
