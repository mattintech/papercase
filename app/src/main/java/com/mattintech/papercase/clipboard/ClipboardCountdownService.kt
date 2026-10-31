package com.mattintech.papercase.clipboard

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mattintech.papercase.R
import com.mattintech.papercase.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClipboardCountdownService : Service() {

    companion object {
        const val ACTION_START_COUNTDOWN = "com.mattintech.papercase.ACTION_START_COUNTDOWN"
        const val ACTION_STOP_COUNTDOWN = "com.mattintech.papercase.ACTION_STOP_COUNTDOWN"
        const val EXTRA_TIMEOUT_SECONDS = "extra_timeout_seconds"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "ClipboardCountdownSvc"

        fun start(context: Context, timeoutSeconds: Int) {
            val intent = Intent(context, ClipboardCountdownService::class.java).apply {
                action = ACTION_START_COUNTDOWN
                putExtra(EXTRA_TIMEOUT_SECONDS, timeoutSeconds)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClipboardCountdownService::class.java).apply {
                action = ACTION_STOP_COUNTDOWN
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var countdownJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_COUNTDOWN -> {
                val timeoutSeconds = intent.getIntExtra(EXTRA_TIMEOUT_SECONDS, 10)
                startCountdown(timeoutSeconds)
            }
            ACTION_STOP_COUNTDOWN -> {
                stopCountdownAndSelf()
            }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCountdown(timeoutSeconds: Int) {
        countdownJob?.cancel()

        val notification = buildNotification(timeoutSeconds)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        countdownJob = serviceScope.launch {
            for (remaining in timeoutSeconds downTo 1) {
                ClipboardSecurityManager.updateRemaining(remaining)
                updateNotification(remaining, timeoutSeconds)
                delay(1000L)
            }

            ClipboardSecurityManager.updateRemaining(0)

            // Direct canonical clear via ClipboardSecurityManager
            ClipboardSecurityManager.doExecuteClear(applicationContext)

            val prefs = PreferencesManager(applicationContext)
            prefs.lastCopiedTimestamp = 0L

            delay(300L)
            ClipboardSecurityManager.updateRemaining(null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopCountdownAndSelf() {
        countdownJob?.cancel()
        ClipboardSecurityManager.doExecuteClear(applicationContext)
        val prefs = PreferencesManager(applicationContext)
        prefs.lastCopiedTimestamp = 0L
        ClipboardSecurityManager.updateRemaining(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(remainingSeconds: Int, totalSeconds: Int = remainingSeconds): Notification {
        val clearIntent = Intent(this, ClearClipboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ClipboardSecurityManager.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.clipboard_active_desc, remainingSeconds))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_clear,
                getString(R.string.notification_action_clear),
                pendingIntent
            )
            .setContentIntent(pendingIntent)
            .setProgress(totalSeconds, remainingSeconds, false)
            .build()
    }

    private fun updateNotification(remainingSeconds: Int, totalSeconds: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(remainingSeconds, totalSeconds))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    // A short service must stop when Android's time limit is up. The countdown is far shorter, but if
    // it ever gets here, clear the clipboard now rather than leave it.
    override fun onTimeout(startId: Int) = stopCountdownAndSelf()

    override fun onTimeout(startId: Int, fgsType: Int) = stopCountdownAndSelf()

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
