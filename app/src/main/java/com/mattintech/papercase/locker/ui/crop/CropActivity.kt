package com.mattintech.papercase.locker.ui.crop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import com.mattintech.papercase.BuildConfig
import com.mattintech.papercase.auth.SessionManager
import com.mattintech.papercase.databinding.ActivityCropBinding

/**
 * Lets the user trim a document photo to just the document. The photo is handed over in memory
 * (never written to disk) through [Contract].
 */
class CropActivity : AppCompatActivity() {

    /** Input: photo to crop. Output: cropped photo, the original ("Use full photo"), or null if cancelled. */
    class Contract : ActivityResultContract<Bitmap, Bitmap?>() {
        override fun createIntent(context: Context, input: Bitmap): Intent {
            pending = input
            result = null
            return Intent(context, CropActivity::class.java)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Bitmap? {
            val out = if (resultCode == Activity.RESULT_OK) result else null
            pending = null
            result = null
            return out
        }
    }

    private lateinit var binding: ActivityCropBinding
    private var photo: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val input = pending
        if (input == null) {
            finish()
            return
        }
        photo = input
        binding.cropView.setBitmap(input)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRotate.setOnClickListener {
            val current = photo ?: return@setOnClickListener
            val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, Matrix().apply { postRotate(90f) }, true)
            photo = rotated
            binding.cropView.setBitmap(rotated)
        }
        binding.btnFullPhoto.setOnClickListener { finishWith(photo) }
        binding.btnDone.setOnClickListener { finishWith(binding.cropView.crop() ?: photo) }
    }

    override fun onResume() {
        super.onResume()
        if (!SessionManager.isLockerUnlocked) finish()
    }

    private fun finishWith(bitmap: Bitmap?) {
        result = bitmap
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        // In-memory hand-off so document photos never touch the disk
        private var pending: Bitmap? = null
        private var result: Bitmap? = null
    }
}
