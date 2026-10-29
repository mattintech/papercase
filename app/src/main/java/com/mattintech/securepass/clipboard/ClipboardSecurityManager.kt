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

    /**
     * Copies sensitive text to the clipboard with:
     * 1. EXTRA_IS_SENSITIVE flag on API 33+ (prevents clipboard previews / keyboard caching)
     * 2. Auto-clear timer via foreground service
     * 3. Live countdown state for UI banners
     */
    fun copyToClipboard(
        context: Context,
        passphrase: String,
        timeoutSeconds: Int = 10,
        label: String = "SecurePass"
    ) {
        val appContext = context.applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, passphrase)

        // Mark as sensitive on Android 13+ (API 33+) so keyboard suggestions & preview overlay hide it
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

        try {
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting primary clip", e)
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

        // Start Foreground Service to guarantee timeout execution
        ClipboardCountdownService.start(appContext, timeoutSeconds)
    }

    /**
     * Single canonical clipboard clear routine:
     * Directly invokes clearPrimaryClip() to avoid spurious "Copied to clipboard" toasts
     * that occur when overwriting with empty text on One UI.
     */
    fun doExecuteClear(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear clipboard: ${e.message}")
        }
    }

    /**
     * Checks if the active clipboard copy has exceeded its timeout duration,
     * and if so, performs a full clear sequence immediately.
     */
    fun checkAndClearIfExpired(context: Context) {
        val prefs = PreferencesManager(context)
        val lastCopied = prefs.lastCopiedTimestamp
        val timeoutMs = prefs.clearTimeoutSeconds * 1000L

        if (lastCopied > 0L && (System.currentTimeMillis() - lastCopied >= timeoutMs)) {
            clearClipboard(context)
        }
    }

    /**
     * Clears clipboard, stops countdown service, resets preferences, and clears remaining count.
     */
    fun clearClipboard(context: Context) {
        _countdownRemaining.value = null
        val appContext = context.applicationContext

        val prefs = PreferencesManager(appContext)
        prefs.lastCopiedTimestamp = 0L

        ClipboardCountdownService.stop(appContext)
        doExecuteClear(appContext)
    }
}
