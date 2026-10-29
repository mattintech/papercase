package com.mattintech.securepass.locker.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-use sender. After a receiver proves it scanned the QR (session id), [onReceiverConnected]
 * asks the sender to approve; it returns the encrypted vault to stream, or null if declined.
 */
const val DEVICE_NAME_HEADER = "X-SecurePass-Device"

class P2pTransferServer(
    private val expectedSessionId: String,
    private val timeoutSeconds: Int = 90,
    private val onReceiverConnected: suspend (receiverName: String, receiverAddress: String) -> File?,
    private val onStatusUpdate: (String) -> Unit,
    private val onTransferComplete: () -> Unit,
    private val onError: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start(scope: CoroutineScope) {
        if (isRunning.getAndSet(true)) return

        try {
            serverSocket = ServerSocket(0).apply {
                soTimeout = 3000 // 3s accept poll timeout to allow clean cancellation
            }
        } catch (e: Exception) {
            isRunning.set(false)
            onError("Failed to start transfer server: ${e.message}")
            return
        }

        val port = serverSocket?.localPort ?: -1
        onStatusUpdate("Ready on port $port. Waiting for receiver to connect...")

        serverJob = scope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val maxDurationMs = timeoutSeconds * 1000L

            try {
                while (isRunning.get()) {
                    if (System.currentTimeMillis() - startTime >= maxDurationMs) {
                        onStatusUpdate("Session expired")
                        onError("Transfer session timed out (no connection received)")
                        stop()
                        break
                    }

                    val clientSocket = try {
                        serverSocket?.accept()
                    } catch (_: SocketTimeoutException) {
                        null
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            onError("Connection error: ${e.message}")
                        }
                        break
                    }

                    if (clientSocket != null) {
                        handleClientConnection(clientSocket)
                        break // Single-use transfer completed or failed
                    }
                }
            } finally {
                stop()
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.use { client ->
                client.soTimeout = 15000 // 15s read timeout
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: ""
                val headers = readHeaders(reader)

                // Format expected: GET /vault?sid=<hex> HTTP/1.1
                val sessionIdFromRequest = extractSessionIdFromRequest(requestLine)

                if (sessionIdFromRequest == null || !MessageDigest.isEqual(
                        sessionIdFromRequest.toByteArray(Charsets.UTF_8),
                        expectedSessionId.toByteArray(Charsets.UTF_8)
                    )
                ) {
                    val out = client.getOutputStream()
                    val response = "HTTP/1.1 403 Forbidden\r\nContent-Length: 18\r\nConnection: close\r\n\r\nInvalid session id"
                    out.write(response.toByteArray())
                    out.flush()
                    onError("Rejected unauthorized connection attempt.")
                    return@use
                }

                val receiverName = sanitizeDeviceName(headers[DEVICE_NAME_HEADER.lowercase()])
                val receiverAddress = client.inetAddress?.hostAddress ?: "unknown"
                onStatusUpdate("$receiverName wants to receive your vault")

                val vaultFile = onReceiverConnected(receiverName, receiverAddress)
                if (vaultFile == null) {
                    val out = client.getOutputStream()
                    val body = "Sender declined"
                    out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                    out.flush()
                    onError("Transfer declined")
                    return@use
                }

                onStatusUpdate("Sending to $receiverName...")
                val out: OutputStream = client.getOutputStream()
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: ${vaultFile.length()}\r\n" +
                        "Connection: close\r\n\r\n"
                out.write(header.toByteArray())

                FileInputStream(vaultFile).use { fileIn ->
                    val buffer = ByteArray(32 * 1024)
                    var readBytes: Int
                    while (fileIn.read(buffer).also { readBytes = it } != -1) {
                        out.write(buffer, 0, readBytes)
                    }
                }
                out.flush()

                onStatusUpdate("Transfer complete!")
                onTransferComplete()
            }
        } catch (e: Exception) {
            onError("Transfer failed during transmission: ${e.message}")
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (headers.size < 32) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return headers
    }

    private fun sanitizeDeviceName(raw: String?): String {
        val clean = raw.orEmpty().filter { it.isLetterOrDigit() || it in " -_()." }.trim().take(40)
        return clean.ifEmpty { "Unknown device" }
    }

    private fun extractSessionIdFromRequest(requestLine: String): String? {
        // e.g. GET /vault?sid=9f2c... HTTP/1.1
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val uri = parts[1]
        val queryStart = uri.indexOf("?")
        if (queryStart == -1) return null
        val query = uri.substring(queryStart + 1)
        val params = query.split("&")
        for (param in params) {
            val kv = param.split("=")
            if (kv.size == 2 && kv[0] == "sid") {
                return kv[1].trim().lowercase()
            }
        }
        return null
    }

    fun stop() {
        if (isRunning.getAndSet(false)) {
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
        }
    }
}
