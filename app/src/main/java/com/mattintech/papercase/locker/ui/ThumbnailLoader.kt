package com.mattintech.papercase.locker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.mattintech.papercase.locker.model.SecureDocument
import com.mattintech.papercase.locker.repository.SecureLockerRepository
import com.mattintech.papercase.locker.ui.pages.PdfPages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Card thumbnails. Each is made once from the document's front side (or first page), stored
 * encrypted next to the locker files, and kept in memory while the locker is open.
 */
class ThumbnailLoader(private val repository: SecureLockerRepository) {

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Null when the document has nothing to show or is password protected. */
    suspend fun load(doc: SecureDocument): Bitmap? = withContext(Dispatchers.IO) {
        val source = repository.thumbnailSource(doc) ?: return@withContext null
        cache.get(source.encryptedFileName)?.let { return@withContext it }
        val jpeg = repository.readThumbnail(source) ?: make(source.isPdf, repository.readAttachmentBytes(source))
            ?.also { repository.saveThumbnail(source, it) }
            ?: return@withContext null
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.also { cache.put(source.encryptedFileName, it) }
    }

    fun clear() = cache.evictAll()

    private fun make(isPdf: Boolean, bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val full = if (isPdf) {
            runCatching { PdfPages.open(bytes).use { it.render(0, SIZE) } }.getOrNull()
        } else {
            // Decode at a fraction of full size; thumbnails are small
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
            OrientedBitmap.decode(bytes, sample)
        } ?: return null
        val scale = SIZE.toFloat() / maxOf(full.width, full.height)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
        } else full
        return ByteArrayOutputStream().use { out ->
            thumb.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    }

    private companion object {
        const val SIZE = 480
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}
