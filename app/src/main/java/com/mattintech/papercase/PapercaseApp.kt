package com.mattintech.papercase

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import com.mattintech.papercase.auth.SessionManager
import com.mattintech.papercase.clipboard.ClipboardSecurityManager
import com.mattintech.papercase.data.PreferencesManager
import com.mattintech.papercase.locker.backup.AutoBackupManager
import com.mattintech.papercase.locker.backup.DailyWorker
import com.mattintech.papercase.locker.reminders.ReminderNotifier

class PapercaseApp : Application() {

    private var startedActivityCount = 0

    // Held here: SharedPreferences only keeps a weak reference to listeners
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (PreferencesManager.isBackedUp(key)) AutoBackupManager.onLockerChanged(this)
    }

    override fun onCreate() {
        super.onCreate()
        ClipboardSecurityManager.createNotificationChannel(this)
        ReminderNotifier.createChannel(this)
        SessionManager.init(this)
        DailyWorker.schedule(this)
        getSharedPreferences(PreferencesManager.NAME, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    SessionManager.onAppEnteredForeground()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    SessionManager.onAppEnteredBackground()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
