package com.mattintech.securepass.clipboard

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mattintech.securepass.R

class ClearClipboardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Execute canonical clear and cancel countdown
        ClipboardSecurityManager.clearClipboard(applicationContext)
        Toast.makeText(this, R.string.clipboard_cleared, Toast.LENGTH_SHORT).show()
        finish()
    }
}
