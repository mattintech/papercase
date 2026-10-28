package com.mattintech.securepass.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mattintech.securepass.R

class ClearClipboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Overwrite and clear while holding active foreground window focus
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val emptyClip = ClipData.newPlainText("", "")
            clipboard.setPrimaryClip(emptyClip)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Cancel countdown jobs & dismiss notifications
        ClipboardSecurityManager.clearClipboard(applicationContext)

        Toast.makeText(this, R.string.clipboard_cleared, Toast.LENGTH_SHORT).show()
        finish()
    }
}
