package com.mattintech.papercase.locker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mattintech.papercase.auth.SessionManager
import com.mattintech.papercase.data.PreferencesManager
import com.mattintech.papercase.databinding.ActivityLockerSettingsBinding
import com.mattintech.papercase.locker.backup.AutoBackupManager
import com.mattintech.papercase.locker.backup.AutoBackupManager.Frequency
import com.mattintech.papercase.locker.reminders.ReminderNotifier
import kotlinx.coroutines.launch

/** Automatic backup and expiry reminder settings. Opened from the locker, so the locker key is usable. */
class LockerSettingsActivity : AppCompatActivity() {

    companion object {
        private const val MIN_PASSWORD_LENGTH = 10

        fun start(context: Context) = context.startActivity(Intent(context, LockerSettingsActivity::class.java))
    }

    private lateinit var binding: ActivityLockerSettingsBinding
    private lateinit var prefs: PreferencesManager
    private var updatingUi = false

    /** Set when the user turns backups on before the folder / password are chosen. */
    private var enableWhenReady = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        SessionManager.isExternalOperationActive = false
        SessionManager.keepAlive()
        if (uri != null) {
            AutoBackupManager.setFolder(this, uri)
            if (enableWhenReady && !AutoBackupManager.hasPassword(this)) promptPassword() else maybeFinishEnabling()
        } else {
            enableWhenReady = false
        }
        render()
    }

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, "Allow notifications in system settings to get reminders.", Toast.LENGTH_LONG).show()
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferencesManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.switchAutoBackup.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            if (!checked) {
                AutoBackupManager.setEnabled(this, false)
                render()
            } else if (AutoBackupManager.isReady(this)) {
                AutoBackupManager.setEnabled(this, true)
                backUpNow()
            } else {
                // Walk through the missing setup, then turn on
                enableWhenReady = true
                if (AutoBackupManager.folder(this) == null) chooseFolder() else promptPassword()
                render()
            }
        }

        binding.chipGroupFrequency.setOnCheckedStateChangeListener { _, ids ->
            if (updatingUi) return@setOnCheckedStateChangeListener
            AutoBackupManager.setFrequency(this, if (ids.firstOrNull() == binding.chipDaily.id) Frequency.DAILY else Frequency.WEEKLY)
            render()
        }

        binding.btnChooseFolder.setOnClickListener { chooseFolder() }
        binding.btnSetPassword.setOnClickListener { promptPassword() }
        binding.btnBackupNow.setOnClickListener { backUpNow() }

        binding.switchReminders.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.isExpiryRemindersEnabled = checked
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!SessionManager.isLockerUnlocked) {
            finish()
            return
        }
        render()
    }

    private fun chooseFolder() {
        SessionManager.isExternalOperationActive = true
        pickFolder.launch(AutoBackupManager.folder(this))
    }

    private fun promptPassword() {
        val password = DialogInput(this, hint = "Backup password", password = true)
        val confirm = DialogInput(this, hint = "Confirm password", password = true)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(password.view)
            addView(confirm.view)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (AutoBackupManager.hasPassword(this)) "Change backup password" else "Set backup password")
            .setMessage(
                "You'll need this password to restore a backup, in Papercase or in KeePassXC. " +
                    "Papercase can't recover it, so keep it in your password manager."
            )
            .setView(fields)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel") { _, _ -> enableWhenReady = false; render() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pw = password.text
                when {
                    pw.length < MIN_PASSWORD_LENGTH ->
                        password.editText.error = "Use at least $MIN_PASSWORD_LENGTH characters"
                    pw != confirm.text ->
                        confirm.editText.error = "Passwords don't match"
                    else -> {
                        dialog.dismiss()
                        savePassword(pw)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun savePassword(password: String) {
        lifecycleScope.launch {
            try {
                AutoBackupManager.setPassword(this@LockerSettingsActivity, password)
                AutoBackupManager.refreshIfNeeded(this@LockerSettingsActivity)
                Toast.makeText(this@LockerSettingsActivity, "Backup password saved", Toast.LENGTH_SHORT).show()
                maybeFinishEnabling()
            } catch (e: Exception) {
                enableWhenReady = false
                Toast.makeText(this@LockerSettingsActivity, "Unlock the locker again, then retry.", Toast.LENGTH_LONG).show()
            }
            render()
        }
    }

    private fun maybeFinishEnabling() {
        if (enableWhenReady && AutoBackupManager.isReady(this)) {
            enableWhenReady = false
            AutoBackupManager.setEnabled(this, true)
            backUpNow()
        }
    }

    private fun backUpNow() {
        binding.btnBackupNow.isEnabled = false
        binding.tvBackupStatus.text = "Backing up…"
        lifecycleScope.launch {
            AutoBackupManager.refreshIfNeeded(this@LockerSettingsActivity)
            val message = when (val r = AutoBackupManager.runBackup(this@LockerSettingsActivity, force = true)) {
                AutoBackupManager.Result.Done -> "Backup saved"
                AutoBackupManager.Result.Unchanged -> "Already backed up. Nothing has changed since the last backup."
                AutoBackupManager.Result.NotDue -> null
                is AutoBackupManager.Result.Failed -> r.message
            }
            message?.let { Toast.makeText(this@LockerSettingsActivity, it, Toast.LENGTH_LONG).show() }
            binding.btnBackupNow.isEnabled = true
            render()
        }
    }

    private fun render() {
        updatingUi = true
        val enabled = AutoBackupManager.isEnabled(this)
        binding.switchAutoBackup.isChecked = enabled || enableWhenReady
        val frequency = AutoBackupManager.frequency(this)
        binding.chipGroupFrequency.check(if (frequency == Frequency.DAILY) binding.chipDaily.id else binding.chipWeekly.id)

        binding.tvBackupFolder.text = AutoBackupManager.folderName(this) ?: "Not chosen"
        binding.btnChooseFolder.text = if (AutoBackupManager.folder(this) == null) "Choose" else "Change"
        val hasPassword = AutoBackupManager.hasPassword(this)
        binding.tvBackupPassword.text = if (hasPassword) "Set" else "Not set"
        binding.btnSetPassword.text = if (hasPassword) "Change" else "Set"

        binding.tvBackupStatus.text = backupStatus(enabled, frequency)
        binding.btnBackupNow.isEnabled = AutoBackupManager.isReady(this)

        binding.switchReminders.isChecked = prefs.isExpiryRemindersEnabled
        if (prefs.isExpiryRemindersEnabled && !ReminderNotifier.canNotify(this)) {
            binding.tvRemindersInfo.text = "Notifications are blocked for Papercase. Allow them in system settings to get reminders."
        }
        updatingUi = false
    }

    private fun backupStatus(enabled: Boolean, frequency: Frequency): String {
        AutoBackupManager.lastError(this)?.let { return it }
        val last = AutoBackupManager.lastBackupAt(this)
        val lastText = if (last == 0L) "No backups yet." else
            "Last checked " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + "."
        if (!enabled) return lastText
        val schedule = if (frequency == Frequency.DAILY) "every day" else "every week"
        return "$lastText Backs up $schedule when something has changed."
    }
}
