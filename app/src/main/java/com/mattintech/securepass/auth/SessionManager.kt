package com.mattintech.securepass.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.mattintech.securepass.MainActivity
import com.mattintech.securepass.R

object SessionManager {

    /** Default vault session timeout: 5 minutes */
    const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L

    /** Background inactivity timeout: 30 seconds */
    const val BACKGROUND_INACTIVITY_TIMEOUT_MS = 30 * 1000L

    const val NOTIFICATION_CHANNEL_ID = "locker_security_channel"
    const val NOTIFICATION_ID = 9002

    var timeProvider: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.currentTimeMillis()
        }
    }

    private var lastAuthTime: Long = 0L

    var isLockerUnlocked: Boolean
        get() = isSessionValid()
        set(value) {
            if (value) {
                markAuthenticated()
            } else {
                lock()
            }
        }

    private var isInForeground: Boolean = true
    private var backgroundTimestamp: Long = 0L
    private var appContext: Context? = null

    private val handler: Handler? by lazy {
        try {
            Handler(Looper.getMainLooper())
        } catch (_: Exception) {
            null
        }
    }

    private val backgroundLockRunnable = Runnable {
        checkAndLockDueToInactivity()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        createNotificationChannel(context)
    }

    /**
     * Marks the session as authenticated (e.g. following successful biometric scan).
     */
    fun markAuthenticated() {
        lastAuthTime = timeProvider()
        cancelBackgroundTimer()
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
        cancelBackgroundTimer()
    }

    /**
     * Called when the application as a whole enters the foreground.
     */
    fun onAppEnteredForeground() {
        isInForeground = true
        cancelBackgroundTimer()

        // If the app stayed in the background for >= 30 seconds while unlocked, lock and notify
        if (backgroundTimestamp > 0L) {
            val elapsedInBackground = timeProvider() - backgroundTimestamp
            if (lastAuthTime > 0L && elapsedInBackground >= BACKGROUND_INACTIVITY_TIMEOUT_MS) {
                lock()
                appContext?.let { notifyLockerLocked(it) }
            }
            backgroundTimestamp = 0L
        }
    }

    /**
     * Called when the application as a whole transitions to the background.
     */
    fun onAppEnteredBackground() {
        isInForeground = false
        if (lastAuthTime > 0L) {
            backgroundTimestamp = timeProvider()
            cancelBackgroundTimer()
            handler?.postDelayed(backgroundLockRunnable, BACKGROUND_INACTIVITY_TIMEOUT_MS)
        }
    }

    private fun cancelBackgroundTimer() {
        handler?.removeCallbacks(backgroundLockRunnable)
    }

    fun checkAndLockDueToInactivity() {
        if (!isInForeground && lastAuthTime > 0L) {
            lock()
            appContext?.let { notifyLockerLocked(it) }
        }
    }

    fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val name = "Locker Security"
        val description = "Alerts when Secure Locker is locked due to inactivity"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
            this.description = description
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notifyLockerLocked(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_folder_lock)
            .setContentTitle("Secure Locker Locked")
            .setContentText("Secure Locker was locked due to 30 seconds of inactivity.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Secure Locker was automatically locked due to 30 seconds of inactivity in the background.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    internal fun setAuthTimeForTesting(time: Long) {
        lastAuthTime = time
    }

    internal fun setForegroundForTesting(foreground: Boolean) {
        isInForeground = foreground
    }

    internal fun setBackgroundTimestampForTesting(time: Long) {
        backgroundTimestamp = time
    }
}
