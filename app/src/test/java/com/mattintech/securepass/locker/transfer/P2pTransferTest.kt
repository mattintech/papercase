package com.mattintech.securepass.locker.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class P2pTransferTest {

    private lateinit var tempDir: File
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "p2p_test_" + System.currentTimeMillis())
        tempDir.mkdirs()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun testSuccessfulP2pTransfer() = runBlocking {
        val payloadData = "Hello SecurePass P2P Vault Transfer! Secret test payload".toByteArray()
        val sourceFile = File(tempDir, "test_vault.spvault").apply {
            writeBytes(payloadData)
        }
        val destFile = File(tempDir, "received_vault.spvault")
        val sessionKeys = P2pSessionKeys(ByteArray(32) { it.toByte() })

        val latch = CountDownLatch(1)
        var serverStatus = ""
        var serverCompleted = false
        var serverError: String? = null

        var approvedName: String? = null
        val server = P2pTransferServer(
            expectedSessionId = sessionKeys.sessionId,
            timeoutSeconds = 10,
            onReceiverConnected = { name, _ ->
                approvedName = name
                sourceFile
            },
            onStatusUpdate = { serverStatus = it },
            onTransferComplete = {
                serverCompleted = true
                latch.countDown()
            },
            onError = { serverError = it }
        )

        server.start(testScope)

        // Give server a moment to bind port
        var attempts = 0
        while (server.localPort <= 0 && attempts++ < 20) {
            Thread.sleep(50)
        }
        val port = server.localPort
        assertTrue("Server port should be positive", port > 0)

        // Download via client
        val downloaded = P2pTransferClient.downloadVault(
            host = "127.0.0.1",
            port = port,
            sessionId = sessionKeys.sessionId,
            deviceName = "Samsung SM-X230\r\nInjected: header",
            destinationFile = destFile
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("Server completed flag should be true", serverCompleted)
        assertTrue(destFile.exists())
        assertEquals(payloadData.size.toLong(), destFile.length())
        assertArrayEquals(payloadData, destFile.readBytes())
        assertEquals("Samsung SM-X230  Injected header", approvedName)

        server.stop()
    }

    @Test
    fun testWrongSessionIdTransferRejected() = runBlocking {
        val payloadData = "Secret Vault Bytes".toByteArray()
        val sourceFile = File(tempDir, "secret.spvault").apply { writeBytes(payloadData) }
        val destFile = File(tempDir, "rejected.spvault")
        val validKeys = P2pSessionKeys(ByteArray(32) { 1 })
        val wrongKeys = P2pSessionKeys(ByteArray(32) { 2 })

        val server = P2pTransferServer(
            expectedSessionId = validKeys.sessionId,
            onReceiverConnected = { _, _ -> fail("Wrong session id must not reach approval"); null },
            timeoutSeconds = 10,
            onStatusUpdate = {},
            onTransferComplete = {},
            onError = {}
        )

        server.start(testScope)
        var attempts = 0
        while (server.localPort <= 0 && attempts++ < 20) {
            Thread.sleep(50)
        }
        val port = server.localPort

        try {
            P2pTransferClient.downloadVault(
                host = "127.0.0.1",
                port = port,
                sessionId = wrongKeys.sessionId,
                deviceName = "Test",
                destinationFile = destFile
            )
            fail("Expected exception when using wrong session id")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Transfer rejected") == true || e.message?.contains("403") == true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun testSessionIdIsIndependentOfEncryptionKey() {
        val keys = P2pSessionKeys(ByteArray(32) { 7 })
        val encHex = keys.encryptionKey.joinToString("") { "%02x".format(it) }
        assertEquals(64, keys.sessionId.length)
        assertEquals(32, keys.encryptionKey.size)
        assertTrue("Session id must not equal the encryption key", keys.sessionId != encHex)
        assertEquals(keys.sessionId, P2pSessionKeys(ByteArray(32) { 7 }).sessionId)
    }

    @Test
    fun testSenderDeclineSendsNothing() = runBlocking {
        val keys = P2pSessionKeys(ByteArray(32) { 3 })
        val destFile = File(tempDir, "declined.spvault")
        var declinedError: String? = null

        val server = P2pTransferServer(
            expectedSessionId = keys.sessionId,
            timeoutSeconds = 10,
            onReceiverConnected = { _, _ -> null },
            onStatusUpdate = {},
            onTransferComplete = { fail("Declined transfer must not complete") },
            onError = { declinedError = it }
        )
        server.start(testScope)
        var attempts = 0
        while (server.localPort <= 0 && attempts++ < 20) {
            Thread.sleep(50)
        }

        try {
            P2pTransferClient.downloadVault(
                host = "127.0.0.1",
                port = server.localPort,
                sessionId = keys.sessionId,
                deviceName = "Test",
                destinationFile = destFile
            )
            fail("Expected exception when the sender declines")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Sender declined") == true)
        } finally {
            server.stop()
        }
        assertEquals("Transfer declined", declinedError)
        assertTrue(!destFile.exists() || destFile.length() == 0L)
    }
}
