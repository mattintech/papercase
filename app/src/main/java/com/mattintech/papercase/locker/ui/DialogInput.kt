package com.mattintech.papercase.locker.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * An outlined Material text field inset to line up with the dialog's title and message,
 * for use with SecureAlertDialogBuilder.setView().
 */
class DialogInput(context: Context, hint: String, password: Boolean = false) {

    val editText: TextInputEditText
    val view: View

    val text: String
        get() = editText.text?.toString().orEmpty()

    init {
        val density = context.resources.displayMetrics.density
        val horizontal = (24 * density).toInt()
        val top = (8 * density).toInt()

        val layout = TextInputLayout(
            context,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            this.hint = hint
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        editText = TextInputEditText(layout.context).apply {
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            isSingleLine = true
        }
        layout.addView(editText)

        view = FrameLayout(context).apply {
            setPadding(horizontal, top, horizontal, 0)
            addView(layout)
        }
    }
}
