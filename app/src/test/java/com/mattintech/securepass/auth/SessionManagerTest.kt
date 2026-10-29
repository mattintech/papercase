package com.mattintech.securepass.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionManagerTest {

    private var fakeCurrentTime = 100_000L

    @Before
    fun setUp() {
        fakeCurrentTime = 100_000L
        SessionManager.timeProvider = { fakeCurrentTime }
        SessionManager.lock()
    }

    @Test
    fun testInitialState_isLocked() {
        assertFalse(SessionManager.isSessionValid())
    }

    @Test
    fun testMarkAuthenticated_isValid() {
        SessionManager.markAuthenticated()
        assertTrue(SessionManager.isSessionValid())
    }

    @Test
    fun testLock_invalidatesSession() {
        SessionManager.markAuthenticated()
        assertTrue(SessionManager.isSessionValid())

        SessionManager.lock()
        assertFalse(SessionManager.isSessionValid())
    }

    @Test
    fun testSessionValidityWithinTimeout() {
        SessionManager.markAuthenticated()
        // Advance time by 4 minutes (timeout is 5 minutes)
        fakeCurrentTime += 4 * 60 * 1000L
        assertTrue(SessionManager.isSessionValid())
    }

    @Test
    fun testSessionExpirationPastTimeout() {
        SessionManager.markAuthenticated()
        // Advance time by 5 minutes and 1 second
        fakeCurrentTime += (5 * 60 * 1000L) + 1000L
        assertFalse(SessionManager.isSessionValid())
    }

    @Test
    fun testKeepAliveExtendsSession() {
        SessionManager.markAuthenticated()
        // Advance time by 4 minutes
        fakeCurrentTime += 4 * 60 * 1000L
        // User interacts with app
        SessionManager.keepAlive()
        // Advance another 4 minutes (total 8 minutes from start, but only 4 from keepAlive)
        fakeCurrentTime += 4 * 60 * 1000L
        assertTrue(SessionManager.isSessionValid())
    }
}
