package com.mattintech.papercase.locker.reminders

import com.mattintech.papercase.locker.backup.AutoBackupManager
import com.mattintech.papercase.locker.model.DocumentType
import com.mattintech.papercase.locker.model.SecureDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExpiryRemindersTest {

    private fun doc(expiry: String, days: Int, id: String = "a") =
        SecureDocument(id = id, title = "Passport", type = DocumentType.PASSPORT, expirationDate = expiry, reminderDays = days)

    @Test
    fun parsesPickerAndCommonFormats() {
        assertEquals(LocalDate.of(2027, 4, 12), ExpiryDates.parse("2027-04-12"))
        assertEquals(LocalDate.of(2027, 4, 12), ExpiryDates.parse("12/04/2027"))
        assertEquals(LocalDate.of(2027, 4, 12), ExpiryDates.parse(" 12.04.2027 "))
        assertNull(ExpiryDates.parse(""))
        assertNull(ExpiryDates.parse("next spring"))
    }

    @Test
    fun buildsEntriesOnlyForDocumentsWithReminderAndDate() {
        val entries = ExpiryDates.buildEntries(
            listOf(doc("2027-04-12", 180, "a"), doc("2027-04-12", 0, "b"), doc("", 30, "c")),
            emptyList()
        )
        assertEquals(listOf("a"), entries.map { it.docId })
        assertEquals("2026-10-14", entries.single().remindOn)
    }

    @Test
    fun keepsNotifiedFlagsUnlessDatesChange() {
        val sent = ExpiryDates.buildEntries(listOf(doc("2027-04-12", 30)), emptyList()).map { it.copy(notifiedEarly = true) }
        assertTrue(ExpiryDates.buildEntries(listOf(doc("2027-04-12", 30)), sent).single().notifiedEarly)
        // Renewed: new expiry date, so the reminder fires again for the new date
        assertFalse(ExpiryDates.buildEntries(listOf(doc("2037-04-12", 30)), sent).single().notifiedEarly)
    }

    @Test
    fun earlyOnceThenEveryTwoWeeksAfterExpiryUntilStopped() {
        val e = ExpiryDates.buildEntries(listOf(doc("2027-04-12", 30)), emptyList()).single()
        assertEquals(ExpiryDates.Due.NONE, ExpiryDates.due(e, LocalDate.of(2027, 3, 12)))
        assertEquals(ExpiryDates.Due.EARLY, ExpiryDates.due(e, LocalDate.of(2027, 3, 13)))
        val early = e.copy(notifiedEarly = true)
        assertEquals(ExpiryDates.Due.NONE, ExpiryDates.due(early, LocalDate.of(2027, 4, 1)))
        assertEquals(ExpiryDates.Due.EXPIRED, ExpiryDates.due(early, LocalDate.of(2027, 4, 12)))
        // Still expired and not updated: again after 14 days, not before
        val sent = early.copy(lastExpiredNotice = "2027-04-12")
        assertEquals(ExpiryDates.Due.NONE, ExpiryDates.due(sent, LocalDate.of(2027, 4, 25)))
        assertEquals(ExpiryDates.Due.EXPIRED, ExpiryDates.due(sent, LocalDate.of(2027, 4, 26)))
        assertEquals(ExpiryDates.Due.NONE, ExpiryDates.due(sent.copy(stopped = true), LocalDate.of(2027, 6, 1)))
    }

    @Test
    fun stoppingIsKeptUntilTheDateChanges() {
        val stopped = ExpiryDates.buildEntries(listOf(doc("2027-04-12", 30)), emptyList()).map { it.copy(stopped = true) }
        assertTrue(ExpiryDates.buildEntries(listOf(doc("2027-04-12", 30)), stopped).single().stopped)
        assertFalse(ExpiryDates.buildEntries(listOf(doc("2037-04-12", 30)), stopped).single().stopped)
    }

    @Test
    fun defaultsByType() {
        assertEquals(180, ReminderOptions.defaultFor(DocumentType.PASSPORT))
        assertEquals("6 months before", ReminderOptions.label(180))
    }

    @Test
    fun prunesOnlyOldAutomaticBackups() {
        val names = (1..7).map { "Papercase_Auto_2026010${it}_090000.kdbx" } +
            listOf("Papercase_Backup_20250101_000000.kdbx", "notes.txt")
        assertEquals(
            listOf("Papercase_Auto_20260102_090000.kdbx", "Papercase_Auto_20260101_090000.kdbx"),
            AutoBackupManager.filesToPrune(names, 5)
        )
    }
}
