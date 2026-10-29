package com.mattintech.papercase.locker.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Removes all metadata (GPS location, timestamps, device model, embedded thumbnail) from images
 * before they're stored, by re-encoding the pixels. Rotation from EXIF is applied first, so the
 * result is upright without needing the orientation tag. Non-images (e.g. PDFs) are returned as-is.
 */
object ImageMetadataStripper {

    private const val JPEG_QUALITY = 92

    data class Result(val bytes: ByteArray, val mimeType: String, val fileName: String)

    fun strip(bytes: ByteArray, mimeType: String, fileName: String): Result {
        if (!mimeType.startsWith("image/")) return Result(bytes, mimeType, fileName)
        val bitmap = OrientedBitmap.decode(bytes) ?: return Result(bytes, mimeType, fileName)

        val keepPng = mimeType == "image/png"
        val out = ByteArrayOutputStream()
        bitmap.compress(
            if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
            JPEG_QUALITY,
            out
        )
        bitmap.recycle()

        return if (keepPng) {
            Result(out.toByteArray(), mimeType, fileName)
        } else {
            // HEIC / WebP / etc. are stored as JPEG once re-encoded
            val baseName = fileName.substringBeforeLast('.', fileName)
            Result(out.toByteArray(), "image/jpeg", "$baseName.jpg")
        }
    }
}
