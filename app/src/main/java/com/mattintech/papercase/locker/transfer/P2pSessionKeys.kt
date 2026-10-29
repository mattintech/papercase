package com.mattintech.papercase.locker.transfer

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives independent sub-keys from the QR transfer key so the key itself never crosses the network:
 * - sessionId: sent in the clear by the receiver to claim the session (useless for decryption)
 * - encryptionKey: AES-256-GCM key for the vault payload
 */
class P2pSessionKeys(transferKey: ByteArray) {

    val sessionId: String = derive(transferKey, "papercase-p2p-v2-session")
        .joinToString("") { "%02x".format(it) }

    val encryptionKey: ByteArray = derive(transferKey, "papercase-p2p-v2-encryption")

    private fun derive(key: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }
}
