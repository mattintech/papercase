package com.mattintech.papercase.data

import com.mattintech.papercase.locker.data.IssuingAuthorityHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuingAuthorityTest {

    @Test
    fun testBaseAuthoritiesIncludeCountriesAndAgencies() {
        val list = IssuingAuthorityHelper.getBaseAuthorities()
        assertTrue(list.isNotEmpty())
        assertTrue(list.contains("Canada"))
        assertTrue(list.contains("United States"))
        assertTrue(list.contains("United Kingdom"))
        assertTrue(list.contains("Passport Canada"))
        assertTrue(list.contains("California DMV"))
        assertFalse(list.contains(""))
    }
}
