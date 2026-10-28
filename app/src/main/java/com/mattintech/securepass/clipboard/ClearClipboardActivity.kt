package com.mattintech.securepass.clipboard

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

        // Clear via manager (cancels countdown and notification)
        ClipboardSecurityManager.clearClipboard(this)

        // Ensure clip is cleared with active activity focus
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Toast.makeText(this, R.string.clipboard_cleared, Toast.LENGTH_SHORT).show()
        finish()
    }
}
