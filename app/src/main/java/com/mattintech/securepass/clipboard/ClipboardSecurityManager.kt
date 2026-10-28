package com.mattintech.securepass.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.mattintech.securepass.R
import com.mattintech.securepass.data.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ClipboardSecurityManager {

    private const val TAG = "ClipboardSecurityMgr"
    const val NOTIFICATION_CHANNEL_ID = "clipboard_security_channel"

    private val _countdownRemaining = MutableStateFlow<Int?>(null)
    val countdownRemaining: StateFlow<Int?> = _countdownRemaining.asStateFlow()

    private val _countdownTotal = MutableStateFlow(10)
    val countdownTotal: StateFlow<Int> = _countdownTotal.asStateFlow()

    fun updateRemaining(remaining: Int?) {
        _countdownRemaining.value = remaining
    }

    fun createNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val name = context.getString(R.string.notification_channel_name)
        val description = context.getString(R.string.notification_channel_desc)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
            this.description = description
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun copyToClipboard(
        context: Context,
        passphrase: String,
        timeoutSeconds: Int = 10
    ) {
        val appContext = context.applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SecurePassphrase", passphrase)

        // Mark as sensitive on Android 13+ (API 33+) so keyboard suggestions & preview overlay hide it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

        try {
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set primary clip", e)
            return
        }

        val prefs = PreferencesManager(appContext)
        val now = System.currentTimeMillis()
        prefs.lastCopiedTimestamp = now

        if (timeoutSeconds <= 0) {
            _countdownRemaining.value = null
            return
        }

        _countdownTotal.value = timeoutSeconds
        _countdownRemaining.value = timeoutSeconds

        // Start Foreground Service to guarantee execution and prevent Samsung Freecess from suspending the timer
        ClipboardCountdownService.start(appContext, timeoutSeconds)
    }

    /**
     * Executes the actual clipboard wipe:
     * Overwrites with an empty ClipData first (which forces keyboards like Gboard / Samsung Keyboard
     * to drop the cached password), then calls clearPrimaryClip() to erase the primary clip.
     */
    fun doExecuteClear(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        try {
            val emptyClip = ClipData.newPlainText("", "")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                emptyClip.description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(emptyClip)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear clipboard (may be background restricted): ${e.message}")
        }
    }

    /**
     * Checks if the active clipboard copy has exceeded its timeout duration,
     * and if so, performs a full clear sequence immediately while the caller has foreground focus.
     */
    fun checkAndClearIfExpired(context: Context) {
        val prefs = PreferencesManager(context)
        val lastCopied = prefs.lastCopiedTimestamp
        val timeoutMs = prefs.clearTimeoutSeconds * 1000L

        if (lastCopied > 0L && (System.currentTimeMillis() - lastCopied >= timeoutMs)) {
            clearClipboard(context)
        }
    }

    fun clearClipboard(context: Context) {
        _countdownRemaining.value = null
        val appContext = context.applicationContext

        val prefs = PreferencesManager(appContext)
        prefs.lastCopiedTimestamp = 0L

        ClipboardCountdownService.stop(appContext)
        doExecuteClear(appContext)
    }
}
