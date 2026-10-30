package com.mattintech.papercase.locker.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mattintech.papercase.MainActivity
import com.mattintech.papercase.R
import com.mattintech.papercase.data.PreferencesManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Sends expiry reminders. The lock screen only shows that a document expires soon. */
object ReminderNotifier {

    const val CHANNEL_ID = "expiry_reminders"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Expiry reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Reminders before documents in your locker expire"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Sends whatever is due today and records it, so each reminder is sent once. */
    fun checkAndNotify(context: Context, today: LocalDate = LocalDate.now()) {
        if (!PreferencesManager(context).isExpiryRemindersEnabled || !canNotify(context)) return
        val entries = ReminderStore.load(context)
        if (entries.isEmpty()) return

        var changed = false
        val updated = entries.map { entry ->
            when (ExpiryDates.due(entry, today)) {
                ExpiryDates.Due.EARLY -> {
                    notify(context, entry, expired = false)
                    changed = true
                    entry.copy(notifiedEarly = true)
                }
                ExpiryDates.Due.EXPIRED -> {
                    notify(context, entry, expired = true)
                    changed = true
                    entry.copy(notifiedEarly = true, lastExpiredNotice = today.toString())
                }
                ExpiryDates.Due.NONE -> entry
            }
        }
        if (changed) ReminderStore.save(context, updated)
    }

    @Suppress("MissingPermission") // checked in canNotify()
    private fun notify(context: Context, entry: ReminderEntry, expired: Boolean) {
        val date = LocalDate.parse(entry.expiresOn).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        val title = if (expired) "${entry.title} expired on $date" else "${entry.title} expires on $date"
        val text = if (expired) {
            "Renew it, then update the date in Papercase. You'll be reminded every 2 weeks until then."
        } else {
            "Time to start renewing it."
        }

        val stop = PendingIntent.getBroadcast(
            context,
            entry.docId.hashCode(),
            Intent(context, StopReminderReceiver::class.java).putExtra(StopReminderReceiver.EXTRA_DOC_ID, entry.docId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val open = PendingIntent.getActivity(
            context,
            entry.docId.hashCode(),
            // Opens that document once the user unlocks the locker
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_DOCUMENT_ID, entry.docId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // What the lock screen shows instead of the document name and date
        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_folder_lock)
            .setContentTitle("Papercase")
            .setContentText(if (expired) "A document in your locker has expired" else "A document in your locker expires soon")
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_folder_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, "Stop reminders", stop)
            .build()

        NotificationManagerCompat.from(context).notify(entry.docId.hashCode(), notification)
    }
}
