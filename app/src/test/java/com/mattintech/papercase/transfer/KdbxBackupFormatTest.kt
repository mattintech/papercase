package com.mattintech.papercase.transfer

import com.mattintech.papercase.locker.backup.BackupExtras
import com.mattintech.papercase.locker.model.AttachmentSlot
import com.mattintech.papercase.locker.model.DocumentAttachment
import com.mattintech.papercase.locker.model.DocumentType
import com.mattintech.papercase.locker.model.Folder
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.reminders.ReminderEntry
import com.mattintech.papercase.locker.transfer.KdbxBackupFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.json.JSONObject
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class KdbxBackupFormatTest {

    private val token = "PCASE-ABCD-EFGH-JKLM-NPQR-STUV"

    private val photo = DocumentAttachment(
        fileName = "front.jpg", mimeType = "image/jpeg", encryptedFileName = "local-1.bin",
        ivBase64 = "", sizeBytes = 4, slot = AttachmentSlot.FRONT
    )
    private val visa = DocumentAttachment(
        fileName = "front.jpg", mimeType = "image/jpeg", encryptedFileName = "local-2.bin",
        ivBase64 = "", sizeBytes = 3, slot = AttachmentSlot.VISA, customTitle = "Japan"
    )
    private val secret = DocumentAttachment(
        fileName = "scan.pdf", mimeType = "application/pdf", encryptedFileName = "local-3.bin",
        ivBase64 = "aXZpdml2aXZpdml2", sizeBytes = 5, slot = AttachmentSlot.OTHER
    )

    private val passport = SecureDocument(
        title = "Passport", type = DocumentType.PASSPORT, documentNumber = "X123",
        expirationDate = "2031-01-01", issuingCountry = "Canada", notes = "renew early",
        attachments = listOf(photo, visa), createdAt = 1_700_000_000_123, updatedAt = 1_760_000_000_456
    )
    private val protectedDoc = SecureDocument(
        title = "Protected", type = DocumentType.PDF_DOCUMENT, attachments = listOf(secret),
        isPasswordProtected = true, passwordSaltBase64 = "c2FsdA==", encryptedDekBase64 = "ZGVr",
        dekIvBase64 = "aXY=", createdAt = 1_700_000_000_000, updatedAt = 1_760_000_000_999
    )
    private val bytes = mapOf(
        photo.id to byteArrayOf(1, 2, 3, 4),
        visa.id to byteArrayOf(5, 6, 7),
        secret.id to byteArrayOf(9, 9, 9, 9, 9)
    )
    private val deletedId = UUID.randomUUID().toString()

    private fun backup(): ByteArray = runBlocking {
        val out = ByteArrayOutputStream()
        KdbxBackupFormat.write(out, token, listOf(passport, protectedDoc), mapOf(deletedId to 1_750_000_000_000)) { _, att ->
            bytes.getValue(att.id)
        }
        out.toByteArray()
    }

    @Test
    fun roundTripKeepsDocumentsAttachmentsAndDeletions() {
        val file = backup()
        assertTrue(KdbxBackupFormat.isKdbx(file))
        // Handy for checking the output in KeePassXC
        File(System.getProperty("java.io.tmpdir"), "papercase-test.kdbx").writeBytes(file)

        val contents = KdbxBackupFormat.read(ByteArrayInputStream(file), token)
        assertEquals(2, contents.documents.size)

        val p = contents.documents.first { it.id == passport.id }
        assertEquals(passport.title, p.title)
        assertEquals(DocumentType.PASSPORT, p.type)
        assertEquals("X123", p.documentNumber)
        assertEquals("2031-01-01", p.expirationDate)
        assertEquals("Canada", p.issuingCountry)
        assertEquals("renew early", p.notes)
        assertEquals(passport.createdAt, p.createdAt)
        assertEquals("exact time survives so unchanged docs compare identical", passport.updatedAt, p.updatedAt)
        assertEquals(listOf(AttachmentSlot.FRONT, AttachmentSlot.VISA), p.attachments.map { it.slot })
        assertEquals("Japan", p.attachments[1].customTitle)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), contents.files[p.attachments[0].id])
        assertArrayEquals(byteArrayOf(5, 6, 7), contents.files[p.attachments[1].id])

        val s = contents.documents.first { it.id == protectedDoc.id }
        assertTrue(s.isPasswordProtected)
        assertEquals("c2FsdA==", s.passwordSaltBase64)
        assertEquals("ZGVr", s.encryptedDekBase64)
        assertEquals("aXY=", s.dekIvBase64)
        assertEquals("IV is needed to decrypt the protected attachment", secret.ivBase64, s.attachments.single().ivBase64)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9, 9), contents.files[s.attachments.single().id])

        val deletedAt = contents.deletions.getValue(deletedId)
        assertTrue(deletedAt >= 1_750_000_000_000 && deletedAt < 1_750_000_001_000)
    }

    @Test
    fun tokenIsCaseAndSpaceInsensitive() {
        val contents = KdbxBackupFormat.read(ByteArrayInputStream(backup()), " pcase-abcd-efgh-jklm-npqr-stuv ")
        assertEquals(2, contents.documents.size)
    }

    @Test
    fun backupPasswordIsUsedExactlyAsTyped() {
        val out = ByteArrayOutputStream()
        runBlocking {
            KdbxBackupFormat.write(out, "Correct horse battery", listOf(passport), emptyMap(), exactPassword = true) { _, att ->
                bytes.getValue(att.id)
            }
        }
        val file = out.toByteArray()
        assertEquals(1, KdbxBackupFormat.read(ByteArrayInputStream(file), "Correct horse battery").documents.size)
        try {
            KdbxBackupFormat.read(ByteArrayInputStream(file), "CORRECTHORSEBATTERY")
            fail("password must match exactly")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("incorrect"))
        }
    }

    @Test
    fun keepsReminderSetting() {
        val out = ByteArrayOutputStream()
        runBlocking {
            KdbxBackupFormat.write(out, token, listOf(passport.copy(reminderDays = 180)), emptyMap()) { _, att -> bytes.getValue(att.id) }
        }
        assertEquals(180, KdbxBackupFormat.read(ByteArrayInputStream(out.toByteArray()), token).documents.single().reminderDays)
    }

    @Test
    fun keepsSettingsPassphraseAndReminderStatus() {
        val reminder = ReminderEntry(
            docId = passport.id, title = passport.title, expiresOn = "2031-01-01", remindOn = "2030-07-05",
            notifiedEarly = true, lastExpiredNotice = null, stopped = true
        )
        val extras = BackupExtras(
            settings = JSONObject().put("locker_timeout_minutes", 2),
            passphrase = "correct horse battery staple",
            reminders = listOf(reminder)
        )
        val out = ByteArrayOutputStream()
        runBlocking {
            KdbxBackupFormat.write(out, token, listOf(passport), emptyMap(), extras) { _, att -> bytes.getValue(att.id) }
        }
        val contents = KdbxBackupFormat.read(ByteArrayInputStream(out.toByteArray()), token)

        assertEquals("the passphrase entry isn't a document", 1, contents.documents.size)
        assertEquals("correct horse battery staple", contents.extras.passphrase)
        assertEquals(2, contents.extras.settings!!.getInt("locker_timeout_minutes"))
        assertEquals(listOf(reminder), contents.extras.reminders)
    }

    @Test
    fun foldersBecomeNestedGroups() {
        val kids = Folder(name = "Kids", createdAt = 1_700_000_000_000, updatedAt = 1_760_000_000_123)
        val jane = Folder(name = "Jane", parentId = kids.id, createdAt = 1_700_000_000_000, updatedAt = 1_760_000_000_456)
        val out = ByteArrayOutputStream()
        runBlocking {
            KdbxBackupFormat.write(
                out, token, listOf(passport.copy(folderId = jane.id), protectedDoc), emptyMap(), folders = listOf(kids, jane)
            ) { _, att -> bytes.getValue(att.id) }
        }
        val contents = KdbxBackupFormat.read(ByteArrayInputStream(out.toByteArray()), token)

        assertEquals(setOf(kids, jane), contents.folders.toSet())
        assertEquals(jane.id, contents.documents.first { it.id == passport.id }.folderId)
        assertEquals("", contents.documents.first { it.id == protectedDoc.id }.folderId)
    }

    @Test
    fun backupWithoutExtrasHasNone() {
        val contents = KdbxBackupFormat.read(ByteArrayInputStream(backup()), token)
        assertEquals(null, contents.extras.passphrase)
        assertEquals(null, contents.extras.settings)
        assertTrue(contents.extras.reminders.isEmpty())
    }

    @Test
    fun wrongTokenIsRejected() {
        try {
            KdbxBackupFormat.read(ByteArrayInputStream(backup()), "PCASE-AAAA-AAAA-AAAA-AAAA-AAAA")
            fail("expected wrong token to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("incorrect"))
        }
    }
}
