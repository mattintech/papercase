package com.mattintech.papercase.locker.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object P2pTransferClient {

    suspend fun downloadVault(
        host: String,
        port: Int,
        sessionId: String,
        deviceName: String,
        destinationFile: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val socket = Socket()
        socket.soTimeout = 120000 // the sender has to approve and authenticate before data flows
        socket.connect(InetSocketAddress(host, port), 10000) // 10s connect timeout

        socket.use { s ->
            val out = s.getOutputStream()
            val request = "GET /vault?sid=$sessionId HTTP/1.1\r\nHost: $host:$port\r\n" +
                "$DEVICE_NAME_HEADER: ${deviceName.replace(Regex("[\\r\\n]"), " ")}\r\nConnection: close\r\n\r\n"
            out.write(request.toByteArray())
            out.flush()

            val inStream = BufferedInputStream(s.getInputStream())

            // Read HTTP headers until \r\n\r\n
            val headerBytes = ByteArrayOutputStream()
            var prev1 = -1
            var prev2 = -1
            var prev3 = -1
            var b: Int

            while (inStream.read().also { b = it } != -1) {
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                    break
                }
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }

            val headerText = headerBytes.toString("UTF-8")
            val statusLine = headerText.lines().firstOrNull() ?: ""
            if (!statusLine.contains("200")) {
                val errorBody = inStream.bufferedReader().use { it.readText() }
                throw IllegalStateException("Transfer rejected by sender ($statusLine: $errorBody)")
            }

            // Extract Content-Length if present
            var totalBytes = -1L
            for (line in headerText.lines()) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    totalBytes = line.substringAfter(":").trim().toLongOrNull() ?: -1L
                }
            }

            // Stream encrypted vault payload
            FileOutputStream(destinationFile).use { fileOut ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                var downloaded = 0L

                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    fileOut.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onProgress(downloaded, totalBytes)
                }
                fileOut.flush()
            }

            if (destinationFile.length() == 0L) {
                throw IllegalStateException("Downloaded vault file is empty")
            }

            destinationFile
        }
    }
}
