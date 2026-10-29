package com.mattintech.securepass.locker.transfer

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
import com.mattintech.securepass.locker.model.AttachmentSlot
import com.mattintech.securepass.locker.model.DocumentAttachment
import com.mattintech.securepass.locker.model.DocumentType
import com.mattintech.securepass.locker.model.SecureDocument
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
 * Each document is an entry; attachments are entry attachments. SecurePass-only details (document
 * type, photo slots, password-protection key material) travel in the entry's custom data, so an
 * entry edited in another KeePass app still imports.
 */
internal object KdbxBackupFormat {

    class Contents(
        val documents: List<SecureDocument>,
        /** Attachment id -> bytes (ciphertext for password-protected documents). */
        val files: Map<String, ByteArray>,
        /** Document id -> deletedAt (ms). */
        val deletions: Map<String, Long>
    )

    /** Attachment bytes as stored in the backup for [doc]. */
    fun interface AttachmentSource {
        suspend fun read(doc: SecureDocument, attachment: DocumentAttachment): ByteArray
    }

    private const val KEY_DOCUMENT = "SecurePass.Document"
    private const val KEY_UPDATED_AT = "SecurePass.UpdatedAt"
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

    /** Tokens are shown as "SPVT-XXXX-…"; tolerate lower case and stray spaces when typed. */
    fun normalizeToken(token: String): String = token.trim().uppercase().replace(" ", "")

    private fun credentials(token: String) =
        Credentials.from(EncryptedValue.fromString(normalizeToken(token)))

    suspend fun write(
        out: OutputStream,
        token: String,
        documents: List<SecureDocument>,
        deletions: Map<String, Long>,
        attachments: AttachmentSource
    ) {
        val random = SecureRandom()
        val binaries = linkedMapOf<okio.ByteString, BinaryData>()
        val entries = documents.map { doc ->
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
            toEntry(doc.copy(attachments = attachmentMeta), refs)
        }

        val database = KeePassDatabase.Ver4x.create(
            rootName = "SecurePass",
            meta = Meta(generator = "SecurePass", name = "SecurePass Vault", recycleBinEnabled = false),
            credentials = credentials(token),
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
                    group = db.content.group.copy(entries = entries),
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
        val database = try {
            KeePassDatabase.decode(input, credentials(token))
        } catch (e: CryptoError.InvalidKey) {
            throw IllegalArgumentException("Decryption failed. The Secure Token entered is incorrect.")
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not read the backup. The token is incorrect or the file is damaged.")
        }

        val meta = database.content.meta
        val binaries = database.binaries
        val documents = mutableListOf<SecureDocument>()
        val files = mutableMapOf<String, ByteArray>()

        for (entry in allEntries(database.content.group, if (meta.recycleBinEnabled) meta.recycleBinUuid else null)) {
            val doc = fromEntry(entry)
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
        return Contents(documents, files, deletions)
    }

    private fun toEntry(doc: SecureDocument, refs: List<BinaryReference>): Entry {
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
            )
        )
    }

    private fun fromEntry(entry: Entry): SecureDocument {
        val stored = entry.customData[KEY_DOCUMENT]?.value?.let {
            runCatching { SecureDocument.fromJson(JSONObject(it)) }.getOrNull()
        }
        fun field(key: String) = entry.fields[key]?.content.orEmpty()

        val modifiedSeconds = entry.times?.lastModificationTime?.epochSecond
        val exactUpdatedAt = entry.customData[KEY_UPDATED_AT]?.value?.toLongOrNull()
        val updatedAt = when {
            // Not edited since SecurePass wrote it
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
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun allEntries(root: Group, skipGroup: UUID?): List<Entry> {
        val result = mutableListOf<Entry>()
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val group = stack.removeLast()
            if (group.uuid == skipGroup) continue
            result.addAll(group.entries)
            stack.addAll(group.groups)
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
