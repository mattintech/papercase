package com.mattintech.papercase.locker.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A folder's photo (e.g. a child's face), kept small enough to live in the folder itself, so it's
 * encrypted with the catalog and travels with backups and transfers. Re-encoding drops all metadata.
 */
object FolderPhoto {

    /** Width and height of the stored photo, in pixels. */
    private const val SIZE = 192
    private const val JPEG_QUALITY = 85

    /** The centre square of [bitmap], scaled down to [SIZE] and encoded for [Folder.photo]. */
    fun encode(bitmap: Bitmap): String {
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== square) scaled.recycle()
        if (square !== bitmap) square.recycle()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun decode(photo: String): Bitmap? = runCatching {
        val bytes = Base64.getDecoder().decode(photo)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
