package com.mattintech.securepass

import android.app.Application
import com.mattintech.securepass.clipboard.ClipboardSecurityManager

class SecurePassApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ClipboardSecurityManager.createNotificationChannel(this)
    }
}
