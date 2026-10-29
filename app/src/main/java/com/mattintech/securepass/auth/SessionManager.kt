package com.mattintech.securepass.auth

import android.os.SystemClock

object SessionManager {

    /** Default vault session timeout: 5 minutes */
    const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L

    var timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.currentTimeMillis()
        }
    }

    private var lastAuthTime: Long = 0L

    /**
     * Marks the session as authenticated (e.g. following successful biometric scan).
     */
    fun markAuthenticated() {
        lastAuthTime = timeProvider()
    }

    /**
     * Checks whether the biometric session is currently active and unexpired.
     */
    fun isSessionValid(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (lastAuthTime == 0L) return false
        val elapsed = timeProvider() - lastAuthTime
        return elapsed in 0..timeoutMs
    }

    /**
     * Updates activity timestamp while user is actively interacting with the app.
     */
    fun keepAlive() {
        if (lastAuthTime > 0L) {
            lastAuthTime = timeProvider()
        }
    }

    /**
     * Manually terminates the session and relocks the vault.
     */
    fun lock() {
        lastAuthTime = 0L
    }

    internal fun setAuthTimeForTesting(time: Long) {
        lastAuthTime = time
    }
}
