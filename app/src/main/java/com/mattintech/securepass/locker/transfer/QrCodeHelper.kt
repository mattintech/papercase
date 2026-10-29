package com.mattintech.securepass.locker.transfer

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.security.SecureRandom

object QrCodeHelper {

    private const val TOKEN_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Crockford Base32-like (unambiguous)

    fun generateSecureToken(): String {
        val random = SecureRandom()
        val segments = (1..5).map {
            (1..4).map { TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)] }.joinToString("")
        }
        return "SPVT-" + segments.joinToString("-")
    }

    fun generateBitMatrix(content: String, size: Int = 512): BitMatrix {
        val writer = QRCodeWriter()
        return writer.encode(content, BarcodeFormat.QR_CODE, size, size)
    }

    fun generateQrCodeBitmap(content: String, size: Int = 512): Bitmap {
        val bitMatrix = generateBitMatrix(content, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)

        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    fun decodeQrCodeFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)
            result.text
        } catch (_: Exception) {
            null
        }
    }
}
