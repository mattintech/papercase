package com.mattintech.papercase.ui

import android.content.Context
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// Dialogs get their own window, so the activity's FLAG_SECURE doesn't cover them.
// Use these in place of the Material classes so every dialog blocks screenshots.

class SecureAlertDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {
    override fun create(): AlertDialog = super.create().apply {
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
}

class SecureBottomSheetDialog(context: Context) : BottomSheetDialog(context) {
    init {
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
}
