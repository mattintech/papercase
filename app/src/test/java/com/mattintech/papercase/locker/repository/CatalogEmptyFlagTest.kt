package com.mattintech.papercase.locker.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogEmptyFlagTest {

    @Test
    fun noCatalogYet_isEmpty() {
        assertTrue(SecureLockerRepository.isEmptyCatalog(null))
    }

    @Test
    fun catalogFlaggedEmpty_isEmpty() {
        assertTrue(SecureLockerRepository.isEmptyCatalog("""{"ciphertext":"x","iv":"y","keyVersion":2,"empty":true}"""))
    }

    @Test
    fun catalogFlaggedNotEmpty_isNotEmpty() {
        assertFalse(SecureLockerRepository.isEmptyCatalog("""{"ciphertext":"x","iv":"y","keyVersion":2,"empty":false}"""))
    }

    @Test
    fun catalogFromBeforeTheFlag_staysLocked() {
        assertFalse(SecureLockerRepository.isEmptyCatalog("""{"ciphertext":"x","iv":"y","keyVersion":2}"""))
    }

    @Test
    fun unreadableCatalog_staysLocked() {
        assertFalse(SecureLockerRepository.isEmptyCatalog("not json"))
    }
}
