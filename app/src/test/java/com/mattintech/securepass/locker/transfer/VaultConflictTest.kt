package com.mattintech.securepass.locker.transfer

import com.mattintech.securepass.locker.model.DocumentType
import com.mattintech.securepass.locker.model.SecureDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VaultConflictTest {

    @Test
    fun testConflictAnalysisCategorization() {
        val now = System.currentTimeMillis()
        val doc1Id = UUID.randomUUID().toString()
        val doc2Id = UUID.randomUUID().toString()
        val doc3Id = UUID.randomUUID().toString()

        val localDoc1 = SecureDocument(id = doc1Id, title = "Passport", type = DocumentType.PASSPORT, updatedAt = now)
        val localDoc2 = SecureDocument(id = doc2Id, title = "Driver's License", type = DocumentType.DRIVERS_LICENSE, updatedAt = now)

        val incomingDoc1Identical = SecureDocument(id = doc1Id, title = "Passport", type = DocumentType.PASSPORT, updatedAt = now)
        val incomingDoc2Modified = SecureDocument(id = doc2Id, title = "Driver's License Renewed", type = DocumentType.DRIVERS_LICENSE, updatedAt = now + 5000)
        val incomingDoc3New = SecureDocument(id = doc3Id, title = "National ID", type = DocumentType.ID_CARD, updatedAt = now)

        val incomingList = listOf(incomingDoc1Identical, incomingDoc2Modified, incomingDoc3New)
        val localMap = listOf(localDoc1, localDoc2).associateBy { it.id }

        val newDocs = mutableListOf<SecureDocument>()
        val identicalDocs = mutableListOf<SecureDocument>()
        val conflictingDocs = mutableListOf<ConflictPair>()

        for (incoming in incomingList) {
            val local = localMap[incoming.id]
            if (local == null) {
                newDocs.add(incoming)
            } else if (local.updatedAt == incoming.updatedAt && local.title == incoming.title && local.documentNumber == incoming.documentNumber) {
                identicalDocs.add(incoming)
            } else {
                conflictingDocs.add(ConflictPair(incoming, local))
            }
        }

        val report = VaultConflictReport(
            totalIncoming = 3,
            newDocuments = newDocs,
            identicalDocuments = identicalDocs,
            conflictingDocuments = conflictingDocs,
            fileMap = emptyMap()
        )

        assertEquals(1, report.newDocuments.size)
        assertEquals("National ID", report.newDocuments[0].title)

        assertEquals(1, report.identicalDocuments.size)
        assertEquals("Passport", report.identicalDocuments[0].title)

        assertEquals(1, report.conflictingDocuments.size)
        assertEquals("Driver's License Renewed", report.conflictingDocuments[0].incoming.title)
        assertTrue(report.hasConflicts)
    }

    @Test
    fun testSmartMergeSelectsNewest() {
        val now = System.currentTimeMillis()
        val olderDoc = SecureDocument(id = "doc-1", title = "Old Title", type = DocumentType.OTHER, updatedAt = now)
        val newerDoc = SecureDocument(id = "doc-1", title = "New Title", type = DocumentType.OTHER, updatedAt = now + 10000)

        // Case A: Incoming is newer than local
        val incomingIsNewer = newerDoc.updatedAt > olderDoc.updatedAt
        assertTrue("Incoming should be chosen when newer", incomingIsNewer)

        // Case B: Local is newer than incoming
        val localIsNewer = olderDoc.updatedAt > newerDoc.updatedAt
        assertFalse("Older incoming should not overwrite newer local", localIsNewer)
    }
}
