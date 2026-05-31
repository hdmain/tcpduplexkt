package com.hdmain.tcpduplex.internal.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object KeyDerivation {

    fun deriveSessionKey(sharedSecret: ByteArray, preSharedKey: ByteArray?): ByteArray {
        if (preSharedKey == null || preSharedKey.isEmpty()) {
            return MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sharedSecret)
        digest.update(byteArrayOf(0))
        val lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(preSharedKey.size).array()
        digest.update(lengthBytes)
        digest.update(preSharedKey)
        return digest.digest()
    }
}
