package com.mattintech.papercase.locker.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/** "Stop reminders" on an expiry notification. Works without unlocking: it only touches the reminder list. */
class StopReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val docId = intent.getStringExtra(EXTRA_DOC_ID) ?: return
        val pending = goAsync()
        Thread {
            try {
                ReminderStore.stop(context, docId)
                NotificationManagerCompat.from(context).cancel(docId.hashCode())
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_DOC_ID = "doc_id"
    }
}
