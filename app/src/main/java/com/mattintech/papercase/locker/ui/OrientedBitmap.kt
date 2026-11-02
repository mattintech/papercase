package com.mattintech.papercase.locker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Decodes an image the way it was taken. Camera apps usually store photos in sensor orientation and
 * record the real rotation in EXIF, which BitmapFactory ignores.
 */
object OrientedBitmap {

    /**
     * Like [decode], but skips pixels while decoding so the result isn't much bigger than [maxEdge].
     * Decoding a 200 MP photo at full size needs about 800 MB, more than Android gives an app.
     */
    fun decodeForEdge(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return decode(bytes, sample)
    }

    /** [sampleSize] > 1 decodes a smaller image (e.g. 4 = a quarter of the width and height). */
    fun decode(bytes: ByteArray, sampleSize: Int = 1): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }

        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
