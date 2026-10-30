package com.mattintech.papercase.locker.ui.pages

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable

/**
 * Renders pages of a decrypted PDF. The bytes are kept in an anonymous in-memory file, so the
 * decrypted document is never written to storage. PdfRenderer allows one page at a time, hence the locks.
 */
class PdfPages private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : Closeable {

    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** Renders page [index] with its longest side [maxEdge] pixels, or null once closed. */
    @Synchronized
    fun render(index: Int, maxEdge: Int): Bitmap? {
        if (closed) return null
        renderer.openPage(index).use { page ->
            val scale = maxEdge.toFloat() / maxOf(page.width, page.height)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            // PDF pages have no background of their own; without this they render transparent
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
        pfd.close()
    }

    companion object {
        fun open(bytes: ByteArray): PdfPages {
            val fd = Os.memfd_create("papercase-pdf", 0)
            try {
                var written = 0
                while (written < bytes.size) written += Os.write(fd, bytes, written, bytes.size - written)
                Os.lseek(fd, 0, OsConstants.SEEK_SET)
                val pfd = ParcelFileDescriptor.dup(fd)
                return try {
                    PdfPages(pfd, PdfRenderer(pfd))
                } catch (e: Exception) {
                    pfd.close()
                    throw e
                }
            } finally {
                Os.close(fd)
            }
        }
    }
}
