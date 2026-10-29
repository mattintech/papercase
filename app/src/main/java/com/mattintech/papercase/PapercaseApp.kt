package com.mattintech.papercase

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.mattintech.papercase.auth.SessionManager
import com.mattintech.papercase.clipboard.ClipboardSecurityManager

class PapercaseApp : Application() {

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        ClipboardSecurityManager.createNotificationChannel(this)
        SessionManager.init(this)

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
