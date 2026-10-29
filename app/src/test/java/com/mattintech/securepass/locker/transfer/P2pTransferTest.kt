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
        val token = "SPVT-TEST-ABCD-1234-EFGH-5678"

        val latch = CountDownLatch(1)
        var serverStatus = ""
        var serverCompleted = false
        var serverError: String? = null

        val server = P2pTransferServer(
            vaultFile = sourceFile,
            expectedToken = token,
            timeoutSeconds = 10,
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
            token = token,
            destinationFile = destFile
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue("Server completed flag should be true", serverCompleted)
        assertTrue(destFile.exists())
        assertEquals(payloadData.size.toLong(), destFile.length())
        assertArrayEquals(payloadData, destFile.readBytes())

        server.stop()
    }

    @Test
    fun testUnauthorizedTokenTransferRejected() = runBlocking {
        val payloadData = "Secret Vault Bytes".toByteArray()
        val sourceFile = File(tempDir, "secret.spvault").apply { writeBytes(payloadData) }
        val destFile = File(tempDir, "rejected.spvault")
        val validToken = "SPVT-VALID-TOKEN-1111"
        val invalidToken = "SPVT-WRONG-TOKEN-9999"

        val server = P2pTransferServer(
            vaultFile = sourceFile,
            expectedToken = validToken,
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
                token = invalidToken,
                destinationFile = destFile
            )
            fail("Expected exception when using invalid token")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Transfer rejected") == true || e.message?.contains("403") == true)
        } finally {
            server.stop()
        }
    }
}
