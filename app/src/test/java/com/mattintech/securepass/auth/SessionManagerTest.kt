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

    @Test
    fun testBackgroundTimeout_locksSessionAfter30Seconds() {
        SessionManager.markAuthenticated()
        assertTrue(SessionManager.isLockerUnlocked)

        SessionManager.onAppEnteredBackground()
        // Advance 31 seconds in background
        fakeCurrentTime += 31_000L

        SessionManager.onAppEnteredForeground()
        assertFalse("Locker should be locked after 30s in background", SessionManager.isLockerUnlocked)
    }

    @Test
    fun testBackgroundResumeWithin30Seconds_staysUnlocked() {
        SessionManager.markAuthenticated()
        assertTrue(SessionManager.isLockerUnlocked)

        SessionManager.onAppEnteredBackground()
        // Advance only 15 seconds in background
        fakeCurrentTime += 15_000L

        SessionManager.onAppEnteredForeground()
        assertTrue("Locker should remain unlocked when returning within 30s", SessionManager.isLockerUnlocked)
    }

    @Test
    fun testBackgroundCheckAndLockDirectly() {
        SessionManager.markAuthenticated()
        SessionManager.onAppEnteredBackground()
        SessionManager.checkAndLockDueToInactivity()
        assertFalse("Locker should be locked by inactivity check", SessionManager.isLockerUnlocked)
    }
}
