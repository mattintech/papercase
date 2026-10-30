package com.mattintech.papercase.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.mattintech.papercase.MainActivity
import com.mattintech.papercase.data.PreferencesManager

@RequiresApi(Build.VERSION_CODES.N)
class PapercaseTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val prefs = PreferencesManager(this)
        qsTile?.apply {
            state = if (prefs.hasStoredPassphrase) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (prefs.hasStoredPassphrase) "Ready to copy" else "Not set"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_TRIGGER_COPY
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            // Only reached below API 34, where the Intent overload is still supported
            @SuppressLint("StartActivityAndCollapseDeprecated")
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
