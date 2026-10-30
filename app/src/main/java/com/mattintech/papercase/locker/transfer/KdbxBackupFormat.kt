package com.mattintech.papercase.locker.transfer

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import com.mattintech.papercase.locker.backup.BackupExtras
import com.mattintech.papercase.locker.model.AttachmentSlot
import com.mattintech.papercase.locker.model.DocumentAttachment
import com.mattintech.papercase.locker.model.DocumentType
import com.mattintech.papercase.locker.model.Folder
import com.mattintech.papercase.locker.model.FolderTree
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.reminders.ReminderEntry
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Backup files are standard KeePass KDBX 4 databases (Argon2id + AES-256), so they can also be
 * opened in KeePassXC / KeePassDX with the backup token as the master password.
 *
 * Each document is an entry; attachments are entry attachments. Papercase-only details (document
 * type, photo slots, password-protection key material, reminder status) travel in the entry's custom
 * data, so an entry edited in another KeePass app still imports. App settings are in the database's
 * custom data, and the master passphrase is its own entry.
 */
internal object KdbxBackupFormat {

    class Contents(
        val documents: List<SecureDocument>,
        /** Attachment id -> bytes (ciphertext for password-protected documents). */
        val files: Map<String, ByteArray>,
        /** Document or folder id -> deletedAt (ms). */
        val deletions: Map<String, Long>,
        val extras: BackupExtras,
        val folders: List<Folder> = emptyList()
    )

    /** Attachment bytes as stored in the backup for [doc]. */
    fun interface AttachmentSource {
        suspend fun read(doc: SecureDocument, attachment: DocumentAttachment): ByteArray
    }

    private const val KEY_DOCUMENT = "Papercase.Document"
    private const val KEY_UPDATED_AT = "Papercase.UpdatedAt"
    // Backups made before the rename to Papercase
    private const val LEGACY_KEY_DOCUMENT = "SecurePass.Document"
    private const val LEGACY_KEY_UPDATED_AT = "SecurePass.UpdatedAt"
    private const val KEY_SETTINGS = "Papercase.Settings"
    private const val KEY_FOLDER = "Papercase.Folder"
    private const val KEY_REMINDER = "Papercase.Reminder"
    // Marks the entry holding the master passphrase (not a document)
    private const val KEY_PASSPHRASE = "Papercase.Passphrase"
    private const val PASSPHRASE_TITLE = "Papercase passphrase"
    private const val FIELD_TYPE = "Document Type"
    private const val FIELD_EXPIRES = "Expiration Date"
    private const val FIELD_COUNTRY = "Issuing Country"
    private const val ENCRYPTED_SUFFIX = ".encrypted"

    // Argon2id, 64 MiB, 3 passes. The token is random (100 bits), so this is defence in depth.
    private const val ARGON2_MEMORY = 67_108_864UL // 64 MiB
    private const val ARGON2_ITERATIONS = 3UL
    private const val ARGON2_PARALLELISM = 2U
    private const val ARGON2_VERSION_13 = 0x13U

    private val KDBX_SIGNATURE = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte())

    fun isKdbx(header: ByteArray): Boolean =
        header.size >= 4 && header.copyOfRange(0, 4).contentEquals(KDBX_SIGNATURE)

    /** Tokens are shown as "PCASE-XXXX-…"; tolerate lower case and stray spaces when typed. */
    fun normalizeToken(token: String): String = token.trim().uppercase().replace(" ", "")

    /** Tokens are normalized; user-chosen backup passwords are used exactly as typed. */
    private fun credentials(secret: String, exact: Boolean) =
        Credentials.from(EncryptedValue.fromString(if (exact) secret else normalizeToken(secret)))

    suspend fun write(
        out: OutputStream,
        token: String,
        documents: List<SecureDocument>,
        deletions: Map<String, Long>,
        extras: BackupExtras = BackupExtras(),
        folders: List<Folder> = emptyList(),
        exactPassword: Boolean = false,
        attachments: AttachmentSource
    ) {
        val random = SecureRandom()
        val binaries = linkedMapOf<okio.ByteString, BinaryData>()
        val reminders = extras.reminders.associateBy { it.docId }
        val docEntries = documents.map { doc ->
            val usedNames = mutableSetOf<String>()
            val refs = mutableListOf<BinaryReference>()
            val attachmentMeta = mutableListOf<DocumentAttachment>()
            for (att in doc.attachments) {
                val bytes = attachments.read(doc, att)
                if (bytes.isEmpty()) continue
                val binary = BinaryData.Uncompressed(memoryProtection = false, rawContent = bytes)
                binaries[binary.hash] = binary
                val name = uniqueName(
                    if (doc.isPasswordProtected) att.fileName + ENCRYPTED_SUFFIX else att.fileName,
                    usedNames
                )
                refs.add(BinaryReference(binary.hash, name))
                // encryptedFileName is device-local; reuse it to remember the attachment's name in the backup
                attachmentMeta.add(att.copy(encryptedFileName = name))
            }
            doc to toEntry(doc.copy(attachments = attachmentMeta), refs, reminders[doc.id])
        }

        // Folders become KeePass groups, so Kids > Jane shows up as nested groups in KeePassXC
        val byFolder = docEntries.groupBy({ FolderTree.resolve(folders, it.first.folderId) }, { it.second })
        fun groupFor(folder: Folder): Group = Group(
            uuid = parseUuid(folder.id) ?: UUID.nameUUIDFromBytes(folder.id.toByteArray()),
            name = folder.name,
            icon = PredefinedIcon.Folder,
            times = TimeData(
                creationTime = Instant.ofEpochMilli(folder.createdAt),
                lastAccessTime = Instant.ofEpochMilli(folder.updatedAt),
                lastModificationTime = Instant.ofEpochMilli(folder.updatedAt),
                locationChanged = Instant.ofEpochMilli(folder.updatedAt),
                expiryTime = null
            ),
            groups = FolderTree.children(folders, folder.id).map(::groupFor),
            entries = byFolder[folder.id].orEmpty(),
            customData = mapOf(KEY_FOLDER to CustomDataValue(folder.toJson().toString()))
        )
        val entries = byFolder[""].orEmpty() + listOfNotNull(extras.passphrase?.let(::passphraseEntry))
        val topGroups = FolderTree.children(folders, "").map(::groupFor)

        val database = KeePassDatabase.Ver4x.create(
            rootName = "Papercase",
            meta = Meta(
                generator = "Papercase",
                name = "Papercase Backup",
                recycleBinEnabled = false,
                customData = extras.settings?.let { mapOf(KEY_SETTINGS to CustomDataValue(it.toString())) }.orEmpty()
            ),
            credentials = credentials(token, exactPassword),
            random = random
        ).let { db ->
            db.copy(
                header = db.header.copy(
                    kdfParameters = KdfParameters.Argon2(
                        variant = KdfParameters.Argon2.Variant.Argon2id,
                        salt = ByteArray(32).also(random::nextBytes).toByteString(),
                        parallelism = ARGON2_PARALLELISM,
                        memory = ARGON2_MEMORY,
                        iterations = ARGON2_ITERATIONS,
                        version = ARGON2_VERSION_13,
                        secretKey = null,
                        associatedData = null
                    )
                ),
                content = db.content.copy(
                    group = db.content.group.copy(entries = entries, groups = topGroups),
                    deletedObjects = deletions.mapNotNull { (id, at) ->
                        parseUuid(id)?.let { DeletedObject(it, Instant.ofEpochMilli(at)) }
                    }
                )
            )
        }.modifyBinaries { binaries }

        database.encode(out, random = random)
    }

    /** @throws IllegalArgumentException with a user-facing message if the token is wrong or the file is damaged. */
    fun read(input: InputStream, token: String): Contents {
        val bytes = input.readBytes()
        // Automatic backups use the user's password as typed; exported backups use a token
        val candidates = listOf(credentials(token, exact = true), credentials(token, exact = false))
            .let { if (normalizeToken(token) == token) it.take(1) else it }
        var database: KeePassDatabase? = null
        for (credentials in candidates) {
            try {
                database = KeePassDatabase.decode(bytes.inputStream(), credentials)
                break
            } catch (e: CryptoError.InvalidKey) {
                // try the next form
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not read the backup. The password is incorrect or the file is damaged.")
            }
        }
        if (database == null) {
            throw IllegalArgumentException("Decryption failed. The token or backup password is incorrect.")
        }

        val meta = database.content.meta
        val binaries = database.binaries
        val documents = mutableListOf<SecureDocument>()
        val files = mutableMapOf<String, ByteArray>()
        var passphrase: String? = null
        val reminders = mutableListOf<ReminderEntry>()

        val folders = mutableListOf<Folder>()
        for ((entry, folderId) in allEntries(database.content.group, if (meta.recycleBinEnabled) meta.recycleBinUuid else null, folders)) {
            if (entry.customData.containsKey(KEY_PASSPHRASE)) {
                passphrase = entry.fields[BasicField.Password()]?.content?.takeIf { it.isNotEmpty() }
                continue
            }
            val doc = fromEntry(entry).copy(folderId = folderId)
            entry.customData[KEY_REMINDER]?.value?.let { json ->
                runCatching { ReminderEntry.fromJson(JSONObject(json)) }.getOrNull()
                    ?.let { reminders.add(it.copy(docId = doc.id)) }
            }
            val attachments = mutableListOf<DocumentAttachment>()
            for (ref in entry.binaries) {
                val bytes = binaries[ref.hash]?.getContent() ?: continue
                val known = doc.attachments.firstOrNull { it.encryptedFileName == ref.name }
                // Protected documents can only use attachments that are still encrypted with their key
                if (doc.isPasswordProtected && known == null) continue
                val att = known ?: DocumentAttachment(
                    fileName = ref.name,
                    mimeType = URLConnection.guessContentTypeFromName(ref.name) ?: "application/octet-stream",
                    encryptedFileName = "",
                    ivBase64 = "",
                    sizeBytes = bytes.size.toLong(),
                    slot = AttachmentSlot.OTHER
                )
                attachments.add(att)
                files[att.id] = bytes
            }
            documents.add(doc.copy(attachments = attachments))
        }

        val deletions = database.content.deletedObjects.associate { obj ->
            // KDBX stores seconds; round up so a delete isn't treated as older than an edit in the same second
            obj.id.toString() to (obj.deletionTime.epochSecond * 1000 + 999)
        }
        val settings = meta.customData[KEY_SETTINGS]?.value?.let { runCatching { JSONObject(it) }.getOrNull() }
        return Contents(documents, files, deletions, BackupExtras(settings, passphrase, reminders), folders)
    }

    /** Shown as a normal password entry in KeePass apps. */
    private fun passphraseEntry(passphrase: String) = Entry(
        uuid = UUID.randomUUID(),
        icon = PredefinedIcon.Key,
        fields = EntryFields.of(
            BasicField.Title() to EntryValue.Plain(PASSPHRASE_TITLE),
            BasicField.UserName() to EntryValue.Plain(""),
            BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString(passphrase)),
            BasicField.Url() to EntryValue.Plain(""),
            BasicField.Notes() to EntryValue.Plain("The master passphrase stored in Papercase.")
        ),
        customData = mapOf(KEY_PASSPHRASE to CustomDataValue("1"))
    )

    private fun toEntry(doc: SecureDocument, refs: List<BinaryReference>, reminder: ReminderEntry?): Entry {
        val fields = EntryFields.of(
            BasicField.Title() to EntryValue.Plain(doc.title),
            BasicField.UserName() to EntryValue.Plain(doc.documentNumber),
            BasicField.Password() to EntryValue.Plain(""),
            BasicField.Url() to EntryValue.Plain(""),
            BasicField.Notes() to EntryValue.Plain(doc.notes),
            FIELD_TYPE to EntryValue.Plain(doc.type.displayName),
            FIELD_EXPIRES to EntryValue.Plain(doc.expirationDate),
            FIELD_COUNTRY to EntryValue.Plain(doc.issuingCountry)
        )
        return Entry(
            uuid = parseUuid(doc.id) ?: UUID.randomUUID(),
            icon = PredefinedIcon.Identity,
            times = TimeData(
                creationTime = Instant.ofEpochMilli(doc.createdAt),
                lastAccessTime = Instant.ofEpochMilli(doc.updatedAt),
                lastModificationTime = Instant.ofEpochMilli(doc.updatedAt),
                locationChanged = Instant.ofEpochMilli(doc.createdAt),
                expiryTime = null
            ),
            fields = fields,
            binaries = refs,
            customData = mapOf(
                KEY_DOCUMENT to CustomDataValue(doc.toJson().toString()),
                // KDBX timestamps are whole seconds; keep the exact time so unchanged documents match
                KEY_UPDATED_AT to CustomDataValue(doc.updatedAt.toString())
            ) + listOfNotNull(reminder?.let { KEY_REMINDER to CustomDataValue(it.toJson().toString()) })
        )
    }

    private fun fromEntry(entry: Entry): SecureDocument {
        val stored = (entry.customData[KEY_DOCUMENT] ?: entry.customData[LEGACY_KEY_DOCUMENT])?.value?.let {
            runCatching { SecureDocument.fromJson(JSONObject(it)) }.getOrNull()
        }
        fun field(key: String) = entry.fields[key]?.content.orEmpty()

        val modifiedSeconds = entry.times?.lastModificationTime?.epochSecond
        val exactUpdatedAt = (entry.customData[KEY_UPDATED_AT] ?: entry.customData[LEGACY_KEY_UPDATED_AT])?.value?.toLongOrNull()
        val updatedAt = when {
            // Not edited since Papercase wrote it
            exactUpdatedAt != null && (modifiedSeconds == null || exactUpdatedAt / 1000 == modifiedSeconds) -> exactUpdatedAt
            modifiedSeconds != null -> modifiedSeconds * 1000
            else -> System.currentTimeMillis()
        }
        val createdAt = stored?.createdAt
            ?: entry.times?.creationTime?.toEpochMilli()
            ?: updatedAt

        val type = stored?.type
            ?: DocumentType.entries.firstOrNull { it.displayName == field(FIELD_TYPE) }
            ?: DocumentType.OTHER

        return SecureDocument(
            id = entry.uuid.toString(),
            title = field(BasicField.Title()).ifBlank { "Untitled" },
            type = type,
            documentNumber = field(BasicField.UserName()),
            expirationDate = field(FIELD_EXPIRES),
            issuingCountry = field(FIELD_COUNTRY),
            notes = field(BasicField.Notes()),
            attachments = stored?.attachments.orEmpty(),
            isPasswordProtected = stored?.isPasswordProtected ?: false,
            passwordSaltBase64 = stored?.passwordSaltBase64.orEmpty(),
            encryptedDekBase64 = stored?.encryptedDekBase64.orEmpty(),
            dekIvBase64 = stored?.dekIvBase64.orEmpty(),
            reminderDays = stored?.reminderDays ?: 0,
            iconKey = stored?.iconKey.orEmpty(),
            iconColor = stored?.iconColor.orEmpty(),
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    /**
     * Every entry with the folder it's in (blank = top level). Each group below the root becomes a
     * folder in [folders]; groups made in another KeePass app work the same way.
     */
    private fun allEntries(root: Group, skipGroup: UUID?, folders: MutableList<Folder>): List<Pair<Entry, String>> {
        val result = mutableListOf<Pair<Entry, String>>()
        val stack = ArrayDeque(listOf(root to ""))
        while (stack.isNotEmpty()) {
            val (group, folderId) = stack.removeLast()
            if (group.uuid == skipGroup) continue
            group.entries.forEach { result.add(it to folderId) }
            for (child in group.groups) {
                if (child.uuid == skipGroup) continue
                val stored = child.customData[KEY_FOLDER]?.value?.let { runCatching { Folder.fromJson(JSONObject(it)) }.getOrNull() }
                val modified = child.times?.lastModificationTime?.toEpochMilli()
                val folder = Folder(
                    id = child.uuid.toString(),
                    name = child.name.ifBlank { "Folder" },
                    parentId = folderId,
                    iconKey = stored?.iconKey.orEmpty(),
                    iconColor = stored?.iconColor.orEmpty(),
                    createdAt = stored?.createdAt ?: child.times?.creationTime?.toEpochMilli() ?: System.currentTimeMillis(),
                    // Exact time unless the group was changed in another app since
                    updatedAt = stored?.updatedAt?.takeIf { modified == null || it / 1000 == modified / 1000 }
                        ?: modified ?: System.currentTimeMillis()
                )
                folders.add(folder)
                stack.add(child to folder.id)
            }
        }
        return result
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        var candidate = name.ifBlank { "attachment" }
        var n = 2
        while (!used.add(candidate)) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            n++
        }
        return candidate
    }

    private fun parseUuid(id: String): UUID? = try {
        UUID.fromString(id)
    } catch (_: IllegalArgumentException) {
        null
    }
}
