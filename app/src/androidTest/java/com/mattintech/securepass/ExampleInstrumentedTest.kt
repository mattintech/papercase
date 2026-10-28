package com.mattintech.securepass

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

    @Test
    fun testOnlyEmptyClip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        instrumentation.runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            // Step 1: Copy sensitive string
            clipboard.setPrimaryClip(ClipData.newPlainText("test", "PASSWORD_ABC"))

            // Step 2: Overwrite with empty clip only (NO clearPrimaryClip)
            val emptyClip = ClipData.newPlainText("", "")
            clipboard.setPrimaryClip(emptyClip)

            val text = clipboard.primaryClip?.getItemAt(0)?.text
            android.util.Log.d("TEST_EMPTY_ONLY", "text: '$text', isNull: ${text == null}, isEmpty: ${text?.isEmpty()}")
        }
    }
}
