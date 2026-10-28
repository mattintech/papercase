package com.mattintech.securepass.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mattintech.securepass.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ClipboardSecurityManager {

    private const val TAG = "ClipboardSecurityMgr"
    const val NOTIFICATION_CHANNEL_ID = "clipboard_security_channel"
    private const val NOTIFICATION_ID = 1001

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdownJob: Job? = null

    private val _countdownRemaining = MutableStateFlow<Int?>(null)
    val countdownRemaining: StateFlow<Int?> = _countdownRemaining.asStateFlow()

    private val _countdownTotal = MutableStateFlow(10)
    val countdownTotal: StateFlow<Int> = _countdownTotal.asStateFlow()

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
        timeoutSeconds: Int = 10,
        onClearedCallback: (() -> Unit)? = null
    ) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SecurePassphrase", passphrase)

        // Mark as sensitive on Android 13+ (API 33+) so keyboard / system UI hides it
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

        countdownJob?.cancel()

        if (timeoutSeconds <= 0) {
            _countdownRemaining.value = null
            return
        }

        _countdownTotal.value = timeoutSeconds
        _countdownRemaining.value = timeoutSeconds

        showNotification(context, timeoutSeconds)

        countdownJob = scope.launch {
            for (remaining in timeoutSeconds downTo 1) {
                _countdownRemaining.value = remaining
                delay(1000L)
            }
            _countdownRemaining.value = 0
            clearClipboard(context)
            onClearedCallback?.invoke()
            delay(1000L)
            _countdownRemaining.value = null
        }
    }

    fun clearClipboard(context: Context) {
        countdownJob?.cancel()
        countdownJob = null
        _countdownRemaining.value = null

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        try {
            clipboard?.clearPrimaryClip()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear clipboard directly (may be in background): ${e.message}")
        }

        dismissNotification(context)
    }

    private fun showNotification(context: Context, remainingSeconds: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val clearIntent = Intent(context, ClearClipboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.clipboard_active_desc, remainingSeconds))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_clear,
                context.getString(R.string.notification_action_clear),
                pendingIntent
            )
            .setContentIntent(pendingIntent)
            .setTimeoutAfter(remainingSeconds * 1000L + 2000L)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Notification post failed: ${e.message}")
        }
    }

    private fun dismissNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dismiss notification", e)
        }
    }
}
