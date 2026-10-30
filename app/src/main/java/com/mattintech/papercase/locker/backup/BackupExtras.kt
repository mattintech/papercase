package com.mattintech.papercase.locker.backup

import android.content.Context
import com.mattintech.papercase.data.PreferencesManager
import com.mattintech.papercase.locker.reminders.ReminderEntry
import com.mattintech.papercase.locker.reminders.ReminderStore
import org.json.JSONObject

/** Everything in a backup besides the documents: settings, the stored passphrase and reminder status. */
data class BackupExtras(
    val settings: JSONObject? = null,
    val passphrase: String? = null,
    val reminders: List<ReminderEntry> = emptyList()
) {
    companion object {
        /** Call while the locker is unlocked: the passphrase copy is encrypted with the locker key. */
        fun collect(context: Context) = BackupExtras(
            settings = PreferencesManager(context).exportSettings(),
            passphrase = runCatching { AutoBackupManager.readPassphrase(context) }.getOrNull(),
            reminders = ReminderStore.load(context)
        )
    }
}
