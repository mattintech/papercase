package com.mattintech.papercase.locker.reminders

import com.mattintech.papercase.locker.model.DocumentType
import com.mattintech.papercase.locker.model.SecureDocument
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Reminder choices shown on each document, in days before it expires (0 = off). */
object ReminderOptions {

    data class Option(val days: Int, val label: String)

    val ALL = listOf(
        Option(0, "No reminder"),
        Option(7, "1 week before"),
        Option(14, "2 weeks before"),
        Option(30, "1 month before"),
        Option(60, "2 months before"),
        Option(90, "3 months before"),
        Option(180, "6 months before"),
        Option(365, "1 year before"),
    )

    fun label(days: Int): String =
        ALL.firstOrNull { it.days == days }?.label ?: "$days days before"

    /** Sensible lead time per type: passports often need 6 months' validity to travel. */
    fun defaultFor(type: DocumentType): Int = when (type) {
        DocumentType.PASSPORT -> 180
        DocumentType.ID_CARD -> 90
        DocumentType.DRIVERS_LICENSE -> 60
        DocumentType.INSURANCE -> 30
        DocumentType.PDF_DOCUMENT -> 0
        DocumentType.OTHER -> 30
    }
}

/**
 * A scheduled reminder for one document. Dates are ISO yyyy-MM-dd.
 * After expiry the reminder repeats every [ExpiryDates.REPEAT_AFTER_EXPIRY_DAYS] days until the
 * document gets a new date, or the user stops it ([stopped]).
 */
data class ReminderEntry(
    val docId: String,
    val title: String,
    val expiresOn: String,
    val remindOn: String,
    val notifiedEarly: Boolean = false,
    val lastExpiredNotice: String? = null,
    val stopped: Boolean = false,
)

object ExpiryDates {

    /** How often to keep reminding once a document has expired and hasn't been updated. */
    const val REPEAT_AFTER_EXPIRY_DAYS = 14L

    private val FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
    )

    /** Expiration dates are picked as yyyy-MM-dd; older or imported entries may use other common formats. */
    fun parse(text: String): LocalDate? {
        val t = text.trim()
        if (t.isEmpty()) return null
        for (f in FORMATS) {
            try {
                return LocalDate.parse(t, f)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    /**
     * Rebuilds the reminder list from the locker, keeping "already notified" flags for documents
     * whose dates didn't change so nobody gets the same reminder twice.
     */
    fun buildEntries(documents: List<SecureDocument>, previous: List<ReminderEntry>): List<ReminderEntry> {
        val old = previous.associateBy { it.docId }
        return documents.mapNotNull { doc ->
            if (doc.reminderDays <= 0) return@mapNotNull null
            val expires = parse(doc.expirationDate) ?: return@mapNotNull null
            val entry = ReminderEntry(
                docId = doc.id,
                title = doc.title,
                expiresOn = expires.toString(),
                remindOn = expires.minusDays(doc.reminderDays.toLong()).toString(),
            )
            val prior = old[doc.id]
            if (prior != null && prior.expiresOn == entry.expiresOn && prior.remindOn == entry.remindOn) {
                entry.copy(notifiedEarly = prior.notifiedEarly, lastExpiredNotice = prior.lastExpiredNotice, stopped = prior.stopped)
            } else {
                entry
            }
        }
    }

    enum class Due { NONE, EARLY, EXPIRED }

    /** What, if anything, should be sent for [entry] on [today]. */
    fun due(entry: ReminderEntry, today: LocalDate): Due {
        if (entry.stopped) return Due.NONE
        val expires = LocalDate.parse(entry.expiresOn)
        val remindOn = LocalDate.parse(entry.remindOn)
        return when {
            !today.isBefore(expires) -> {
                val last = entry.lastExpiredNotice?.let(LocalDate::parse)
                if (last == null || !today.isBefore(last.plusDays(REPEAT_AFTER_EXPIRY_DAYS))) Due.EXPIRED else Due.NONE
            }
            !today.isBefore(remindOn) -> if (entry.notifiedEarly) Due.NONE else Due.EARLY
            else -> Due.NONE
        }
    }
}
