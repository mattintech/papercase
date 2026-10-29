package com.mattintech.securepass.locker.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

object P2pTransferClient {

    suspend fun downloadVault(
        host: String,
        port: Int,
        token: String,
        destinationFile: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val cleanToken = token.trim().uppercase().replace(" ", "")
        val url = URL("http://$host:$port/vault?token=$cleanToken")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000 // 10s connection timeout
            readTimeout = 30000    // 30s read timeout
            doInput = true
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("Transfer rejected by sender (HTTP $responseCode: $errorStream)")
            }

            val totalBytes = connection.contentLengthLong
            val inputStream: InputStream = connection.inputStream

            FileOutputStream(destinationFile).use { fileOut ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                var downloaded = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
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
        } finally {
            connection.disconnect()
        }
    }
}
